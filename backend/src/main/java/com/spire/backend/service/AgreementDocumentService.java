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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
     * Carries both the Cloudinary {@code secure_url} (for display /
     * email attachment fetch) and the {@code public_id} (for re-signing
     * short-lived download URLs later). Two pieces of state pulled out
     * of the upload response so callers can persist both atomically.
     */
    public record PdfUploadResult(String secureUrl, String publicId) {}

    public PdfUploadResult generateAgreementPdf(ConsultantApplication app) throws Exception {
        Map<String, Object> ctx = buildContext(app);
        Path filledDocx = null;
        Path pdf = null;
        try {
            filledDocx = fillTemplate(ctx);
            pdf = convertToPdf(filledDocx);
            return uploadToCloudinary(pdf, app.getApplicationId());
        } finally {
            safeDelete(filledDocx);
            safeDelete(pdf);
        }
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
        Function<LocalDate, String> fd = d -> d == null ? "" : d.format(DATE_FMT);
        Function<LocalDateTime, String> fdt =
                d -> d == null ? "" : d.toLocalDate().format(DATE_FMT);

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
        c.put("bgDriverLicense", nz.apply(app.getBgDriverLicense()));

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
                ? "" : app.getEffectiveDate().format(DATE_FMT));
        v.put("ratePeriod1", nz.apply(app.getRatePeriod1()));
        v.put("rateAmount1", nz.apply(app.getRateAmount1()));
        v.put("ratePeriod2", nz.apply(app.getRatePeriod2()));
        v.put("rateAmount2", nz.apply(app.getRateAmount2()));
        v.put("ermName", nz.apply(app.getErmName()));
        v.put("ermTitle", nz.apply(app.getErmTitle()));
        v.put("ermEmail", resolveOwnerEmail(app));
        v.put("signatureDate", app.getSignatureDate() == null
                ? "" : app.getSignatureDate().toLocalDate().format(DATE_FMT));
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
                "bgDateOfBirth", "bgFullSsn", "bgDriverLicense",
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
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(input));
            if (original == null) {
                log.warn("Signature normalize: undecodable bytes ({}B), passing through",
                        input.length);
                return input;
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

            byte[] out = encodePngWithDensity(resized);
            // Operational evidence that the normalize fired on the real
            // embedding path with the expected box + density.
            log.info("Signature normalized: in {}x{} -> out {}x{} px, density=96dpi, bytes {}->{}",
                    srcW, srcH, targetW, targetH, input.length, out.length);
            return out;
        } catch (Exception e) {
            log.warn("Signature normalize failed ({}); using original bytes",
                    e.getMessage());
            return input;
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
        try (InputStream in = templateResource.getInputStream();
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

    private PdfUploadResult uploadToCloudinary(Path pdf, String appId) throws IOException {
        // type=authenticated: bare /raw/upload/agreements/{id} URLs
        // return 401. Re-fetching requires a signature minted with the
        // API secret (see signedPdfUrl()). The secure_url returned at
        // upload time IS pre-signed -- safe to persist on the entity as
        // the canonical URL for the operator's existing browser tab
        // (no second sign() round trip), but anyone who only knows the
        // public_id can't construct it.
        String publicId = "agreements/" + appId;
        Map<?, ?> result = cloudinary.uploader().upload(pdf.toFile(),
                ObjectUtils.asMap(
                        "public_id", publicId,
                        "resource_type", "raw",
                        "type", "authenticated",
                        "overwrite", true));
        Object url = result.get("secure_url");
        if (url == null) {
            throw new IOException("Cloudinary upload returned no secure_url");
        }
        return new PdfUploadResult(url.toString(), publicId);
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
