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
        byte[] bytes = renderPdfBytes(app, null);
        PdfUploadResult uploaded = uploadBytesToCloudinary(bytes, app.getApplicationId());
        return new PdfUploadResult(uploaded.secureUrl(), uploaded.publicId(), bytes);
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
            return Files.readAllBytes(pdf);
        } finally {
            safeDelete(filledDocx);
            safeDelete(pdf);
        }
    }

    /**
     * Build I — rasterises {@code pdfBytes} (typically from
     * {@link #renderPdfBytes}) into a list of PNG byte arrays, one per
     * PDF page, each stamped with a diagonal, semi-transparent
     * watermark of {@code viewerEmail} + a UTC timestamp +
     * "CONFIDENTIAL". The wizard shows these as the consultant review
     * preview so the document cannot be downloaded as a PDF and every
     * screenshot or photograph carries the viewer's identity.
     *
     * Render DPI is intentionally modest (~110) so the bytes stay
     * cheap to stream and the watermark stays legible.
     */
    public List<byte[]> renderWatermarkedPageImages(
            byte[] pdfBytes, String viewerEmail) throws IOException {
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
            String stamp = watermarkText(viewerEmail);
            List<byte[]> pages = new ArrayList<>(pageCount);
            for (int i = 0; i < pageCount; i++) {
                BufferedImage page = renderer.renderImageWithDPI(i, 110f);
                BufferedImage stamped = applyWatermark(page, stamp);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(stamped, "png", baos);
                pages.add(baos.toByteArray());
            }
            log.info("Build I — rendered {} watermarked preview page(s) for {}",
                    pageCount, viewerEmail);
            return pages;
        }
    }

    /** Build I — formats the watermark text shown on every page image. */
    private static String watermarkText(String viewerEmail) {
        String email = (viewerEmail == null || viewerEmail.isBlank())
                ? "consultant" : viewerEmail.trim();
        String ts = LocalDateTime.now(java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"));
        return "CONFIDENTIAL  •  " + email + "  •  " + ts;
    }

    /**
     * Build I — bakes a repeating, diagonal, semi-transparent watermark
     * across {@code page}. The rasterised page bytes are the only
     * delivery vehicle, so once the watermark is on the image the
     * consultant cannot strip it from the browser. A diagonal rotation
     * + tiled layout makes a clean crop of the underlying text
     * impractical.
     */
    private static BufferedImage applyWatermark(BufferedImage page, String text) {
        int w = page.getWidth();
        int h = page.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(page, 0, 0, null);
            // Diagonal, repeating watermark. Rotated 30 deg counter-
            // clockwise around the page centre.
            g.setComposite(java.awt.AlphaComposite.getInstance(
                    java.awt.AlphaComposite.SRC_OVER, 0.18f));
            g.setColor(new java.awt.Color(0x1B, 0x2A, 0x5C)); // sage-navy
            int fontSize = Math.max(18, Math.min(w, h) / 36);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, fontSize));
            java.awt.geom.AffineTransform original = g.getTransform();
            g.rotate(Math.toRadians(-30), w / 2.0, h / 2.0);
            java.awt.FontMetrics fm = g.getFontMetrics();
            int textW = fm.stringWidth(text);
            int textH = fm.getHeight();
            int stepX = textW + fontSize * 4;
            int stepY = textH * 5;
            // Cover the page generously past its bounds so the rotation
            // doesn't leave bare corners.
            int margin = Math.max(w, h) / 2;
            for (int y = -margin; y < h + margin; y += stepY) {
                int xShift = (y / stepY) % 2 == 0 ? 0 : stepX / 2;
                for (int x = -margin + xShift; x < w + margin; x += stepX) {
                    g.drawString(text, x, y);
                }
            }
            g.setTransform(original);
        } finally {
            g.dispose();
        }
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
        Function<LocalDateTime, String> fdt =
                d -> d == null ? "" : d.toLocalDate().format(US_SHORT_DATE_FMT);

        // Header. The template's ${participantFullLegalName} and
        // ${primaryEmail} placeholders don't have 1:1 entity getters
        // -- we map them to the closest existing fields. signedLegalName
        // is set by the consultant at sign time; before that, fall back
        // to the consultantName the ERM seeded the row with.
        String participantName = app.getSignedLegalName() != null
                && !app.getSignedLegalName().isBlank()
                ? app.getSignedLegalName()
                : app.getConsultantName();
        c.put("effectiveDate", fd.apply(app.getEffectiveDate()));
        c.put("participantFullLegalName", nz.apply(participantName));
        c.put("primaryEmail", nz.apply(app.getConsultantEmail()));
        c.put("primaryPhone", nz.apply(app.getPrimaryPhone()));
        c.put("workAuthorizationCategory", nz.apply(app.getWorkAuthorizationCategory()));
        c.put("residenceAddress", nz.apply(app.getResidenceAddress()));

        // Rate card (Section 11 + Appendix 1).
        c.put("ratePeriod1", nz.apply(app.getRatePeriod1()));
        c.put("rateAmount1", nz.apply(app.getRateAmount1()));
        c.put("ratePeriod2", nz.apply(app.getRatePeriod2()));
        c.put("rateAmount2", nz.apply(app.getRateAmount2()));

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
        c.put("bgCurrentAddress", nz.apply(app.getBgCurrentAddress()));
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
        c.put("portalPlatform", nz.apply(app.getPortalPlatform()));
        c.put("portalUsername", nz.apply(app.getPortalUsername()));
        c.put("portalAuthorizedActions", nz.apply(app.getPortalAuthorizedActions()));
        c.put("portalEffectiveDate", fd.apply(app.getPortalEffectiveDate()));
        c.put("portalRevocationContact", nz.apply(app.getPortalRevocationContact()));

        // Appendix 5 -- security check (optional).
        c.put("securityCheckCount", nz.apply(app.getSecurityCheckCount()));
        c.put("securityCheckNumbers", nz.apply(app.getSecurityCheckNumbers()));
        c.put("securityCheckBank", nz.apply(app.getSecurityCheckBank()));
        c.put("securityCheckHolderName", nz.apply(app.getSecurityCheckHolderName()));
        c.put("securityCheckAmount", nz.apply(app.getSecurityCheckAmount()));
        c.put("securityCheckDates", nz.apply(app.getSecurityCheckDates()));

        // ERM signature block. ermName/ermTitle come from the Approve &
        // Sign input (the signer confirms them); ermEmail is resolved
        // from the OWNING ERM (Phase B), falling back to the global env.
        c.put("ermName", nz.apply(app.getErmName()));
        c.put("ermTitle", nz.apply(app.getErmTitle()));
        c.put("ermEmail", resolveOwnerEmail(app));
        c.put("signatureDate", fdt.apply(app.getSignatureDate()));

        // Image placeholders -- separate entity fields. Each renders as
        // blank when the corresponding signature hasn't been captured.
        // F-4 first-and-last model: signatureImage is drawn on the
        // main-agreement step; finalSignatureImage is drawn on the
        // review step and stamps the closing/execution block.
        c.put("signatureImage", buildImage(app.getSignatureImage()));
        c.put("finalSignatureImage", buildImage(app.getFinalSignatureImage()));
        c.put("ermSignatureImage", buildImage(app.getErmSignatureUrl()));

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
        v.put("ratePeriod1", nz.apply(app.getRatePeriod1()));
        v.put("rateAmount1", nz.apply(app.getRateAmount1()));
        v.put("ratePeriod2", nz.apply(app.getRatePeriod2()));
        v.put("rateAmount2", nz.apply(app.getRateAmount2()));
        v.put("ermName", nz.apply(app.getErmName()));
        v.put("ermTitle", nz.apply(app.getErmTitle()));
        v.put("ermEmail", resolveOwnerEmail(app));
        v.put("signatureDate", app.getSignatureDate() == null
                ? "" : app.getSignatureDate().toLocalDate().format(US_SHORT_DATE_FMT));
        return v;
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
        };
        for (String k : textKeys) c.put(k, LINE);
        // Signature images render as empty boxes in the preview.
        c.put("signatureImage", "");
        c.put("finalSignatureImage", "");
        c.put("ermSignatureImage", "");
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
     * Build J — keeps every signature block atomic so the label
     * ("By:", "Signature:") and the signature image and the name /
     * title / date row all land on the same page. The block is a
     * 4-row 2-column {@code <w:tbl>}; we identify each signature
     * table by the presence of a signature image placeholder
     * ({@code ${signatureImage}} or {@code ${ermSignatureImage}}) and
     * inject {@code <w:cantSplit/>} into every row's trPr.
     *
     * cantSplit alone prevents a single row from splitting across
     * pages -- which is the observed bug (label on page N, signature
     * image on page N+1). Combined with the table being only ~3-4
     * rows / ~3 cm tall after signature normalisation, the layout
     * engine reliably keeps the whole block together.
     */
    static String keepSignatureBlocksTogether(String xml) {
        // Walk each <w:tbl>...</w:tbl> and check whether it contains
        // a signature placeholder. If yes, transform its rows.
        java.util.regex.Pattern tablePattern = java.util.regex.Pattern.compile(
                "<w:tbl\\b[^>]*>.*?</w:tbl>",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher tableMatcher = tablePattern.matcher(xml);
        StringBuilder sb = new StringBuilder(xml.length());
        int lastEnd = 0;
        int tablesTouched = 0;
        int rowsTouched = 0;
        while (tableMatcher.find()) {
            String table = tableMatcher.group();
            sb.append(xml, lastEnd, tableMatcher.start());
            if (table.contains("${signatureImage}")
                    || table.contains("${ermSignatureImage}")
                    || table.contains("${finalSignatureImage}")) {
                int[] rowCount = new int[]{0};
                String transformed = addCantSplitToEveryRow(table, rowCount);
                sb.append(transformed);
                tablesTouched++;
                rowsTouched += rowCount[0];
            } else {
                sb.append(table);
            }
            lastEnd = tableMatcher.end();
        }
        sb.append(xml, lastEnd, xml.length());
        if (tablesTouched > 0) {
            log.info("Template surgery: cantSplit set on {} row(s) across {} signature table(s)",
                    rowsTouched, tablesTouched);
        }
        return sb.toString();
    }

    /**
     * Inject {@code <w:cantSplit/>} into every {@code <w:tr>} in the
     * given table fragment. Two cases:
     *   - row already has {@code <w:trPr>}: prepend cantSplit inside it
     *     unless it's already there.
     *   - row has no {@code <w:trPr>}: insert a fresh
     *     {@code <w:trPr><w:cantSplit/></w:trPr>} right after the
     *     {@code <w:tr ...>} opening tag.
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
                // <w:trPr> already present -- prepend cantSplit if missing.
                int trPrEnd = m.end();
                // Look ahead until the matching </w:trPr> to test for
                // an existing <w:cantSplit/> child.
                int closeIdx = tableXml.indexOf("</w:trPr>", trPrEnd);
                String trPrBody = closeIdx > 0
                        ? tableXml.substring(trPrEnd, closeIdx) : "";
                if (trPrBody.contains("<w:cantSplit")) {
                    // Already set -- leave the row alone.
                    out.append(trPrOpen);
                } else {
                    out.append(trPrOpen);
                    out.append("<w:cantSplit/>");
                    counter[0]++;
                }
            } else {
                // No <w:trPr> yet -- inject a fresh one.
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
