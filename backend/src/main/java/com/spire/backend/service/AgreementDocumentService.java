package com.spire.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.spire.backend.entity.ConsultantApplication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import org.springframework.context.expression.MapAccessor;
import pro.verron.officestamper.api.OfficeStamperConfiguration;
import pro.verron.officestamper.api.StreamStamper;
import pro.verron.officestamper.preset.Image;
import pro.verron.officestamper.preset.OfficeStamperConfigurations;
import pro.verron.officestamper.preset.OfficeStampers;
import pro.verron.officestamper.preset.Resolvers;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

// Build I — PDFBox rasterises the consultant review preview into page
// images so the wizard can show watermarked PNGs instead of a saveable
// PDF.
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Two-stage agreement PDF generator.
 *
 * Pipeline: docx-stamper fills the prepared Word template with the
 * application's data (null-safe -- missing fields render as blank,
 * never as literal ${name} text), LibreOffice converts the filled
 * .docx to PDF in a headless subprocess, and Cloudinary stores the
 * artifact under a stable {@code agreements/{appId}} public_id so
 * later regenerations overwrite cleanly.
 *
 * The method is callable at any state of the workflow: when the
 * consultant has signed but the ERM hasn't, {@code ermSignatureUrl}
 * is null and that placeholder renders empty (no broken image). When
 * both have signed, both signature images are embedded inline.
 *
 * Phase 3 will pick when to call this and which URL field on the
 * entity to persist into ({@code signedPdfUrl} for the intermediate
 * consultant-only artifact, {@code finalPdfUrl} for the final
 * countersigned version). This service is intentionally stateless --
 * it neither mutates the entity nor writes to the DB.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgreementDocumentService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy");

    /** Build G — US short date used wherever the consultant sees a
     *  date in the wizard / cover read-back / PDF date columns. */
    private static final DateTimeFormatter US_SHORT_DATE_FMT =
            DateTimeFormatter.ofPattern("MM-dd-yyyy");

    /** LibreOffice conversion timeout. 60s is comfortably above the
     *  observed ~3-8s cold-start range on Railway's containers. */
    private static final long LIBREOFFICE_TIMEOUT_SECONDS = 60L;

    private final Cloudinary cloudinary;
    /** Phase 1 (S3) — storage backend for NEWLY generated agreement PDFs. */
    private final DocumentStorage documentStorage;
    /** Phase B — resolve the owning ERM's email for the ${ermEmail}
     *  placeholder, replacing the global env value. */
    private final com.spire.backend.repository.AgreementUserRepository agreementUserRepository;

    @Value("classpath:templates/SageITCO_Master_Agreement_TEMPLATE.docx")
    private Resource templateResource;

    /** Legacy global agreement-ERM operator email. Phase B no longer
     *  uses this for the ${ermEmail} placeholder (now owner-resolved);
     *  kept only as a fallback when the owner can't be resolved, and to
     *  avoid a startup change to the @Value injection. */
    @Value("${agreement-erm.email}")
    private String agreementErmEmail;

    /**
     * Carries the Cloudinary {@code secure_url} + {@code public_id} for
     * later persistence AND the raw PDF bytes that were uploaded. Bytes
     * are returned so the immediate caller (post-countersign email) can
     * attach them directly without re-fetching from Cloudinary -- the
     * stored {@code secure_url} carries a per-upload signature that
     * 401s when GET'd from the server later (observed: every completion
     * email shipped with attachments=0). Direct bytes also avoid the
     * 1-2 MB egress round-trip on the happy path.
     */
    public record PdfUploadResult(String secureUrl, String publicId, byte[] bytes) {}

    public PdfUploadResult generateAgreementPdf(ConsultantApplication app) throws Exception {
        // Build N — renderPdfBytes now centralizes appendAttachments, so the
        // stored/COMPLETED PDF already includes the uploaded documents.
        byte[] bytes = renderPdfBytes(app, null);
        // Phase 1 (S3) — store the final PDF in S3 and persist its key via
        // PdfUploadResult.publicId(). Cloudinary fields are left null for new
        // records (the download path dual-reads on s3_key). Cloudinary upload
        // code (uploadBytesToCloudinary) is kept intact for old records.
        String key = documentStorage.store(
                bytes, buildAgreementPdfKey(app, "final"), "application/pdf");
        return new PdfUploadResult(null, key, bytes);
    }

    /**
     * Build S — read a stored agreement PDF's bytes by its S3 key (the durable
     * Phase-1 signed snapshot). The approver Phase-1 preview rasterizes THIS
     * snapshot rather than live-rendering current entity state (which would
     * reflect the Phase-2 doc / blank signatures after the advance).
     */
    public byte[] readStoredPdfBytes(String s3Key) {
        return documentStorage.get(s3Key);
    }

    /**
     * Phase 1 (S3) — object key for a generated agreement PDF:
     * {@code agreements/{ermId}/phase-{1|2}/{documentType}-{yyyyMMdd-HHmmss}.pdf}.
     * The timestamp makes every generation a fresh key (bucket versioning also
     * retains prior bytes). Falls back to {@code system} when the owning ERM
     * isn't set (legacy rows).
     */
    private String buildAgreementPdfKey(ConsultantApplication app, String documentType) {
        String ermId = (app.getOwnerErmId() == null || app.getOwnerErmId().isBlank())
                ? "system" : app.getOwnerErmId();
        int phase = app.getPhase() == null ? 1 : app.getPhase();
        String ts = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return "agreements/" + ermId + "/phase-" + phase + "/"
                + documentType + "-" + ts + ".pdf";
    }

    /**
     * Phase 1 — resolve a short-lived source URL to GET the final agreement
     * PDF bytes, dual-reading by storage backend:
     *   S3 (new records, {@code s3Key} set) → presigned GET URL;
     *   else Cloudinary ({@code finalPdfPublicId}/derived) → signed URL;
     *   else the legacy intermediate {@code signedPdfUrl}.
     * Returns null when no document is available. For S3 records this NEVER
     * falls back to Cloudinary — a presign failure surfaces as the same kind
     * of error the Cloudinary path would.
     */
    public String finalPdfSourceUrl(ConsultantApplication app, Duration ttl) {
        String s3Key = app.getS3Key();
        if (s3Key != null && !s3Key.isBlank()) {
            return documentStorage.presignedGetUrl(s3Key, ttl, null, true);
        }
        boolean hasFinal = app.getFinalPdfPublicId() != null
                || (app.getFinalPdfUrl() != null && !app.getFinalPdfUrl().isBlank());
        if (hasFinal) {
            String publicId = app.getFinalPdfPublicId();
            if (publicId == null || publicId.isBlank()) {
                publicId = derivePublicId(app.getApplicationId());
            }
            return signedPdfUrl(publicId, ttl);
        }
        if (app.getSignedPdfUrl() != null && !app.getSignedPdfUrl().isBlank()) {
            return app.getSignedPdfUrl();
        }
        return null;
    }

    /**
     * Phase 1 (S3) — store the already-rendered consultant-version PDF
     * (consultant copy + Certificate of Completion) in S3 and return its key.
     */
    public String storeConsultantVersionPdf(ConsultantApplication app, byte[] bytes) {
        return documentStorage.store(
                bytes, buildAgreementPdfKey(app, "consultant"), "application/pdf");
    }

    /**
     * Phase 1 — short-lived source URL for the consultant-version PDF:
     * S3 ({@code consultantPdfS3Key}) → presigned; else Cloudinary
     * ({@code consultantPdfPublicId}) → signed. Null when neither is set.
     */
    public String consultantPdfSourceUrl(ConsultantApplication app, Duration ttl) {
        String s3Key = app.getConsultantPdfS3Key();
        if (s3Key != null && !s3Key.isBlank()) {
            return documentStorage.presignedGetUrl(s3Key, ttl, null, true);
        }
        String publicId = app.getConsultantPdfPublicId();
        if (publicId != null && !publicId.isBlank()) {
            return signedPdfUrl(publicId, ttl);
        }
        return null;
    }

    /**
     * Build I — append the consultant's uploaded documents to the agreement
     * PDF as labeled attachment page(s), AFTER the body/appendices and
     * BEFORE any Certificate of Completion (the caller appends the cert
     * last). Order: Work Authorization → Offer Letter → Driver's License /
     * State ID → SSN (when present). Image uploads are drawn onto a page
     * with aspect preserved; PDF uploads are merged page-for-page.
     * Best-effort: a single bad attachment is skipped (logged) rather than
     * failing the whole agreement render. Callers compute the SHA-256 over
     * the returned bytes so the hash covers the attachments.
     */
    public byte[] appendAttachments(byte[] body, ConsultantApplication app) {
        // Phase 5 — an attachment is present when EITHER its S3 key or its
        // legacy Cloudinary id is set (dual-read).
        boolean hasWorkAuth = nonBlank(app.getWorkAuthDocS3Key()) || nonBlank(app.getWorkAuthDocPublicId());
        boolean hasOffer = nonBlank(app.getOfferLetterS3Key()) || nonBlank(app.getOfferLetterPublicId());
        boolean hasDl = nonBlank(app.getDlDocS3Key()) || nonBlank(app.getDlDocPublicId());
        boolean hasSsn = nonBlank(app.getSsnDocS3Key()) || nonBlank(app.getSsnDocPublicId());
        if (!hasWorkAuth && !hasOffer && !hasDl && !hasSsn) return body;
        try (PDDocument doc = Loader.loadPDF(body)) {
            if (hasWorkAuth) {
                appendOneAttachment(doc, "Attachment — Work Authorization Document",
                        app.getWorkAuthDocS3Key(), app.getWorkAuthDocPublicId(), app.getWorkAuthDocContentType());
            }
            if (hasOffer) {
                appendOneAttachment(doc, "Attachment — Offer Letter",
                        app.getOfferLetterS3Key(), app.getOfferLetterPublicId(), app.getOfferLetterContentType());
            }
            if (hasDl) {
                appendOneAttachment(doc, "Attachment — Driver's License / State ID",
                        app.getDlDocS3Key(), app.getDlDocPublicId(), app.getDlDocContentType());
            }
            if (hasSsn) {
                appendOneAttachment(doc, "Attachment — SSN Document",
                        app.getSsnDocS3Key(), app.getSsnDocPublicId(), app.getSsnDocContentType());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to append attachments for {}: {}",
                    app.getApplicationId(), e.getMessage());
            return body; // never block the agreement PDF on an attachment hiccup
        }
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Append one upload: prepare its content first, then a divider + the content. */
    private void appendOneAttachment(
            PDDocument doc, String label, String s3Key, String publicId, String contentType) {
        try {
            byte[] bytes = fetchAttachmentBytes(s3Key, publicId, contentType);
            if (bytes == null || bytes.length == 0) return;
            boolean isPdf = "application/pdf".equalsIgnoreCase(contentType);
            if (isPdf) {
                try (PDDocument src = Loader.loadPDF(bytes)) {
                    addAttachmentDivider(doc, label);
                    new PDFMergerUtility().appendDocument(doc, src);
                }
            } else {
                // Decode the image first; only add the divider if it's usable.
                PDImageXObject img = PDImageXObject.createFromByteArray(doc, bytes, label);
                addAttachmentDivider(doc, label);
                addImagePage(doc, img);
            }
        } catch (Exception e) {
            log.warn("Skipping attachment '{}' ({}): {}", label, publicId, e.getMessage());
        }
    }

    private void addAttachmentDivider(PDDocument doc, String label) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        float w = PDRectangle.LETTER.getWidth();
        float h = PDRectangle.LETTER.getHeight();
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            // sage-navy header band (#1B2A5C)
            cs.setNonStrokingColor(0x1B / 255f, 0x2A / 255f, 0x5C / 255f);
            cs.addRect(0, h - 96f, w, 96f);
            cs.fill();
            cs.beginText();
            cs.setNonStrokingColor(1f, 1f, 1f);
            cs.setFont(CertFonts.HELVETICA_BOLD, 16);
            cs.newLineAtOffset(54f, h - 56f);
            cs.showText(label);
            cs.endText();
            // copper sub-line (#C87D5C)
            cs.beginText();
            cs.setNonStrokingColor(0xC8 / 255f, 0x7D / 255f, 0x5C / 255f);
            cs.setFont(CertFonts.HELVETICA, 10);
            cs.newLineAtOffset(54f, h - 130f);
            cs.showText("Uploaded by the consultant and attached to this agreement.");
            cs.endText();
        }
    }

    private void addImagePage(PDDocument doc, PDImageXObject img) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        float pw = PDRectangle.LETTER.getWidth();
        float ph = PDRectangle.LETTER.getHeight();
        float margin = 36f;
        float maxW = pw - 2 * margin;
        float maxH = ph - 2 * margin;
        float iw = img.getWidth();
        float ih = img.getHeight();
        float scale = Math.min(maxW / iw, maxH / ih);
        if (scale > 1f) scale = 1f; // never upscale past native resolution
        float dw = iw * scale;
        float dh = ih * scale;
        float x = (pw - dw) / 2f;
        float y = (ph - dh) / 2f;
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(img, x, y, dw, dh);
        }
    }

    /**
     * Fetch an uploaded attachment's bytes. Phase 5 dual-read: the S3 object
     * when {@code s3Key} is set, else the legacy authenticated-Cloudinary
     * upload by public_id.
     */
    private byte[] fetchAttachmentBytes(String s3Key, String publicId, String contentType)
            throws IOException {
        if (s3Key != null && !s3Key.isBlank()) {
            return documentStorage.get(s3Key);
        }
        boolean isPdf = "application/pdf".equalsIgnoreCase(contentType);
        String url = cloudinary.url()
                .resourceType(isPdf ? "raw" : "image")
                .type("authenticated")
                .signed(true)
                .secure(true)
                .generate(publicId);
        URLConnection conn = new URL(url).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        }
    }

    /**
     * Build G — pure render path: DOCX → LibreOffice → bytes. Does NOT
     * upload to Cloudinary. Used by:
     *   - {@link #generateAgreementPdf} for the final stored PDF
     *     (caller uploads the returned bytes).
     *   - {@code /preview-pdf} endpoints for the consultant + ERM
     *     in-place previews. Previews stream the bytes directly to
     *     the browser, so no Cloudinary round-trip happens.
     *
     * The optional {@code overrides} swap individual context values
     * before stamping. The preview endpoints use this to:
     *   - inject a draft primary signature from a data: URL when it
     *     hasn't been uploaded yet (consultant preview at the
     *     review step), or
     *   - blank out signatures that shouldn't appear yet (ERM
     *     preview hides the ERM signature; consultant preview
     *     hides final + ERM).
     *
     * Every signature variant runs through {@link #normalizeSignaturePng}
     * via {@link #buildImage} or {@link #buildImageFromDataUrl} so
     * previews and finals are sized identically.
     */
    public byte[] renderPdfBytes(
            ConsultantApplication app,
            ContextOverrides overrides) throws Exception {
        Map<String, Object> ctx = buildContext(app);
        if (overrides != null) overrides.apply(ctx, this);
        Path filledDocx = null;
        Path pdf = null;
        try {
            filledDocx = fillTemplate(ctx);
            pdf = convertToPdf(filledDocx);
            // Build N — the append is now centralized here, so EVERY
            // full-agreement render (consultant preview, ERM preview, ERM
            // download, consultant-version) carries the uploaded documents
            // as appended pages. Callers that add a Certificate of Completion
            // still append it AFTER this, keeping the certificate last.
            return appendAttachments(Files.readAllBytes(pdf), app);
        } finally {
            safeDelete(filledDocx);
            safeDelete(pdf);
        }
    }

    /**
     * Rasterises {@code pdfBytes} (typically from
     * {@link #renderPdfBytes}) into a list of PNG byte arrays, one per
     * PDF page. Render DPI is intentionally modest (~110) so the
     * bytes stay cheap to stream.
     *
     * Build T removed the diagonal watermark; Build L restores it, but
     * ONLY for the consultant pre-signature preview ({@code watermark=true}).
     * The approver preview passes {@code watermark=false} (clean images).
     * The preview is still image-only (no downloadable PDF) and the
     * scroll-gated attestation still gates the review submission.
     */
    public List<byte[]> renderWatermarkedPageImages(
            byte[] pdfBytes, String viewerEmail, boolean watermark) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IOException("Empty PDF bytes; nothing to rasterize.");
        }
        // PDFBox 3.x: use Loader.loadPDF (the old PDDocument.load is
        // gone). The whole rasterisation happens inside a single
        // try-with-resources so the document closes even on partial
        // failure.
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();
            List<byte[]> pages = new ArrayList<>(pageCount);
            String stamp = watermark ? watermarkText(viewerEmail) : null;
            for (int i = 0; i < pageCount; i++) {
                BufferedImage page = renderer.renderImageWithDPI(i, 110f);
                BufferedImage out = watermark ? applyWatermark(page, stamp) : page;
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(out, "png", baos);
                pages.add(baos.toByteArray());
            }
            log.info("Rendered {} preview page(s) (watermark={}) for {}",
                    pageCount, watermark, viewerEmail);
            return pages;
        }
    }

    /** Build L — the per-viewer watermark string: CONFIDENTIAL • email • UTC. */
    private static String watermarkText(String viewerEmail) {
        String who = (viewerEmail == null || viewerEmail.isBlank())
                ? "consultant" : viewerEmail.trim();
        String ts = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
                .format(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return "CONFIDENTIAL  •  " + who + "  •  " + ts;
    }

    /**
     * Build L — bake a diagonal, tiled, semi-transparent watermark onto a
     * rendered page image (sage-navy @ 18% alpha, 30° tilt). Deterrent only.
     */
    private static BufferedImage applyWatermark(BufferedImage page, String text) {
        int w = page.getWidth();
        int h = page.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(page, 0, 0, null);
        g.setComposite(java.awt.AlphaComposite.getInstance(
                java.awt.AlphaComposite.SRC_OVER, 0.18f));
        g.setColor(new java.awt.Color(0x1B, 0x2A, 0x5C)); // sage-navy
        int fontSize = Math.max(18, Math.min(w, h) / 36);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, fontSize));
        java.awt.FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(text);
        int textH = fm.getHeight();
        g.rotate(Math.toRadians(-30), w / 2.0, h / 2.0);
        int margin = Math.max(w, h) / 2;
        int xStep = textW + fontSize * 4;
        int yStep = textH * 5;
        for (int y = -margin; y < h + margin; y += yStep) {
            for (int x = -margin; x < w + margin; x += xStep) {
                g.drawString(text, x, y);
            }
        }
        g.dispose();
        return out;
    }

    /**
     * Build G — applied after {@link #buildContext} to override or
     * blank specific signature slots for preview renders. The two
     * concrete subclasses live as factory methods below.
     */
    public interface ContextOverrides {
        void apply(Map<String, Object> ctx, AgreementDocumentService svc);
    }

    /** Consultant preview: primary signature from a data: URL; final + ERM blank. */
    public static ContextOverrides consultantPreviewOverrides(String primarySignatureDataUrl) {
        return (ctx, svc) -> {
            if (primarySignatureDataUrl != null && !primarySignatureDataUrl.isBlank()) {
                ctx.put("signatureImage", svc.buildImageFromDataUrl(primarySignatureDataUrl));
            }
            ctx.put("finalSignatureImage", "");
            ctx.put("ermSignatureImage", "");
        };
    }

    /** ERM preview: consultant signatures from Cloudinary (re-fetched); ERM blank. */
    public static ContextOverrides ermPreviewOverrides() {
        return (ctx, svc) -> ctx.put("ermSignatureImage", "");
    }

    /**
     * Stable, derivable public_id for an application's final PDF.
     * Always {@code agreements/<applicationId>}, matching the upload
     * call. Used to re-sign download URLs for rows persisted before
     * {@code final_pdf_public_id} was a column.
     */
    public static String derivePublicId(String applicationId) {
        return "agreements/" + applicationId;
    }

    /**
     * In-memory cache of the consultant-independent BLANK preview PDF
     * (the master template rendered with empty values + underscore
     * placeholders + no signatures). Built lazily on first request and
     * served from memory thereafter -- the bytes are identical for
     * every consultant, so generating it per-consultant would just
     * burn LibreOffice cycles.
     *
     * Volatile so the double-checked-locking-style write in
     * {@link #getBlankPreviewPdfBytes()} publishes safely under the
     * Spring container's worker threads.
     */
    private volatile byte[] blankPreviewPdfCache;

    /**
     * Returns the bytes of the blank-form preview PDF (the agreement
     * template rendered with empty values + underscore placeholders).
     * Cached after the first generation -- subsequent calls are an
     * in-memory return.
     *
     * Used by the consultant wizard's "View full agreement" reference:
     * the consultant reads the real document with their data NOT in it,
     * paired with the plain-language section summaries.
     */
    public byte[] getBlankPreviewPdfBytes() throws Exception {
        byte[] cached = blankPreviewPdfCache;
        if (cached != null) return cached;
        synchronized (this) {
            cached = blankPreviewPdfCache;
            if (cached != null) return cached;
            Path docx = null;
            Path pdf = null;
            try {
                Map<String, Object> ctx = buildBlankContext();
                docx = fillTemplate(ctx);
                pdf = convertToPdf(docx);
                cached = Files.readAllBytes(pdf);
                blankPreviewPdfCache = cached;
                log.info("Cached blank-form preview PDF ({} bytes) on first request",
                        cached.length);
                return cached;
            } finally {
                safeDelete(docx);
                safeDelete(pdf);
            }
        }
    }

    /**
     * Builds a Cloudinary signed URL for the given final-PDF public_id.
     *
     * The {@code Url.signed(true) + type("authenticated")} chain blocks
     * bare URL guessing -- requests without a valid {@code ?s=...}
     * signature get 401 from Cloudinary's edge.
     *
     * Note on the {@code ttl} parameter: the cloudinary-http44 v1.38.0
     * {@code Url} class does not expose an {@code expiresAt} method;
     * true time-limited URLs need an account-side Auth Token key
     * (enterprise Cloudinary feature). Until that's configured, the
     * signature is valid as long as the API secret is. The TTL is
     * accepted here so call sites can express intent ("5-minute
     * download link") and a future Auth Token upgrade is a single-
     * method-body change with no signature churn.
     */
    public String signedPdfUrl(String publicId, Duration ttl) {
        return signedPdfUrl(publicId, ttl, null);
    }

    /**
     * Overload that bakes a human-readable download filename into the
     * URL via Cloudinary's {@code fl_attachment:<filename>} flag.
     * Causes the edge to respond with {@code Content-Disposition:
     * attachment; filename="<filename>"} so the browser save dialog
     * suggests the readable name instead of the public_id's UUID tail.
     *
     * Pass {@code null} (or use the two-arg overload) when fetching
     * for server-side use (email attachment bytes) where the
     * Content-Disposition header is set on the email by the
     * MimeMessageHelper anyway.
     */
    public String signedPdfUrl(String publicId, Duration ttl, String downloadFilename) {
        if (publicId == null || publicId.isBlank()) return null;
        log.debug("Signing final PDF URL for {} with intended ttl {} download={}",
                publicId, ttl, downloadFilename);
        com.cloudinary.Url url = cloudinary.url()
                .resourceType("raw")
                .type("authenticated")
                .signed(true)
                .secure(true);
        if (downloadFilename != null && !downloadFilename.isBlank()) {
            // fl_attachment is signature-stable per filename: Cloudinary
            // computes the signature over the transformation string, so
            // the URL is valid for exactly this filename.
            url = url.transformation(
                    new com.cloudinary.Transformation<>()
                            .flags("attachment:" + downloadFilename));
        }
        return url.generate(publicId);
    }

    // ── Filename helpers ────────────────────────────────────────────

    /**
     * Slug rule (per the agreement-PDF naming spec):
     *
     *   * replace whitespace runs with a single hyphen
     *   * strip everything outside {@code [A-Za-z0-9_-]}
     *   * collapse repeated hyphens / underscores
     *   * trim leading/trailing separators
     *
     * Case is preserved -- the spec called out human-readable names
     * (e.g. "Maria-OBrien"), not the lowercased URL-slug convention.
     * Returns {@code ""} for null or blank input.
     */
    public static String slugify(String input) {
        if (input == null) return "";
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return "";
        String hyphenated = trimmed.replaceAll("\\s+", "-");
        String stripped = hyphenated.replaceAll("[^A-Za-z0-9_-]", "");
        String collapsed = stripped.replaceAll("[-_]+", "-");
        return collapsed.replaceAll("^[-_]+|[-_]+$", "");
    }

    /**
     * Composes the download / email-attachment filename for an
     * application's final PDF. Spec:
     *
     *   SageITCO-Agreement_{slug(name)}_{slug(track)}.pdf
     *
     * Fallback chain:
     *   name  -> signedLegalName, then consultantName, then applicationId
     *   track -> technologyTrack; omit the segment entirely when null /
     *            blank (so the filename ends with the name slot)
     *   if a slug comes out empty after stripping (e.g. "!!!"), fall
     *   back to applicationId for that slot
     *
     * Examples:
     *   "Abhi G" + "React"                -> SageITCO-Agreement_Abhi-G_React.pdf
     *   "Maria O'Brien" + "Java Full Stack" -> SageITCO-Agreement_Maria-OBrien_Java-Full-Stack.pdf
     *   "Abhi G" + null                   -> SageITCO-Agreement_Abhi-G.pdf
     *   null + null                       -> SageITCO-Agreement_{applicationId}.pdf
     */
    public static String buildPdfFilename(ConsultantApplication app) {
        String rawName = app.getSignedLegalName();
        if (rawName == null || rawName.isBlank()) rawName = app.getConsultantName();
        if (rawName == null || rawName.isBlank()) rawName = app.getApplicationId();
        String nameSlug = slugify(rawName);
        if (nameSlug.isEmpty()) nameSlug = slugify(app.getApplicationId());

        String trackSlug = slugify(app.getTechnologyTrack());

        String base = "SageITCO-Agreement_" + nameSlug;
        if (!trackSlug.isEmpty()) base = base + "_" + trackSlug;
        return base + ".pdf";
    }

    // ── Context build ───────────────────────────────────────────────

    /**
     * Phase B — resolve the ${ermEmail} placeholder from the owning ERM
     * ({@code owner_erm_id} → agreement_user). Falls back to the legacy
     * global env value when the owner can't be resolved (e.g. a legacy
     * row with no owner).
     */
    /** Build G — maps the ID-type code to the consultant-facing label. */
    static String idTypeLabel(String code) {
        if (code == null) return "";
        if ("DL".equals(code)) return "Driver's License";
        if ("STATE_ID".equals(code)) return "State ID";
        return "";
    }

    private String resolveOwnerEmail(ConsultantApplication app) {
        String ownerId = app.getOwnerErmId();
        if (ownerId != null) {
            var owner = agreementUserRepository.findById(ownerId);
            if (owner.isPresent()) {
                return owner.get().getEmail();
            }
        }
        return agreementErmEmail;
    }

    private Map<String, Object> buildContext(ConsultantApplication app) {
        Map<String, Object> c = new HashMap<>();
        Function<String, String> nz = s -> s == null ? "" : s;
        // Build G — every consultant-facing date renders MM-DD-YYYY.
        Function<LocalDate, String> fd = d -> d == null ? "" : d.format(US_SHORT_DATE_FMT);

        // Header. The template's ${participantFullLegalName} and
        // ${primaryEmail} placeholders don't have 1:1 entity getters
        // -- we map them to the closest existing fields. signedLegalName
        // is set by the consultant at sign time; before that, fall back
        // to the consultantName the ERM seeded the row with.
        // Build W — composed First + Middle? + Last; the consultant's
        // typed signing name (signedLegalName) still wins when present.
        String participantName = app.getSignedLegalName() != null
                && !app.getSignedLegalName().isBlank()
                ? app.getSignedLegalName()
                : composeFullName(app);
        c.put("effectiveDate", fd.apply(app.getEffectiveDate()));
        c.put("participantFullLegalName", nz.apply(participantName));
        c.put("primaryEmail", nz.apply(app.getConsultantEmail()));
        c.put("primaryPhone", nz.apply(app.getPrimaryPhone()));
        // Build W — render the custom value when the ERM picked "Others".
        c.put("workAuthorizationCategory", effectiveWorkAuth(app));
        // Build W — assemble the structured US billing address;
        // fall back to the legacy single-block column for old rows.
        c.put("residenceAddress", assembledAddress(app));

        // Rate card (Section 11 + Appendix 1).
        c.put("ratePeriod1", nz.apply(app.getRatePeriod1()));
        c.put("rateAmount1", nz.apply(app.getRateAmount1()));
        c.put("ratePeriod2", nz.apply(app.getRatePeriod2()));
        c.put("rateAmount2", nz.apply(app.getRateAmount2()));
        // Appendix 1 Schedule 1 — Phase 2 monthly-deliverables period (ERM-set,
        // single merged cell).
        c.put("phase2DeliverablePeriod", nz.apply(app.getPhase2DeliverablePeriod()));

        // Exhibit A.
        c.put("technologyTrack", nz.apply(app.getTechnologyTrack()));
        c.put("customScopeNotes", nz.apply(app.getCustomScopeNotes()));

        // Appendix 1 -- employment.
        c.put("employerPayrollEntity", nz.apply(app.getEmployerPayrollEntity()));
        c.put("implementationPartner", nz.apply(app.getImplementationPartner()));
        c.put("endClient", nz.apply(app.getEndClient()));
        c.put("roleTitle", nz.apply(app.getRoleTitle()));
        c.put("verifiedStartDate", fd.apply(app.getVerifiedStartDate()));
        c.put("payrollCycle", nz.apply(app.getPayrollCycle()));

        // Appendix 2 -- ACH (optional).
        c.put("achAccountType", nz.apply(app.getAchAccountType()));
        c.put("achBankName", nz.apply(app.getAchBankName()));
        c.put("achAccountHolderName", nz.apply(app.getAchAccountHolderName()));
        c.put("achRoutingNumber", nz.apply(app.getAchRoutingNumber()));
        c.put("achAccountNumber", nz.apply(app.getAchAccountNumber()));
        c.put("achNoticeEmail", nz.apply(app.getAchNoticeEmail()));
        c.put("achDebitDates", nz.apply(app.getAchDebitDates()));
        c.put("achDebitAmounts", nz.apply(app.getAchDebitAmounts()));

        // Appendix 3 -- background check (sensitive PII).
        c.put("bgFullLegalName", nz.apply(app.getBgFullLegalName()));
        c.put("bgOtherNamesUsed", nz.apply(app.getBgOtherNamesUsed()));
        // Build J — assembled from the structured current-address columns
        // (legacy single block as fallback).
        c.put("bgCurrentAddress", assembledCurrentAddress(app));
        c.put("bgDateOfBirth", fd.apply(app.getBgDateOfBirth()));
        c.put("bgFullSsn", nz.apply(app.getBgFullSsn()));
        // Build G — Appendix 3 renders "Driver's License: 12345" or
        // "State ID: 12345" via $bgDriverLicense (the existing
        // placeholder; we now prefix with the chosen idType label).
        // Pure $idType is also exposed so the template can drop the
        // label separately if it wants.
        String idTypeLabel = idTypeLabel(app.getIdType());
        c.put("idType", idTypeLabel);
        String dl = nz.apply(app.getBgDriverLicense());
        c.put("bgDriverLicense",
                idTypeLabel.isEmpty() || dl.isEmpty()
                        ? dl
                        : idTypeLabel + ": " + dl);

        // Appendix 4 -- portal access (optional).
        // Build J — flatten the repeatable platform+username entries into the
        // existing placeholders (position-aligned, comma-joined).
        c.put("portalPlatform", derivePortalField(app, "platform", app.getPortalPlatform()));
        c.put("portalUsername", derivePortalField(app, "username", app.getPortalUsername()));
        c.put("portalAuthorizedActions", nz.apply(app.getPortalAuthorizedActions()));
        c.put("portalEffectiveDate", fd.apply(app.getPortalEffectiveDate()));
        c.put("portalRevocationContact", nz.apply(app.getPortalRevocationContact()));

        // Appendix 5 -- security cheque(s) (optional).
        c.put("securityCheckCount", nz.apply(app.getSecurityCheckCount()));
        // Build U — derive numbers + dates from the cheques JSON list
        // (one row per cheque) so the template's existing placeholders
        // stay populated. Falls back to the legacy single-text columns
        // when the JSON is empty (pre-Build-U rows).
        c.put("securityCheckNumbers", deriveChequeField(app, "number",
                app.getSecurityCheckNumbers()));
        c.put("securityCheckBank", nz.apply(app.getSecurityCheckBank()));
        c.put("securityCheckHolderName", nz.apply(app.getSecurityCheckHolderName()));
        c.put("securityCheckAmount", nz.apply(app.getSecurityCheckAmount()));
        // Build W — the cheque DATE was removed from the form; render it
        // empty so no stale per-cheque date appears in the PDF.
        c.put("securityCheckDates", "");

        // ERM signature block. ermName/ermTitle come from the Approve &
        // Sign input (the signer confirms them); ermEmail is resolved
        // from the OWNING ERM (Phase B), falling back to the global env.
        c.put("ermName", nz.apply(app.getErmName()));
        c.put("ermTitle", nz.apply(app.getErmTitle()));
        c.put("ermEmail", resolveOwnerEmail(app));
        // Build W — the ERM "Date:" line. Blank until the ERM actually
        // countersigns (no today-fallback), so nothing renders under the
        // unsigned ERM signature while the consultant signs/views.
        c.put("ermSignatureDate", resolveErmSignatureDate(app));
        // Build Q — the consultant signs at the review step; the
        // preview renders BEFORE submit, so the persisted
        // signatureDate is still null. Fall back to today's date so
        // "Date / Email: ${signatureDate} / ${primaryEmail}" never
        // shows the email without a date. Once the consultant
        // submits, signatureDate is persisted (see
        // ConsultantApplicationService.consultantSubmit) and this
        // fallback stops firing.
        c.put("signatureDate", resolveSignatureDate(app));

        // Build R — final signing IP, rendered as a faint trace line
        // ("IP: <ip> · Signed: <utc>") under the Date / Email line in
        // each consultant signature block. The IP + timestamp are
        // captured TOGETHER at consultantSubmit (overwritten on every
        // re-sign so a revision-resubmit updates both), so they
        // always correspond to the same signing event. The template
        // surgery (keepSignatureBlocksTogether / insertSigningIpLine)
        // injects the gray 7pt paragraph; here we just provide the
        // values. Pre-submit previews show "(pending)" since neither
        // is recorded until the consultant actually clicks submit.
        c.put("finalSigningIp", resolveFinalSigningIp(app));
        c.put("finalSigningAt", resolveFinalSigningAt(app));

        // Image placeholders -- separate entity fields. Each renders as
        // blank when the corresponding signature hasn't been captured.
        // F-4 first-and-last model: signatureImage is drawn on the
        // main-agreement step; finalSignatureImage is drawn on the
        // review step and stamps the closing/execution block.
        // Phase 5 — dual-read signatures: S3 bytes when the *_s3_key is set,
        // else the legacy Cloudinary URL. Blank box on failure (render-safe).
        Object consultantSig = signatureImage(app.getSignatureS3Key(), app.getSignatureImage());
        Object ermSig = signatureImage(app.getErmSignatureS3Key(), app.getErmSignatureUrl());
        c.put("signatureImage", consultantSig);
        c.put("finalSignatureImage", signatureImage(app.getFinalSignatureS3Key(), app.getFinalSignatureImage()));
        c.put("ermSignatureImage", ermSig);

        // Build AF — per-appendix signature gating. The Appendix 1-5
        // authorization blocks used to stamp the GLOBAL consultant + ERM
        // signature and a today-fallback date into EVERY block, so an
        // appendix the ERM never sent (and the consultant never filled)
        // still rendered as "signed" with empty fields. Each appendix block
        // now carries its own ${appendixNSignature} / ${appendixNErmSignature}
        // / ${appendixNSignatureDate} / ${appendixNErmSignatureDate} /
        // ${appendixNEmail} placeholders, populated ONLY when the consultant
        // affirmed that appendix (i.e. it was sent AND filled). When the
        // appendix is inactive every slot is blank (""), so the whole block
        // renders unsigned — no signature image, no date, no email line.
        String consultantSigDate = resolveSignatureDate(app);
        String ermSigDate = resolveErmSignatureDate(app);
        String consultantEmail = nz.apply(app.getConsultantEmail());
        boolean[] appendixAffirmed = {
                Boolean.TRUE.equals(app.getAffirmedAppendix1()),
                Boolean.TRUE.equals(app.getAffirmedAppendix2()),
                Boolean.TRUE.equals(app.getAffirmedAppendix3()),
                Boolean.TRUE.equals(app.getAffirmedAppendix4()),
                Boolean.TRUE.equals(app.getAffirmedAppendix5()),
        };
        for (int i = 0; i < appendixAffirmed.length; i++) {
            int n = i + 1;
            boolean on = appendixAffirmed[i];
            c.put("appendix" + n + "Signature", on ? consultantSig : "");
            c.put("appendix" + n + "ErmSignature", on ? ermSig : "");
            c.put("appendix" + n + "SignatureDate", on ? consultantSigDate : "");
            c.put("appendix" + n + "ErmSignatureDate", on ? ermSigDate : "");
            c.put("appendix" + n + "Email", on ? consultantEmail : "");
        }

        return c;
    }

    /**
     * F-3 — the NON-editable placeholder values for inline clause display
     * in the consultant wizard. Same value source + formatting as
     * {@link #buildContext}, so the on-screen clauses match the signed
     * PDF exactly. Consultant-editable placeholders are intentionally
     * omitted (the frontend fills them live from form state); the two
     * signature images are handled by the frontend (drawn signature /
     * "Sage IT").
     */
    public Map<String, String> nonEditableDisplayValues(ConsultantApplication app) {
        Map<String, String> v = new HashMap<>();
        Function<String, String> nz = s -> s == null ? "" : s;
        v.put("effectiveDate", app.getEffectiveDate() == null
                ? "" : app.getEffectiveDate().format(US_SHORT_DATE_FMT));
        // Build W — work authorization is ERM-set; expose the EFFECTIVE
        // value (the custom free-text when "Others") so the inline clause
        // view shows the real status, not the literal word "Others".
        v.put("workAuthorizationCategory", effectiveWorkAuth(app));
        v.put("ratePeriod1", nz.apply(app.getRatePeriod1()));
        v.put("rateAmount1", nz.apply(app.getRateAmount1()));
        v.put("ratePeriod2", nz.apply(app.getRatePeriod2()));
        v.put("rateAmount2", nz.apply(app.getRateAmount2()));
        v.put("phase2DeliverablePeriod", nz.apply(app.getPhase2DeliverablePeriod()));
        v.put("ermName", nz.apply(app.getErmName()));
        v.put("ermTitle", nz.apply(app.getErmTitle()));
        v.put("ermEmail", resolveOwnerEmail(app));
        // Build Q — same today-fallback as buildContext so the
        // inline-clause read view in the wizard shows the date in
        // every signature block before submit.
        v.put("signatureDate", resolveSignatureDate(app));
        // Build W — ERM "Date:" line. Blank until the ERM countersigns,
        // so the consultant's inline read view shows no ERM date.
        v.put("ermSignatureDate", resolveErmSignatureDate(app));
        // Build R — same final-signing IP + timestamp fallback as
        // buildContext; both captured at the same review-step submit.
        v.put("finalSigningIp", resolveFinalSigningIp(app));
        v.put("finalSigningAt", resolveFinalSigningAt(app));
        return v;
    }

    /**
     * Build Q — single source of truth for the consultant's
     * signature date in every render path (final PDF, preview-images,
     * inline-clause view). Returns the persisted {@code signatureDate}
     * formatted MM-DD-YYYY when set, else today's date as a preview
     * fallback. Empty only when there is no consultant signature on
     * file AND no fallback context (i.e. a blank-template render).
     */
    private static String resolveSignatureDate(ConsultantApplication app) {
        LocalDateTime stamped = app.getSignatureDate();
        if (stamped != null) {
            return stamped.toLocalDate().format(US_SHORT_DATE_FMT);
        }
        return LocalDate.now().format(US_SHORT_DATE_FMT);
    }

    /**
     * Build W — the ERM countersign date for the ERM "Date:" line.
     * Unlike the consultant's date, there is NO today-fallback: an empty
     * string renders until the ERM actually signs, so no date appears
     * under the unsigned ERM signature while the consultant signs/views.
     */
    private static String resolveErmSignatureDate(ConsultantApplication app) {
        LocalDateTime stamped = app.getErmSignatureDate();
        if (stamped != null) {
            return stamped.toLocalDate().format(US_SHORT_DATE_FMT);
        }
        return "";
    }

    /**
     * Build W — compose the full legal name from the structured
     * First + Middle? + Last columns, falling back to the legacy single
     * {@code consultantName} for rows captured before the split.
     */
    static String composeFullName(ConsultantApplication app) {
        String first = trimToEmpty(app.getFirstName());
        String middle = trimToEmpty(app.getMiddleName());
        String last = trimToEmpty(app.getLastName());
        if (first.isEmpty() && last.isEmpty()) {
            return trimToEmpty(app.getConsultantName());
        }
        StringBuilder sb = new StringBuilder(first);
        if (!middle.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(middle);
        }
        if (!last.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(last);
        }
        return sb.toString().trim();
    }

    /**
     * Build W — the work-authorization value to render: the ERM's custom
     * free-text when the category is "Others", otherwise the category
     * itself. Empty when nothing is set.
     */
    static String effectiveWorkAuth(ConsultantApplication app) {
        String cat = trimToEmpty(app.getWorkAuthorizationCategory());
        if ("Others".equalsIgnoreCase(cat)) {
            String other = trimToEmpty(app.getWorkAuthorizationOther());
            return other.isEmpty() ? cat : other;
        }
        return cat;
    }

    /**
     * Build W — assemble a US-format billing address from the structured
     * columns ("Line1[, Line2], City, ST ZIP"), falling back to the
     * legacy single-block {@code residenceAddress} for old rows.
     */
    static String assembledAddress(ConsultantApplication app) {
        return assembleUsAddress(
                app.getAddressLine1(), app.getAddressLine2(), app.getAddressCity(),
                app.getAddressState(), app.getAddressZip(), app.getResidenceAddress());
    }

    /**
     * Build W/J — assemble a US-format address ("Line1[, Line2], City, ST
     * ZIP") from structured parts, falling back to a legacy single-block
     * value when no structured part is present. Shared by the residence
     * address and the Background Check current address.
     */
    private static String assembleUsAddress(
            String l1, String l2, String c, String st, String z, String legacy) {
        String line1 = trimToEmpty(l1);
        String line2 = trimToEmpty(l2);
        String city = trimToEmpty(c);
        String state = trimToEmpty(st);
        String zip = trimToEmpty(z);
        boolean anyStructured = !(line1.isEmpty() && line2.isEmpty()
                && city.isEmpty() && state.isEmpty() && zip.isEmpty());
        if (!anyStructured) {
            return trimToEmpty(legacy);
        }
        StringBuilder sb = new StringBuilder();
        if (!line1.isEmpty()) sb.append(line1);
        if (!line2.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(line2);
        }
        // City, ST ZIP — comma before city, space-separated state + zip.
        String cityStateZip = city;
        if (!state.isEmpty()) {
            cityStateZip = cityStateZip.isEmpty() ? state : cityStateZip + ", " + state;
        }
        if (!zip.isEmpty()) {
            cityStateZip = cityStateZip.isEmpty() ? zip : cityStateZip + " " + zip;
        }
        if (!cityStateZip.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(cityStateZip);
        }
        return sb.toString().trim();
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Build R — final signing IP for the faint trace line under each
     * consultant signature block. Captured at the review-step submit
     * and overwritten on every re-sign (revision resubmit), so the
     * value always reflects the most recent execution event. Empty
     * on a pre-submit preview render -- callers should treat empty
     * as "(pending)" in the rendered line.
     */
    /**
     * Build U — join the per-cheque field ("number" or "date") from the
     * cheques JSON column into a comma-separated string for the
     * template's existing {@code ${securityCheckNumbers}} /
     * {@code ${securityCheckDates}} placeholders. Falls back to the
     * legacy single-text column for pre-Build-U rows.
     */
    private static String deriveChequeField(ConsultantApplication app, String key, String legacy) {
        String json = app.getCheques();
        if (json != null && !json.isBlank()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper m =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode arr = m.readTree(json);
                if (arr.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                        String v = n.path(key).asText("");
                        if (v.isBlank()) continue;
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(v);
                    }
                    if (sb.length() > 0) return sb.toString();
                }
            } catch (Exception ignored) {
                /* fall through to legacy */
            }
        }
        return legacy == null ? "" : legacy;
    }

    /**
     * Build J — join a per-entry field ("platform" or "username") from the
     * portal_entries JSON into a comma-separated string for the existing
     * {@code ${portalPlatform}} / {@code ${portalUsername}} placeholders.
     * Position-aligned with the sibling field (same row order). Falls back
     * to the legacy single value for old rows.
     */
    private static String derivePortalField(ConsultantApplication app, String key, String legacy) {
        String json = app.getPortalEntries();
        if (json != null && !json.isBlank()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper m =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode arr = m.readTree(json);
                if (arr.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                        String platform = n.path("platform").asText("").trim();
                        String username = n.path("username").asText("").trim();
                        // Only render rows that have BOTH, so platform and
                        // username lists stay index-aligned.
                        if (platform.isEmpty() || username.isEmpty()) continue;
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(n.path(key).asText("").trim());
                    }
                    if (sb.length() > 0) return sb.toString();
                }
            } catch (Exception ignored) {
                /* fall through to legacy */
            }
        }
        return legacy == null ? "" : legacy;
    }

    /**
     * Build J — assemble the Background Check current address from its
     * structured columns, falling back to the legacy single-block
     * {@code bg_current_address} for old rows.
     */
    static String assembledCurrentAddress(ConsultantApplication app) {
        // Build J — "Same as residence" is authoritative: the current address
        // IS the residence address (no reliance on the copied snapshot, which
        // could go stale if the residence was edited after the toggle).
        if (Boolean.TRUE.equals(app.getBgCurrentSameAsResidence())) {
            return assembledAddress(app);
        }
        return assembleUsAddress(
                app.getBgCurrentAddressLine1(), app.getBgCurrentAddressLine2(),
                app.getBgCurrentAddressCity(), app.getBgCurrentAddressState(),
                app.getBgCurrentAddressZip(), app.getBgCurrentAddress());
    }

    private static String resolveFinalSigningIp(ConsultantApplication app) {
        String ip = app.getFinalSigningIp();
        if (ip != null && !ip.isBlank()) return ip.trim();
        return "(pending)";
    }

    /** UTC formatter used by the faint signing-timestamp stamp.
     *  Matches the Certificate of Completion's timestamp shape so the
     *  in-document IP/time stamp and the certificate stay consistent. */
    private static final DateTimeFormatter SIGNING_AT_UTC_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    /**
     * Build R — final signing timestamp paired with the captured IP
     * in the faint trace line. Sourced from {@code finalSignedAt},
     * which is set at the same instant as {@code finalSigningIp} in
     * {@code consultantSubmit} (and overwritten together on re-sign),
     * so the displayed IP and time always correspond to the same
     * signing event. Formatted UTC for audit precision and
     * consistency with the Certificate of Completion. "(pending)" on
     * pre-submit previews — mirrors the IP fallback.
     */
    private static String resolveFinalSigningAt(ConsultantApplication app) {
        LocalDateTime stamped = app.getFinalSignedAt();
        if (stamped != null) {
            return stamped.atZone(java.time.ZoneOffset.UTC)
                    .format(SIGNING_AT_UTC_FMT);
        }
        return "(pending)";
    }

    /**
     * Builds the same placeholder map as {@link #buildContext} but
     * with underscore-line placeholders ("________________") for every
     * text field and empty strings for signature images. The result is
     * consultant-INDEPENDENT (constant) so the rendered PDF can be
     * cached and reused across every consultant request.
     *
     * Keys list must stay in sync with {@link #buildContext}: any
     * placeholder added there should be added here too so the master
     * template doesn't show a literal "${var}" in the preview.
     */
    private Map<String, Object> buildBlankContext() {
        Map<String, Object> c = new HashMap<>();
        // 30 underscores -- visually obvious "fill here" guide; safe
        // width inside narrow cells (the template has both wide and
        // narrow blanks; this is a compromise that reads well on both).
        final String LINE = "______________________________";
        String[] textKeys = {
                // Header / cover
                "participantFullLegalName", "primaryEmail", "primaryPhone",
                "workAuthorizationCategory", "residenceAddress",
                "effectiveDate",
                // Rate card
                "ratePeriod1", "rateAmount1", "ratePeriod2", "rateAmount2",
                // Exhibit A
                "technologyTrack", "customScopeNotes",
                // Appendix 1 -- employment
                "employerPayrollEntity", "implementationPartner", "endClient",
                "roleTitle", "verifiedStartDate", "payrollCycle",
                // Appendix 2 -- ACH
                "achAccountType", "achBankName", "achAccountHolderName",
                "achRoutingNumber", "achAccountNumber", "achNoticeEmail",
                "achDebitDates", "achDebitAmounts",
                // Appendix 3 -- background check
                "bgFullLegalName", "bgOtherNamesUsed", "bgCurrentAddress",
                "bgDateOfBirth", "bgFullSsn", "bgDriverLicense", "idType",
                // Appendix 4 -- portal
                "portalPlatform", "portalUsername", "portalAuthorizedActions",
                "portalEffectiveDate", "portalRevocationContact",
                // Appendix 5 -- security check
                "securityCheckCount", "securityCheckNumbers", "securityCheckBank",
                "securityCheckHolderName", "securityCheckAmount", "securityCheckDates",
                // ERM signature block (text -- the images themselves are
                // signatureImage / ermSignatureImage below).
                "ermName", "ermTitle", "ermEmail", "signatureDate",
                // Build W — ERM "Date:" line (own placeholder).
                "ermSignatureDate",
                // Build R — faint IP + timestamp trace line under the
                // Date/Email line. Both render in the same gray 7pt
                // paragraph; both are blank on the master template.
                "finalSigningIp", "finalSigningAt",
        };
        for (String k : textKeys) c.put(k, LINE);
        // Signature images render as empty boxes in the preview.
        c.put("signatureImage", "");
        c.put("finalSignatureImage", "");
        c.put("ermSignatureImage", "");
        // Build AF — per-appendix signature slots (Appendix 1-5). Empty image
        // boxes + underscore date / email lines, mirroring the global blanks
        // above so the master reference form looks identical to before.
        for (int n = 1; n <= 5; n++) {
            c.put("appendix" + n + "Signature", "");
            c.put("appendix" + n + "ErmSignature", "");
            c.put("appendix" + n + "SignatureDate", LINE);
            c.put("appendix" + n + "ErmSignatureDate", LINE);
            c.put("appendix" + n + "Email", LINE);
        }
        return c;
    }

    // Signature bounding box + density. Word/LibreOffice display size =
    // pixelWidth / horizontalDPI, so a pixel-only resize (the prior bug)
    // left physical size at the mercy of the source's DPI. We now control
    // BOTH: fit inside a 190x76 px box AND force a 96-DPI pHYs chunk, so
    // the render is deterministic: 190px/96dpi = 5.03cm wide,
    // 76px/96dpi = 2.01cm tall -- the professional contract-signature target.
    private static final int SIGNATURE_BOX_WIDTH_PX = 190;
    private static final int SIGNATURE_BOX_HEIGHT_PX = 76;
    /** 96 DPI expressed as PNG pHYs pixels-per-meter: 96 / 0.0254 ≈ 3780. */
    private static final int SIGNATURE_PHYS_PPM = 3780;

    /**
     * Returns an {@link Image} object for docx-stamper to embed, or an
     * empty string when the URL is missing / unreachable so the
     * placeholder renders blank instead of crashing the run.
     *
     * Downloads with explicit timeouts (Cloudinary egress can hang on
     * a default URLConnection), then re-scales to a sensible signature
     * footprint before handing off to docx-stamper. Pre-resizing the
     * byte[] in Java is more reliable than relying on the
     * {@code Image(bytes, maxWidth)} overload because the unit
     * interpretation (EMU vs pixel vs DXA) varies between stamper
     * versions.
     */
    private Object buildImage(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            byte[] bytes;
            URLConnection conn = new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            try (InputStream in = conn.getInputStream()) {
                bytes = in.readAllBytes();
            }
            byte[] normalized = normalizeSignaturePng(bytes);
            return new Image(normalized);
        } catch (Exception e) {
            log.warn("Failed loading signature image from {}: {}",
                    url, e.getMessage());
            return "";
        }
    }

    /**
     * Phase 5 — dual-read a signature image: the S3 object when {@code s3Key}
     * is set, else the legacy Cloudinary URL ({@link #buildImage}). Returns ""
     * (blank box) on any failure so a render never breaks on a signature
     * hiccup — but, unlike the silent S3 path the survey flagged, it logs.
     */
    private Object signatureImage(String s3Key, String cloudinaryUrl) {
        if (s3Key != null && !s3Key.isBlank()) {
            try {
                return buildImageBytes(documentStorage.get(s3Key));
            } catch (Exception e) {
                log.warn("Phase 5 — failed loading signature from S3 key {}: {}",
                        s3Key, e.getMessage());
                return "";
            }
        }
        return buildImage(cloudinaryUrl);
    }

    /** Build G — normalise raw signature bytes into a sized PDF image (S3 read path). */
    private Object buildImageBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        try {
            return new Image(normalizeSignaturePng(bytes));
        } catch (Exception e) {
            log.warn("Failed normalising signature image bytes: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Build G — decodes a {@code data:image/...;base64,...} URL and
     * normalises it through the same sizing pipeline as
     * {@link #buildImage}. Returns "" when the URL is malformed so
     * the preview render still completes (blank box instead of a
     * stamper failure).
     *
     * Public so the package-private
     * {@link #consultantPreviewOverrides} lambda can call into it.
     */
    Object buildImageFromDataUrl(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) return "";
        int comma = dataUrl.indexOf(',');
        if (comma < 0 || !dataUrl.startsWith("data:image/")) {
            log.warn("Build G — discarded malformed data URL for preview signature");
            return "";
        }
        try {
            byte[] bytes = java.util.Base64.getDecoder()
                    .decode(dataUrl.substring(comma + 1));
            byte[] normalized = normalizeSignaturePng(bytes);
            return new Image(normalized);
        } catch (Exception e) {
            log.warn("Build G — failed to decode preview signature data URL: {}",
                    e.getMessage());
            return "";
        }
    }

    /**
     * Deterministic signature sizing. Fits {@code input} inside a
     * {@link #SIGNATURE_BOX_WIDTH_PX} x {@link #SIGNATURE_BOX_HEIGHT_PX}
     * box (aspect preserved, downscale only) AND re-encodes the PNG with
     * an explicit 96-DPI {@code pHYs} density chunk. Controlling both the
     * pixel box and the density is what makes Word/LibreOffice render the
     * signature at the intended physical size (~5cm x ~2cm max) -- a
     * pixel-only resize left the source's DPI intact, so the physical
     * size stayed large.
     *
     * Bilinear interpolation + ARGB buffer keep strokes smooth and
     * transparent. On any decode/encode failure the original bytes are
     * returned so PDF generation never breaks.
     */
    private static byte[] normalizeSignaturePng(byte[] input) {
        BufferedImage original = null;
        try {
            original = ImageIO.read(new ByteArrayInputStream(input));
        } catch (Exception decodeErr) {
            log.warn("Signature normalize: decode failed ({}); falling back to blank",
                    decodeErr.getMessage());
        }
        if (original == null) {
            // Build I — DO NOT return raw bytes. Returning the raw
            // bytes is what previously let a high-resolution sig sneak
            // through and overflow its row, cascading into a blank
            // page. A clean 1x1 transparent PNG produces a zero-area
            // image -- the layout stays identical to "no signature".
            log.warn("Signature normalize: undecodable bytes ({}B), substituting blank",
                    input.length);
            return blankSignaturePng();
        }
        int srcW = original.getWidth();
        int srcH = original.getHeight();

        // Fit inside the box, preserve aspect, never upscale.
        double scale = Math.min(
                Math.min((double) SIGNATURE_BOX_WIDTH_PX / srcW,
                         (double) SIGNATURE_BOX_HEIGHT_PX / srcH),
                1.0);
        int targetW = Math.max(1, (int) Math.round(srcW * scale));
        int targetH = Math.max(1, (int) Math.round(srcH * scale));

        BufferedImage resized = new BufferedImage(
                targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(original, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }

        try {
            byte[] out = encodePngWithDensity(resized);
            log.info("Signature normalized: in {}x{} -> out {}x{} px, density=96dpi, bytes {}->{}",
                    srcW, srcH, targetW, targetH, input.length, out.length);
            return out;
        } catch (Exception encodeErr) {
            // Build I — if the metadata-merge encode path fails (rare,
            // but it has happened on Java 17 + certain ImageIO
            // configurations), encode WITHOUT the pHYs chunk. The
            // resulting image is still resized to the fixed box; the
            // density defaults to 72 DPI which renders ~7×2.7 cm
            // instead of ~5×2 cm -- larger than ideal, but BOUNDED, so
            // the layout doesn't catastrophically overflow.
            log.warn("Signature normalize: pHYs encode failed ({}); falling back to bare PNG at {}x{}",
                    encodeErr.getMessage(), targetW, targetH);
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(resized, "png", baos);
                return baos.toByteArray();
            } catch (Exception finalErr) {
                log.error("Signature normalize: bare PNG encode also failed ({}); substituting blank",
                        finalErr.getMessage());
                return blankSignaturePng();
            }
        }
    }

    /** 1x1 transparent PNG bytes. Cached because the cost is the same every call. */
    private static volatile byte[] BLANK_SIGNATURE_PNG;
    private static byte[] blankSignaturePng() {
        byte[] cached = BLANK_SIGNATURE_PNG;
        if (cached != null) return cached;
        BufferedImage blank = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(blank, "png", baos);
            cached = baos.toByteArray();
            BLANK_SIGNATURE_PNG = cached;
            return cached;
        } catch (IOException e) {
            // Shouldn't happen with TYPE_INT_ARGB + bundled PNG writer.
            // If it does, hand back zero bytes -- docx-stamper treats
            // those as an empty placeholder.
            log.error("Blank signature PNG encode failed: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
     * Encodes {@code image} as a PNG carrying an explicit
     * {@link #SIGNATURE_PHYS_PPM}-pixels-per-meter (96 DPI) {@code pHYs}
     * density chunk via the PNG native metadata format. This is the piece
     * a plain {@code ImageIO.write(img, "png", out)} omits.
     */
    private static byte[] encodePngWithDensity(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            ImageTypeSpecifier type = ImageTypeSpecifier
                    .createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB);
            IIOMetadata metadata = writer.getDefaultImageMetadata(type, param);

            String nativeFormat = "javax_imageio_png_1.0";
            IIOMetadataNode pHYs = new IIOMetadataNode("pHYs");
            pHYs.setAttribute("pixelsPerUnitXAxis", String.valueOf(SIGNATURE_PHYS_PPM));
            pHYs.setAttribute("pixelsPerUnitYAxis", String.valueOf(SIGNATURE_PHYS_PPM));
            pHYs.setAttribute("unitSpecifier", "meter");

            IIOMetadataNode root = new IIOMetadataNode(nativeFormat);
            root.appendChild(pHYs);
            metadata.mergeTree(nativeFormat, root);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(metadata, new IIOImage(image, null, metadata), param);
            }
            return baos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    // ── Template fill ───────────────────────────────────────────────

    /**
     * Cached preprocessed template bytes. Built once on first render
     * (cheap surgery: ~450 KB string replace) and reused for every
     * subsequent render -- the surgery output is consultant-
     * independent, so caching is safe.
     *
     * volatile so the lazy-init double-check publishes safely under
     * the Spring container's worker threads.
     */
    private volatile byte[] preprocessedTemplateBytes;

    /**
     * Build I — strips structural artifacts that cause LibreOffice to
     * emit blank intermediate pages around signature blocks. Each
     * appendix boundary in the source template has the shape:
     *
     *     <w:tbl>...signature table...</w:tbl>
     *     <w:p/>                                  <-- orphan empty paragraph
     *     <w:p><w:r><w:br w:type="page"/></w:r></w:p>
     *     <w:p>(next appendix heading)</w:p>
     *
     * When the signature table happens to land at page bottom, the
     * orphan paragraph spills onto the next page; the explicit page
     * break then forces ANOTHER new page, leaving the page in between
     * blank. Removing the four orphan paragraphs (one per appendix
     * boundary that follows a signature block) collapses the cascade
     * so the appendix heading lands immediately on the next page.
     *
     * Done in-memory: the template lives on the classpath as a
     * read-only resource, so we read it once, edit the
     * {@code word/document.xml} entry, and repackage the ZIP into a
     * byte[] cache.
     */
    private byte[] preprocessedTemplate() throws IOException {
        byte[] cached = preprocessedTemplateBytes;
        if (cached != null) return cached;
        synchronized (this) {
            cached = preprocessedTemplateBytes;
            if (cached != null) return cached;
            try (InputStream in = templateResource.getInputStream()) {
                cached = applyTemplateSurgery(in.readAllBytes());
            }
            preprocessedTemplateBytes = cached;
            log.info("Preprocessed agreement template cached ({} KB)",
                    cached.length / 1024);
            return cached;
        }
    }

    /** Visible for direct testing; performs the ZIP-internal XML rewrite. */
    static byte[] applyTemplateSurgery(byte[] templateBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(templateBytes.length);
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(new ByteArrayInputStream(templateBytes));
             java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zin.getNextEntry()) != null) {
                // Preserve compression mode + extra fields on each entry
                // so the resulting .docx is still a valid OPC package.
                java.util.zip.ZipEntry copy = new java.util.zip.ZipEntry(entry.getName());
                zout.putNextEntry(copy);
                if ("word/document.xml".equals(entry.getName())) {
                    ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
                    int n;
                    while ((n = zin.read(buf)) > 0) entryBytes.write(buf, 0, n);
                    String xml = entryBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
                    // Build I — strip orphan empty paragraphs around
                    // page breaks (fixes the blank page 22 cascade).
                    xml = stripOrphanEmptyParagraphsBeforePageBreaks(xml);
                    // Build J — push the body bottom margin clear of
                    // the letterhead background image so text never
                    // overlaps the address block.
                    xml = enlargeBottomMargin(xml);
                    // Build R — insert a faint IP trace paragraph
                    // ("IP: ${finalSigningIp}", gray 7pt) immediately
                    // after every "Date / Email:" paragraph in the
                    // consultant signature blocks. Must run BEFORE
                    // keepSignatureBlocksTogether so the new
                    // paragraph rides into the consolidated single-
                    // row cell with the rest of the block.
                    xml = insertSigningIpLine(xml);
                    // Build J — keep every signature block (label →
                    // signature image → name/date) on a single page
                    // by marking its rows non-splittable.
                    xml = keepSignatureBlocksTogether(xml);
                    zout.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    int n;
                    while ((n = zin.read(buf)) > 0) zout.write(buf, 0, n);
                }
                zout.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    /**
     * Build I — string-level XML surgery. Removes exactly the orphan
     * empty paragraph in the pattern
     *
     *   {@code </w:tbl><w:p .../><w:p ...><w:r><w:br w:type="page"/></w:r></w:p>}
     *
     * The match is intentionally narrow: self-closed empty paragraph
     * (no inner runs) between a table end and a page-break paragraph.
     * Anything more substantial is left alone so legitimate spacing
     * between sections isn't disturbed.
     */
    /**
     * Build J — body margin sized to clear the letterhead. The
     * source template defines US-Letter pages with the bottom margin
     * at 1008 twips (0.7"). The letterhead is a full-page background
     * image anchored {@code behindDoc=1} in {@code word/header1.xml};
     * its visible "footer" art (logo + address) sits in the bottom
     * ~1.5" of every page. With only a 0.7" body margin, paragraphs
     * print on top of the letterhead. Bumping the bottom margin to
     * 2160 twips (1.5") gives the body ~0.8" of clearance over the
     * footer art on every page.
     */
    static final int FOOTER_SAFE_BOTTOM_MARGIN_TWIPS = 2160;

    static String enlargeBottomMargin(String xml) {
        // Match every <w:pgMar ...> declaration so each section's
        // margins are updated together. The bottom attr is rewritten
        // in place; everything else is preserved verbatim.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(<w:pgMar\\b[^>]*?\\bw:bottom=\")(\\d+)(\")",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(xml);
        StringBuilder sb = new StringBuilder(xml.length());
        int touched = 0;
        int lastEnd = 0;
        int largest = 0;
        while (m.find()) {
            sb.append(xml, lastEnd, m.start());
            int currentBottom = Integer.parseInt(m.group(2));
            largest = Math.max(largest, currentBottom);
            int next = Math.max(currentBottom, FOOTER_SAFE_BOTTOM_MARGIN_TWIPS);
            sb.append(m.group(1));
            sb.append(next);
            sb.append(m.group(3));
            lastEnd = m.end();
            touched++;
        }
        sb.append(xml, lastEnd, xml.length());
        if (touched > 0) {
            log.info("Template surgery: bottom margin raised on {} section(s) "
                    + "(was {}+ twips, now {} twips)",
                    touched, largest, FOOTER_SAFE_BOTTOM_MARGIN_TWIPS);
        }
        return sb.toString();
    }

    /**
     * Build R — inserts a faint IP + signing-timestamp trace paragraph
     * immediately after every
     * {@code "Date / Email: ${signatureDate} / ${primaryEmail}"}
     * paragraph in the document. Renders as gray 7pt
     * ({@code w:sz="14"}, {@code w:color="808080"}) so it reads as
     * roughly 50% opacity on white — present for security and
     * traceability but unobtrusive next to the main signature lines.
     *
     * docx-stamper substitutes {@code ${finalSigningIp}} and
     * {@code ${finalSigningAt}} from the context map built by
     * {@link #buildContext}; both are captured TOGETHER at the
     * review-step submit and overwritten together on every re-sign,
     * so the rendered IP and timestamp always correspond to the same
     * signing event. {@code finalSigningAt} is UTC for consistency
     * with the Certificate of Completion timeline.
     *
     * Must run BEFORE {@link #keepSignatureBlocksTogether} so the new
     * paragraph rides into the consolidated single-row cell with the
     * rest of the block.
     */
    private static final String SIGNING_IP_PARAGRAPH =
            "<w:p>"
                    + "<w:pPr><w:spacing w:before=\"40\" w:after=\"0\"/></w:pPr>"
                    + "<w:r>"
                    + "<w:rPr>"
                    + "<w:sz w:val=\"14\"/>"
                    + "<w:color w:val=\"808080\"/>"
                    + "</w:rPr>"
                    + "<w:t xml:space=\"preserve\">IP: ${finalSigningIp}"
                    + "  ·  Signed: ${finalSigningAt}</w:t>"
                    + "</w:r>"
                    + "</w:p>";

    static String insertSigningIpLine(String xml) {
        // The Date/Email line is the LAST paragraph in the right cell
        // of every consultant signature block. The unique signature
        // identifies it without false positives across the rest of
        // the document.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(<w:t[^>]*>Date / Email: \\$\\{signatureDate\\} / \\$\\{primaryEmail\\}</w:t>"
                        + "</w:r></w:p>)",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(xml);
        StringBuilder sb = new StringBuilder(xml.length());
        int lastEnd = 0;
        int touched = 0;
        while (m.find()) {
            sb.append(xml, lastEnd, m.end());
            sb.append(SIGNING_IP_PARAGRAPH);
            lastEnd = m.end();
            touched++;
        }
        sb.append(xml, lastEnd, xml.length());
        if (touched > 0) {
            log.info("Template surgery: inserted faint IP line after {} Date/Email paragraph(s)",
                    touched);
        }
        return sb.toString();
    }

    /**
     * Build O — keeps every signature block atomic. The Build-J pass
     * applied {@code <w:cantSplit/>} to each row, but cantSplit only
     * stops a single row from breaking mid-cell; it does NOT keep
     * rows together. We saw blocks split between rows (By:/Signature
     * line on page N, Name/Title line on page N+1). The fix is to
     * COLLAPSE each 4-row signature {@code <w:tbl>} into a SINGLE row
     * with two cells, each cell carrying the stacked paragraphs from
     * the original rows. A single cantSplit row cannot break across
     * pages, so the entire block becomes one indivisible unit.
     *
     * Layout-preserving: the original rows had no fixed-exact height
     * (just {@code <w:trHeight w:val="397"/>} = minimum 0.28"). The
     * paragraphs render at their natural heights inside the single
     * row, so the visual block stays identical to the original.
     */
    static String keepSignatureBlocksTogether(String xml) {
        java.util.regex.Pattern tablePattern = java.util.regex.Pattern.compile(
                "<w:tbl\\b[^>]*>.*?</w:tbl>",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher tableMatcher = tablePattern.matcher(xml);
        StringBuilder sb = new StringBuilder(xml.length());
        int lastEnd = 0;
        int tablesTouched = 0;
        int tablesConsolidated = 0;
        while (tableMatcher.find()) {
            String table = tableMatcher.group();
            sb.append(xml, lastEnd, tableMatcher.start());
            if (table.contains("${signatureImage}")
                    || table.contains("${ermSignatureImage}")
                    || table.contains("${finalSignatureImage}")
                    // Build AF — appendix authorization blocks now carry
                    // per-appendix signature keys (${appendixNSignature} /
                    // ${appendixNErmSignature}); both end in "Signature}", and
                    // only signature tables contain them, so keep recognising
                    // them so the keep-together consolidation still applies.
                    || table.contains("Signature}")) {
                String consolidated = consolidateSignatureTable(table);
                if (consolidated != null) {
                    sb.append(consolidated);
                    tablesConsolidated++;
                } else {
                    // Couldn't safely consolidate (unexpected shape) -- fall
                    // back to the Build-J row-level cantSplit so the block
                    // at least doesn't break mid-row.
                    int[] rowCount = new int[]{0};
                    sb.append(addCantSplitToEveryRow(table, rowCount));
                }
                tablesTouched++;
            } else {
                sb.append(table);
            }
            lastEnd = tableMatcher.end();
        }
        sb.append(xml, lastEnd, xml.length());
        if (tablesTouched > 0) {
            log.info(
                    "Template surgery: {} signature table(s) processed; "
                            + "{} consolidated to single cantSplit row, {} fell back to row-cantSplit",
                    tablesTouched, tablesConsolidated,
                    tablesTouched - tablesConsolidated);
        }
        return sb.toString();
    }

    /**
     * Build O — folds a multi-row 2-column signature table into a
     * single-row, two-cell table where each cell carries the stacked
     * paragraphs from the original column. The single row is marked
     * {@code <w:cantSplit/>} so it can never break across pages.
     *
     * Returns null on any structural surprise so the caller can fall
     * back to per-row cantSplit instead of corrupting the XML.
     */
    static String consolidateSignatureTable(String tableXml) {
        // Slice out <w:tblPr>, <w:tblGrid>, and every <w:tr>.
        java.util.regex.Matcher tblPrM = java.util.regex.Pattern.compile(
                "<w:tblPr\\b.*?</w:tblPr>", java.util.regex.Pattern.DOTALL)
                .matcher(tableXml);
        java.util.regex.Matcher gridM = java.util.regex.Pattern.compile(
                "<w:tblGrid\\b.*?</w:tblGrid>", java.util.regex.Pattern.DOTALL)
                .matcher(tableXml);
        if (!tblPrM.find() || !gridM.find()) return null;
        String tblPr = tblPrM.group();
        String tblGrid = gridM.group();

        java.util.regex.Matcher rowM = java.util.regex.Pattern.compile(
                "<w:tr\\b[^>]*>.*?</w:tr>", java.util.regex.Pattern.DOTALL)
                .matcher(tableXml);
        java.util.List<String> rows = new java.util.ArrayList<>();
        while (rowM.find()) rows.add(rowM.group());
        if (rows.isEmpty()) return null;

        // The signature blocks have a stable 2-column shape. If a row
        // doesn't have exactly two <w:tc> blocks, bail out so the
        // caller falls back to row-cantSplit rather than corrupting.
        java.util.List<String> leftCellTcPrs = new java.util.ArrayList<>();
        java.util.List<String> rightCellTcPrs = new java.util.ArrayList<>();
        java.util.List<java.util.List<String>> leftParas = new java.util.ArrayList<>();
        java.util.List<java.util.List<String>> rightParas = new java.util.ArrayList<>();
        for (String row : rows) {
            java.util.regex.Matcher cellM = java.util.regex.Pattern.compile(
                    "<w:tc\\b[^>]*>(.*?)</w:tc>", java.util.regex.Pattern.DOTALL)
                    .matcher(row);
            int cellIdx = 0;
            while (cellM.find()) {
                String body = cellM.group(1);
                java.util.regex.Matcher tcPrM = java.util.regex.Pattern.compile(
                        "<w:tcPr\\b.*?</w:tcPr>", java.util.regex.Pattern.DOTALL)
                        .matcher(body);
                String tcPr = tcPrM.find() ? tcPrM.group() : "";
                java.util.regex.Matcher pM = java.util.regex.Pattern.compile(
                        "<w:p\\b[^>]*>.*?</w:p>|<w:p\\b[^>]*/>",
                        java.util.regex.Pattern.DOTALL).matcher(body);
                java.util.List<String> ps = new java.util.ArrayList<>();
                while (pM.find()) ps.add(pM.group());
                if (cellIdx == 0) {
                    leftCellTcPrs.add(tcPr);
                    leftParas.add(ps);
                } else if (cellIdx == 1) {
                    rightCellTcPrs.add(tcPr);
                    rightParas.add(ps);
                }
                cellIdx++;
            }
            if (cellIdx != 2) return null;
        }

        // Use the first row's tcPr (preserves column width, borders,
        // vAlign). Concatenate paragraphs across all rows for each
        // column. Drop any vAlign=center on the cell since multi-
        // paragraph cells with vAlign=center collapse oddly; default
        // top-alignment matches the visual flow of stacked lines.
        String leftTcPr = stripCellVAlign(leftCellTcPrs.get(0));
        String rightTcPr = stripCellVAlign(rightCellTcPrs.get(0));

        StringBuilder leftBody = new StringBuilder();
        for (java.util.List<String> ps : leftParas) {
            for (String p : ps) leftBody.append(p);
        }
        StringBuilder rightBody = new StringBuilder();
        for (java.util.List<String> ps : rightParas) {
            for (String p : ps) rightBody.append(p);
        }

        // Build the consolidated single-row table. cantSplit is set
        // first inside trPr so it ALWAYS holds even if the original
        // first row had no trPr.
        StringBuilder out = new StringBuilder(tableXml.length());
        out.append("<w:tbl>");
        out.append(tblPr);
        out.append(tblGrid);
        out.append("<w:tr>");
        out.append("<w:trPr><w:cantSplit/></w:trPr>");
        out.append("<w:tc>").append(leftTcPr).append(leftBody).append("</w:tc>");
        out.append("<w:tc>").append(rightTcPr).append(rightBody).append("</w:tc>");
        out.append("</w:tr>");
        out.append("</w:tbl>");
        return out.toString();
    }

    /** Remove {@code <w:vAlign .../>} from a cell's tcPr so stacked paragraphs flow naturally from the top. */
    private static String stripCellVAlign(String tcPr) {
        return tcPr.replaceAll("<w:vAlign\\b[^/]*/>", "");
    }

    /**
     * Build J fallback — preserved for the rare case where a signature
     * table doesn't match the expected 2-column shape and consolidation
     * bails out. Injects {@code <w:cantSplit/>} into every row's trPr.
     */
    private static String addCantSplitToEveryRow(String tableXml, int[] counter) {
        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "(<w:tr\\b[^>]*>)(\\s*<w:trPr\\b[^>]*>)?",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = row.matcher(tableXml);
        StringBuilder out = new StringBuilder(tableXml.length() + 256);
        int lastEnd = 0;
        while (m.find()) {
            out.append(tableXml, lastEnd, m.start());
            String openTag = m.group(1);
            String trPrOpen = m.group(2);
            out.append(openTag);
            if (trPrOpen != null) {
                int trPrEnd = m.end();
                int closeIdx = tableXml.indexOf("</w:trPr>", trPrEnd);
                String trPrBody = closeIdx > 0
                        ? tableXml.substring(trPrEnd, closeIdx) : "";
                if (trPrBody.contains("<w:cantSplit")) {
                    out.append(trPrOpen);
                } else {
                    out.append(trPrOpen);
                    out.append("<w:cantSplit/>");
                    counter[0]++;
                }
            } else {
                out.append("<w:trPr><w:cantSplit/></w:trPr>");
                counter[0]++;
            }
            lastEnd = m.end();
        }
        out.append(tableXml, lastEnd, tableXml.length());
        return out.toString();
    }

    static String stripOrphanEmptyParagraphsBeforePageBreaks(String xml) {
        // Pattern: </w:tbl> then a self-closing empty paragraph
        // <w:p ... rsidR="..." rsidRDefault="..." /> then a paragraph
        // whose only run is <w:r><w:br w:type="page"/></w:r>.
        // The empty paragraph is replaced by nothing; the page-break
        // paragraph is kept intact.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(</w:tbl>)\\s*<w:p\\b[^>]*/>\\s*(<w:p\\b[^>]*><w:r><w:br w:type=\"page\"/></w:r></w:p>)",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(xml);
        StringBuilder sb = new StringBuilder(xml.length());
        int removed = 0;
        int lastEnd = 0;
        while (m.find()) {
            sb.append(xml, lastEnd, m.start());
            sb.append(m.group(1));
            sb.append(m.group(2));
            lastEnd = m.end();
            removed++;
        }
        sb.append(xml, lastEnd, xml.length());
        if (removed > 0) {
            log.info("Template surgery: removed {} orphan empty paragraph(s) "
                    + "between signature tables and page-break paragraphs",
                    removed);
        }
        return sb.toString();
    }

    private Path fillTemplate(Map<String, Object> ctx) throws IOException {
        Path out = Files.createTempFile("agreement-", ".docx");
        // standard() -- includes the default preprocessors + comment
        // processors so the template's table-repeat / display-if
        // comments behave as expected.
        // setEvaluationContextConfigurer + MapAccessor -- docx-stamper
        //                       2.x ships a StandardEvaluationContext
        //                       without MapAccessor registered, so
        //                       SpEL can't read ${key} expressions
        //                       against the Map<String,Object> we
        //                       pass to stamp(). Without this every
        //                       placeholder gets EL1008E and the
        //                       unresolved-default replacement quietly
        //                       blanks the field -- structurally
        //                       correct PDF with no data. Registering
        //                       MapAccessor flips the full set of
        //                       placeholder substitutions back on
        //                       without touching the template or the
        //                       context-building code.
        // nullToEmpty()      -- any null context value renders as "".
        // image()            -- map preset.Image values into the doc
        //                       at the placeholder position.
        OfficeStamperConfiguration cfg = OfficeStamperConfigurations.standard()
                .setEvaluationContextConfigurer(
                        spelCtx -> spelCtx.addPropertyAccessor(new MapAccessor()))
                .addResolver(Resolvers.image())
                .addResolver(Resolvers.nullToEmpty())
                .replaceUnresolvedExpressions(true)
                .unresolvedExpressionsDefaultValue("")
                .setFailOnUnresolvedExpression(false);
        StreamStamper<?> stamper = OfficeStampers.docxStamper(cfg);
        // Build I — feed the preprocessed template (orphan empty
        // paragraphs stripped) instead of the raw classpath resource so
        // every render benefits from the alignment surgery.
        byte[] templateBytes = preprocessedTemplate();
        try (InputStream in = new ByteArrayInputStream(templateBytes);
             OutputStream os = Files.newOutputStream(out)) {
            stamper.stamp(in, ctx, os);
        }
        return out;
    }

    // ── LibreOffice headless conversion ─────────────────────────────

    private Path convertToPdf(Path docx) throws IOException, InterruptedException {
        Path outDir = docx.getParent();
        Process p = new ProcessBuilder(
                "libreoffice", "--headless",
                "--convert-to", "pdf",
                "--outdir", outDir.toString(),
                docx.toString())
                .redirectErrorStream(true)
                .start();
        if (!p.waitFor(LIBREOFFICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(
                    "LibreOffice conversion timed out after "
                            + LIBREOFFICE_TIMEOUT_SECONDS + "s");
        }
        if (p.exitValue() != 0) {
            String err = new String(p.getInputStream().readAllBytes());
            throw new IOException(
                    "LibreOffice failed (exit " + p.exitValue() + "): " + err);
        }
        String pdfName = docx.getFileName().toString()
                .replaceFirst("\\.docx$", ".pdf");
        Path pdf = outDir.resolve(pdfName);
        if (!Files.exists(pdf)) {
            throw new IOException("Expected PDF output missing: " + pdf);
        }
        return pdf;
    }

    // ── Cloudinary upload ───────────────────────────────────────────

    /**
     * Build T — upload an arbitrary PDF byte array under a custom
     * public_id (the consultant-version PDF uses
     * {@code agreements/{appId}-consultant} so it doesn't collide with
     * the ERM-signed final PDF at {@code agreements/{appId}}).
     */
    public PdfUploadResult uploadPdfBytes(byte[] bytes, String publicId) throws IOException {
        Map<?, ?> result = cloudinary.uploader().upload(bytes,
                ObjectUtils.asMap(
                        "public_id", publicId,
                        "resource_type", "raw",
                        "type", "authenticated",
                        "overwrite", true));
        Object url = result.get("secure_url");
        if (url == null) {
            throw new IOException("Cloudinary upload returned no secure_url");
        }
        return new PdfUploadResult(url.toString(), publicId, bytes);
    }

    private PdfUploadResult uploadBytesToCloudinary(byte[] bytes, String appId) throws IOException {
        // type=authenticated: bare /raw/upload/agreements/{id} URLs
        // return 401. Re-fetching requires a signature minted with the
        // API secret (see signedPdfUrl()). The secure_url returned at
        // upload time IS pre-signed -- safe to persist on the entity as
        // the canonical URL for the operator's existing browser tab
        // (no second sign() round trip), but anyone who only knows the
        // public_id can't construct it.
        String publicId = "agreements/" + appId;
        Map<?, ?> result = cloudinary.uploader().upload(bytes,
                ObjectUtils.asMap(
                        "public_id", publicId,
                        "resource_type", "raw",
                        "type", "authenticated",
                        "overwrite", true));
        Object url = result.get("secure_url");
        if (url == null) {
            throw new IOException("Cloudinary upload returned no secure_url");
        }
        // Bytes are filled in by the outer generateAgreementPdf() that
        // already holds them -- the upload helper itself never re-reads
        // them, so this slot stays null and is replaced upstream.
        return new PdfUploadResult(url.toString(), publicId, null);
    }

    private static void safeDelete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignore) {
            /* best-effort cleanup */
        }
    }
}
