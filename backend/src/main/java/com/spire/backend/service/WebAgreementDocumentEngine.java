package com.spire.backend.service;

import com.spire.backend.entity.WebAgreement;
import com.spire.backend.exception.StorageUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.context.expression.MapAccessor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The website agreement's own PDF drawing: a copy of the parts of the
 * console's renderer (AgreementDocumentService, never called from here)
 * that the website uses, so the website never shares the console's
 * LibreOffice slots, locks or caches. Same template, same template
 * surgery, same values and formats, same docx-stamper fill and the same
 * LibreOffice call, so the document is word for word the console's.
 *
 * It reads the {@link WebAgreement} itself (the same field names as the
 * console's ConsultantApplication), so nothing of the console's is used to
 * draw a website document.
 *
 * Left out because the website never reaches it:
 * <ul>
 *   <li>the console's storage and Cloudinary reads. Only the agreement's
 *       data fields are used, never a storage key, id or URL, so every
 *       signature slot starts blank (what the console's buildContext gives
 *       a row with none) and the renderer's override fills them from the
 *       website's storage;</li>
 *   <li>the console's owner lookup and its {@code agreement-erm.email}
 *       fallback. ermEmail starts blank, and the renderer always puts the
 *       owner ERM's website email there (blank when there is none), so the
 *       console's address never prints on a website agreement;</li>
 *   <li>the console's attachment append. The renderer appends the
 *       website's uploads itself.</li>
 * </ul>
 *
 * One LibreOffice profile slot of its own ({@code sage-web-lo-profiles}
 * under java.io.tmpdir), which is also the website's render permit: one
 * conversion at a time, a wait of at most 15 seconds (well under the
 * database pool's 30-second connection timeout, since a waiting request
 * still holds its connection), and at most three waiting. A render is
 * admitted before it does any work: with {@link #MAX_IN_PROGRESS} renders
 * already in progress the next one is busy at once, having built, read and
 * filled nothing, so it gives its database connection straight back. Busy
 * is a {@link StorageUnavailableException} (503, {@link #BUSY_MESSAGE}). A
 * failed conversion wipes the profile, and the slot and the admission
 * always come back.
 */
@Service
@Slf4j
public class WebAgreementDocumentEngine {

    /** The console's master template, read from the classpath. */
    static final String TEMPLATE = "templates/SageITCO_Master_Agreement_TEMPLATE.docx";

    static final String BUSY_MESSAGE = "The document service is busy. Please try again in a minute.";

    /** The most requests that may wait for the slot; one more is busy at once. */
    static final int MAX_WAITING = 3;

    /**
     * The most renders in progress at once: the one converting, {@link
     * #MAX_WAITING} in line for the slot and one getting its document ready.
     * That last place keeps the slot's own limit deciding for a request that
     * arrives while the line is full but will find room by the time its
     * template is filled (as before admission); a burst past it is still
     * refused at once.
     */
    static final int MAX_IN_PROGRESS = MAX_WAITING + 2;

    /** US short date, as in every date the console prints. */
    private static final DateTimeFormatter US_SHORT_DATE_FMT =
            DateTimeFormatter.ofPattern("MM-dd-yyyy");

    /** LibreOffice conversion timeout (the console's). */
    private static final long LIBREOFFICE_TIMEOUT_SECONDS = 60L;

    /** The slot: one conversion at a time, in the order they asked. Package-private for the tests. */
    final Semaphore slot = new Semaphore(1, true);

    /** Requests waiting for {@link #slot} right now. Package-private for the tests. */
    final AtomicInteger waiting = new AtomicInteger();

    /**
     * Renders in progress: taken before any work, given back when the render
     * is done. Never waited for. Package-private for the tests.
     */
    final Semaphore admission = new Semaphore(MAX_IN_PROGRESS);

    /** How long a render waits for the slot. Package-private so tests can shorten it. */
    Duration slotWait = Duration.ofSeconds(15);

    /** The slot's LibreOffice profile. Package-private so tests can move it. */
    Path profileDir = Path.of(System.getProperty("java.io.tmpdir"), "sage-web-lo-profiles", "slot-0");

    // ── Rendering ────────────────────────────────────────────────────

    /**
     * The console's renderPdfBytes: the context (after the overrides) filled
     * into the template and converted through the slot. Admitted first (see
     * {@link #admitted}): the context and the overrides run only for an
     * admitted render. No attachments (see the class doc). Busy is a
     * {@link StorageUnavailableException}; any other failure is thrown as is.
     */
    public byte[] renderPdfBytes(
            WebAgreement agreement,
            ContextOverrides overrides) throws Exception {
        return admitted(() -> {
            Map<String, Object> ctx = buildContext(agreement);
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
        });
    }

    /** Sets or blanks signature slots after buildContext (the console's ContextOverrides). */
    public interface ContextOverrides {
        void apply(Map<String, Object> ctx, WebAgreementDocumentEngine engine);
    }

    /** The console's consultant preview: primary signature from a data: URL; final + ERM blank. */
    public static ContextOverrides consultantPreviewOverrides(String primarySignatureDataUrl) {
        return (ctx, svc) -> {
            if (primarySignatureDataUrl != null && !primarySignatureDataUrl.isBlank()) {
                ctx.put("signatureImage", svc.buildImageFromDataUrl(primarySignatureDataUrl));
            }
            ctx.put("finalSignatureImage", "");
            ctx.put("ermSignatureImage", "");
        };
    }

    /** The console's ERM preview: the main ERM signature blank. */
    public static ContextOverrides ermPreviewOverrides() {
        return (ctx, svc) -> ctx.put("ermSignatureImage", "");
    }

    // ── Page images ──────────────────────────────────────────────────

    /**
     * The console's renderWatermarkedPageImages: one PNG per page at 110
     * DPI, with the tiled CONFIDENTIAL • viewer • UTC time watermark when
     * {@code watermark} is true. PDFBox only, so no slot.
     */
    public List<byte[]> renderWatermarkedPageImages(
            byte[] pdfBytes, String viewerEmail, boolean watermark) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IOException("Empty PDF bytes; nothing to rasterize.");
        }
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

    /** The per-viewer watermark: CONFIDENTIAL • email • UTC time. */
    private static String watermarkText(String viewerEmail) {
        String who = (viewerEmail == null || viewerEmail.isBlank())
                ? "consultant" : viewerEmail.trim();
        String ts = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
                .format(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return "CONFIDENTIAL  •  " + who + "  •  " + ts;
    }

    /** A diagonal, tiled, semi-transparent watermark (sage-navy at 18%, 30° tilt). */
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

    // ── The blank template ("View full agreement") ───────────────────

    /** The blank template PDF, the same for everyone, once rendered. */
    private volatile byte[] blankPreviewPdfCache;

    /**
     * The template rendered with underscore lines and no signatures (the
     * console's getBlankPreviewPdfBytes), cached after the first render. A
     * cached copy needs no admission; the first render is admitted like any
     * other. No lock around the render, so a wait for the slot stays
     * bounded; two first requests at once just render the same bytes twice.
     */
    public byte[] getBlankPreviewPdfBytes() throws Exception {
        byte[] cached = blankPreviewPdfCache;
        if (cached != null) return cached;
        return admitted(() -> {
            // Rendered by an earlier request since this one looked.
            byte[] ready = blankPreviewPdfCache;
            if (ready != null) return ready;
            Path docx = null;
            Path pdf = null;
            try {
                Map<String, Object> ctx = buildBlankContext();
                docx = fillTemplate(ctx);
                pdf = convertToPdf(docx);
                ready = Files.readAllBytes(pdf);
                blankPreviewPdfCache = ready;
                log.info("Cached the web agreement's blank template PDF ({} bytes) on first request",
                        ready.length);
                return ready;
            } finally {
                safeDelete(docx);
                safeDelete(pdf);
            }
        });
    }

    /**
     * buildContext's keys with a line of underscores for every text value
     * and blank signature images. Keep the keys in step with
     * {@link #buildContext}.
     */
    private Map<String, Object> buildBlankContext() {
        Map<String, Object> c = new HashMap<>();
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
                // ERM signature block (the images are below)
                "ermName", "ermTitle", "ermEmail", "signatureDate",
                "ermSignatureDate",
                // The faint IP + time line under Date / Email
                "finalSigningIp", "finalSigningAt",
        };
        for (String k : textKeys) c.put(k, LINE);
        c.put("signatureImage", "");
        c.put("finalSignatureImage", "");
        c.put("ermSignatureImage", "");
        // The per-appendix signature blocks.
        for (int n = 1; n <= 5; n++) {
            c.put("appendix" + n + "Signature", "");
            c.put("appendix" + n + "ErmSignature", "");
            c.put("appendix" + n + "SignatureDate", LINE);
            c.put("appendix" + n + "ErmSignatureDate", LINE);
            c.put("appendix" + n + "Email", LINE);
        }
        return c;
    }

    // ── Filenames ────────────────────────────────────────────────────

    /**
     * The console's slug rule: whitespace runs become one hyphen, anything
     * outside {@code [A-Za-z0-9_-]} goes, repeated separators collapse,
     * leading / trailing ones are trimmed. Case is kept. "" for null or
     * blank input.
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
     * The console's PDF filename: {@code SageITCO-Agreement_{name}[_{track}].pdf}.
     * name = signedLegalName, else consultantName, else "web-" + the
     * applicationId (the id the website's files have always carried); the
     * track segment is left out when blank; a slug that comes out empty
     * falls back to that id.
     */
    public static String buildPdfFilename(WebAgreement app) {
        String fallbackId = "web-" + app.getApplicationId();
        String rawName = app.getSignedLegalName();
        if (rawName == null || rawName.isBlank()) rawName = app.getConsultantName();
        if (rawName == null || rawName.isBlank()) rawName = fallbackId;
        String nameSlug = slugify(rawName);
        if (nameSlug.isEmpty()) nameSlug = slugify(fallbackId);

        String trackSlug = slugify(app.getTechnologyTrack());

        String base = "SageITCO-Agreement_" + nameSlug;
        if (!trackSlug.isEmpty()) base = base + "_" + trackSlug;
        return base + ".pdf";
    }

    // ── The context ──────────────────────────────────────────────────

    /** The console's buildContext on the website agreement (see the class doc for what differs). */
    private Map<String, Object> buildContext(WebAgreement app) {
        Map<String, Object> c = new HashMap<>();
        Function<String, String> nz = s -> s == null ? "" : s;
        // Every date prints MM-DD-YYYY.
        Function<LocalDate, String> fd = d -> d == null ? "" : d.format(US_SHORT_DATE_FMT);

        // Header. The typed signing name wins; else First Middle? Last.
        String participantName = app.getSignedLegalName() != null
                && !app.getSignedLegalName().isBlank()
                ? app.getSignedLegalName()
                : composeFullName(app);
        c.put("effectiveDate", fd.apply(app.getEffectiveDate()));
        c.put("participantFullLegalName", nz.apply(participantName));
        c.put("primaryEmail", nz.apply(app.getConsultantEmail()));
        c.put("primaryPhone", nz.apply(app.getPrimaryPhone()));
        c.put("workAuthorizationCategory", effectiveWorkAuth(app));
        c.put("residenceAddress", assembledAddress(app));

        // Rate card (Section 11 + Appendix 1).
        c.put("ratePeriod1", nz.apply(app.getRatePeriod1()));
        c.put("rateAmount1", nz.apply(app.getRateAmount1()));
        c.put("ratePeriod2", nz.apply(app.getRatePeriod2()));
        c.put("rateAmount2", nz.apply(app.getRateAmount2()));
        // Appendix 1 Schedule 1, the Phase 2 deliverables period (one merged cell).
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

        // Appendix 3 -- background check.
        c.put("bgFullLegalName", nz.apply(app.getBgFullLegalName()));
        c.put("bgOtherNamesUsed", nz.apply(app.getBgOtherNamesUsed()));
        c.put("bgCurrentAddress", assembledCurrentAddress(app));
        c.put("bgDateOfBirth", fd.apply(app.getBgDateOfBirth()));
        c.put("bgFullSsn", nz.apply(app.getBgFullSsn()));
        // A Driver's License and/or a State ID, both in $bgDriverLicense
        // ("Driver's License: 123; State ID: 456") with the kinds in $idType.
        String dlNum = nz.apply(app.getBgDriverLicense());
        String stateNum = nz.apply(app.getBgStateId());
        // A legacy STATE_ID row kept its one number in bgDriverLicense: it is a
        // State ID number, never a Driver's License.
        if ("STATE_ID".equals(app.getIdType())) {
            if (stateNum.isEmpty()) stateNum = dlNum;
            dlNum = "";
        }
        java.util.List<String> idKinds = new java.util.ArrayList<>();
        java.util.List<String> idLines = new java.util.ArrayList<>();
        if (!dlNum.isEmpty()) {
            idKinds.add("Driver's License");
            idLines.add("Driver's License: " + dlNum);
        }
        if (!stateNum.isEmpty()) {
            idKinds.add("State ID");
            idLines.add("State ID: " + stateNum);
        }
        c.put("idType", String.join(" / ", idKinds));
        c.put("bgDriverLicense", String.join("; ", idLines));

        // Appendix 4 -- portal access (optional), the entries comma-joined. The
        // website has no legacy single-value portal columns, so nothing to fall back to.
        c.put("portalPlatform", derivePortalField(app, "platform", null));
        c.put("portalUsername", derivePortalField(app, "username", null));
        c.put("portalAuthorizedActions", nz.apply(app.getPortalAuthorizedActions()));
        c.put("portalEffectiveDate", fd.apply(app.getPortalEffectiveDate()));
        c.put("portalRevocationContact", nz.apply(app.getPortalRevocationContact()));

        // Appendix 5 -- security cheque(s) (optional), numbers from the cheques list.
        c.put("securityCheckCount", nz.apply(app.getSecurityCheckCount()));
        c.put("securityCheckNumbers", deriveChequeField(app, "number",
                app.getSecurityCheckNumbers()));
        c.put("securityCheckBank", nz.apply(app.getSecurityCheckBank()));
        c.put("securityCheckHolderName", nz.apply(app.getSecurityCheckHolderName()));
        c.put("securityCheckAmount", nz.apply(app.getSecurityCheckAmount()));
        // The cheque date left the form; it always prints empty.
        c.put("securityCheckDates", "");

        // ERM signature block. ermEmail is blank here, never the console's
        // agreement-erm.email; the renderer puts the owner's website email.
        c.put("ermName", nz.apply(app.getErmName()));
        c.put("ermTitle", nz.apply(app.getErmTitle()));
        c.put("ermEmail", "");
        // Blank until the ERM countersigns (no today fallback).
        c.put("ermSignatureDate", resolveErmSignatureDate(app));
        // The main-agreement date from the per-section map, else the signing
        // date, else today (a preview before submit).
        c.put("signatureDate", resolveSectionSignatureDate(app, "main-agreement"));

        // The faint "IP: … · Signed: …" line; "(pending)" before submit.
        c.put("finalSigningIp", resolveFinalSigningIp(app));
        c.put("finalSigningAt", resolveFinalSigningAt(app));

        // No storage key is read here, so every signature slot starts blank,
        // as the console's signatureImage(null, null) gives it; the
        // renderer's override fills them from the website's storage.
        Object consultantSig = "";
        Object ermSig = "";
        c.put("signatureImage", consultantSig);
        c.put("finalSignatureImage", "");
        c.put("ermSignatureImage", ermSig);

        // Each appendix block is signed, dated and carries the email only
        // when the participant affirmed that appendix; otherwise all blank.
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
            c.put("appendix" + n + "SignatureDate",
                    on ? resolveSectionSignatureDate(app, "appendix" + n) : "");
            c.put("appendix" + n + "ErmSignatureDate", on ? ermSigDate : "");
            c.put("appendix" + n + "Email", on ? consultantEmail : "");
        }

        return c;
    }

    /**
     * The console's nonEditableDisplayValues: the fixed values for the
     * on-screen clauses, with buildContext's sources and formats. ermEmail is
     * blank here too; the renderer puts the owner's website email.
     */
    public Map<String, String> nonEditableDisplayValues(WebAgreement app) {
        Map<String, String> v = new HashMap<>();
        Function<String, String> nz = s -> s == null ? "" : s;
        v.put("effectiveDate", app.getEffectiveDate() == null
                ? "" : app.getEffectiveDate().format(US_SHORT_DATE_FMT));
        v.put("workAuthorizationCategory", effectiveWorkAuth(app));
        v.put("ratePeriod1", nz.apply(app.getRatePeriod1()));
        v.put("rateAmount1", nz.apply(app.getRateAmount1()));
        v.put("ratePeriod2", nz.apply(app.getRatePeriod2()));
        v.put("rateAmount2", nz.apply(app.getRateAmount2()));
        v.put("phase2DeliverablePeriod", nz.apply(app.getPhase2DeliverablePeriod()));
        v.put("ermName", nz.apply(app.getErmName()));
        v.put("ermTitle", nz.apply(app.getErmTitle()));
        v.put("ermEmail", "");
        v.put("signatureDate", resolveSectionSignatureDate(app, "main-agreement"));
        v.put("ermSignatureDate", resolveErmSignatureDate(app));
        v.put("finalSigningIp", resolveFinalSigningIp(app));
        v.put("finalSigningAt", resolveFinalSigningAt(app));
        return v;
    }

    /** The stored signing date (MM-DD-YYYY), else today's (a preview before submit). */
    private static String resolveSignatureDate(WebAgreement app) {
        LocalDateTime stamped = app.getSignatureDate();
        if (stamped != null) {
            return stamped.toLocalDate().format(US_SHORT_DATE_FMT);
        }
        return LocalDate.now().format(US_SHORT_DATE_FMT);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper SECTION_DATES_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * One section's signing date ("main-agreement", "appendix1".."appendix5")
     * from the per-section map, else {@link #resolveSignatureDate}.
     */
    private static String resolveSectionSignatureDate(WebAgreement app, String sectionKey) {
        String json = app.getSectionSignatureDates();
        if (json != null && !json.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = SECTION_DATES_MAPPER.readTree(json);
                com.fasterxml.jackson.databind.JsonNode v = node == null ? null : node.get(sectionKey);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    return LocalDateTime.parse(v.asText()).toLocalDate().format(US_SHORT_DATE_FMT);
                }
            } catch (Exception ignored) { /* fall through to the global date */ }
        }
        return resolveSignatureDate(app);
    }

    /** The ERM's countersign date, or "" until there is one (no today fallback). */
    private static String resolveErmSignatureDate(WebAgreement app) {
        LocalDateTime stamped = app.getErmSignatureDate();
        if (stamped != null) {
            return stamped.toLocalDate().format(US_SHORT_DATE_FMT);
        }
        return "";
    }

    /** First + Middle? + Last, else the legacy consultantName. */
    private static String composeFullName(WebAgreement app) {
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

    /** The work authorization to print: the free text when "Others", else the category. */
    private static String effectiveWorkAuth(WebAgreement app) {
        String cat = trimToEmpty(app.getWorkAuthorizationCategory());
        if ("Others".equalsIgnoreCase(cat)) {
            String other = trimToEmpty(app.getWorkAuthorizationOther());
            return other.isEmpty() ? cat : other;
        }
        return cat;
    }

    /**
     * The residence address ("Line1[, Line2], City, ST ZIP"). The website
     * has no legacy residence block, so there is nothing to fall back to.
     */
    private static String assembledAddress(WebAgreement app) {
        return assembleUsAddress(
                app.getAddressLine1(), app.getAddressLine2(), app.getAddressCity(),
                app.getAddressState(), app.getAddressZip(), null);
    }

    /** "Line1[, Line2], City, ST ZIP" from the parts, else the legacy value when no part is set. */
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
     * One cheque field ("number" or "date") from the cheques list, in index
     * order (the website's own parse, which only reads the entries' values),
     * comma-joined and capped at the declared count, else the legacy column.
     */
    private static String deriveChequeField(WebAgreement app, String key, String legacy) {
        String json = app.getCheques();
        if (json != null && !json.isBlank()) {
            // Entries past the declared count (left from a higher count) never print.
            int count = parseChequeCountSafe(app.getSecurityCheckCount());
            StringBuilder sb = new StringBuilder();
            for (WebAgreementRules.ChequeEntry e : WebAgreementRules.parseCheques(app)) {
                if (count > 0 && e.index() >= count) continue; // stale over-click
                String v = "date".equals(key) ? e.date() : e.number();
                if (v == null || v.isBlank()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(v);
            }
            if (sb.length() > 0) return sb.toString();
        }
        return legacy == null ? "" : legacy;
    }

    /** The declared cheque count, 0..50. */
    private static int parseChequeCountSafe(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int n = Integer.parseInt(raw.trim());
            return n < 0 ? 0 : Math.min(50, n);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * One portal field ("platform" or "username") from the portal entries,
     * comma-joined in row order, else the legacy value.
     */
    private static String derivePortalField(WebAgreement app, String key, String legacy) {
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
                        // Only rows with both, so the two lists stay aligned.
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

    /** The background-check current address: the residence when "same", else its own parts. */
    private static String assembledCurrentAddress(WebAgreement app) {
        if (Boolean.TRUE.equals(app.getBgCurrentSameAsResidence())) {
            return assembledAddress(app);
        }
        return assembleUsAddress(
                app.getBgCurrentAddressLine1(), app.getBgCurrentAddressLine2(),
                app.getBgCurrentAddressCity(), app.getBgCurrentAddressState(),
                app.getBgCurrentAddressZip(), app.getBgCurrentAddress());
    }

    private static String resolveFinalSigningIp(WebAgreement app) {
        String ip = app.getFinalSigningIp();
        if (ip != null && !ip.isBlank()) return ip.trim();
        return "(pending)";
    }

    /** The signing time's format, the same as the certificate's. */
    private static final DateTimeFormatter SIGNING_AT_UTC_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    /** The final signing time, or "(pending)" before submit. */
    private static String resolveFinalSigningAt(WebAgreement app) {
        LocalDateTime stamped = app.getFinalSignedAt();
        if (stamped != null) {
            return stamped.atZone(java.time.ZoneOffset.UTC)
                    .format(SIGNING_AT_UTC_FMT);
        }
        return "(pending)";
    }

    // ── Signature images ─────────────────────────────────────────────

    // Every signature fits a 190x76 px box with a 96 DPI pHYs chunk, so it
    // prints about 5 cm x 2 cm whatever the source's size and density.
    private static final int SIGNATURE_BOX_WIDTH_PX = 190;
    private static final int SIGNATURE_BOX_HEIGHT_PX = 76;
    /** 96 DPI expressed as PNG pHYs pixels-per-meter: 96 / 0.0254 ≈ 3780. */
    private static final int SIGNATURE_PHYS_PPM = 3780;

    /**
     * A {@code data:image/...;base64,...} signature, sized like every
     * other, as an image for the stamper; "" (a blank box) when malformed.
     * Package-private for the override lambdas.
     */
    Object buildImageFromDataUrl(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) return "";
        int comma = dataUrl.indexOf(',');
        if (comma < 0 || !dataUrl.startsWith("data:image/")) {
            log.warn("Web agreement render discarded a malformed signature data URL");
            return "";
        }
        try {
            byte[] bytes = java.util.Base64.getDecoder()
                    .decode(dataUrl.substring(comma + 1));
            byte[] normalized = normalizeSignaturePng(bytes);
            return new Image(normalized);
        } catch (Exception e) {
            log.warn("Web agreement render couldn't decode a signature data URL: {}",
                    e.getMessage());
            return "";
        }
    }

    /**
     * Fits the image inside the signature box (aspect kept, never
     * upscaled) and re-encodes it with the 96 DPI density. An undecodable
     * image becomes a 1x1 transparent PNG, so the layout is the same as no
     * signature; an encoder failure falls back to a plain PNG.
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
            // Never the raw bytes: a large image would overflow its row.
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
            // Without the pHYs chunk the image is still boxed, just a little larger.
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

    /** 1x1 transparent PNG bytes, made once. */
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
            // Zero bytes print as an empty placeholder.
            log.error("Blank signature PNG encode failed: {}", e.getMessage());
            return new byte[0];
        }
    }

    /** A PNG carrying the {@link #SIGNATURE_PHYS_PPM} (96 DPI) pHYs density chunk. */
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

    // ── The template ─────────────────────────────────────────────────

    /** The template after the console's surgery, made once (the same for everyone). */
    private volatile byte[] preprocessedTemplateBytes;

    /** The template with the console's layout surgery, cached after the first read. */
    private byte[] preprocessedTemplate() throws IOException {
        byte[] cached = preprocessedTemplateBytes;
        if (cached != null) return cached;
        synchronized (this) {
            cached = preprocessedTemplateBytes;
            if (cached != null) return cached;
            ClassPathResource template =
                    new ClassPathResource(TEMPLATE, WebAgreementDocumentEngine.class.getClassLoader());
            try (InputStream in = template.getInputStream()) {
                cached = applyTemplateSurgery(in.readAllBytes());
            }
            preprocessedTemplateBytes = cached;
            log.info("Web agreement template preprocessed and cached ({} KB)",
                    cached.length / 1024);
            return cached;
        }
    }

    /**
     * The console's surgery on {@code word/document.xml}, in its order: drop
     * the orphan empty paragraphs before page breaks, raise the bottom margin
     * clear of the letterhead, add the faint IP line under every Date / Email
     * line, then keep every signature block on one page. Every other entry is
     * copied as is.
     */
    static byte[] applyTemplateSurgery(byte[] templateBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(templateBytes.length);
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(new ByteArrayInputStream(templateBytes));
             java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zin.getNextEntry()) != null) {
                java.util.zip.ZipEntry copy = new java.util.zip.ZipEntry(entry.getName());
                zout.putNextEntry(copy);
                if ("word/document.xml".equals(entry.getName())) {
                    ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
                    int n;
                    while ((n = zin.read(buf)) > 0) entryBytes.write(buf, 0, n);
                    String xml = entryBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
                    xml = stripOrphanEmptyParagraphsBeforePageBreaks(xml);
                    xml = enlargeBottomMargin(xml);
                    // Before keepSignatureBlocksTogether, so the IP line joins its block.
                    xml = insertSigningIpLine(xml);
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

    /** The body's bottom margin (1.5") that clears the letterhead's footer art. */
    static final int FOOTER_SAFE_BOTTOM_MARGIN_TWIPS = 2160;

    /** Raises every section's bottom margin to {@link #FOOTER_SAFE_BOTTOM_MARGIN_TWIPS}. */
    static String enlargeBottomMargin(String xml) {
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

    /** The faint (gray 7pt) "IP: … · Signed: …" paragraph. */
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

    /** Adds {@link #SIGNING_IP_PARAGRAPH} after every "Date / Email: …" paragraph. */
    static String insertSigningIpLine(String xml) {
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
     * Folds every signature table into one row that can't split across
     * pages (falling back to row-level cantSplit when a table has an
     * unexpected shape).
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
                    // The appendix blocks' ${appendixNSignature} / ${appendixNErmSignature}.
                    || table.contains("Signature}")) {
                String consolidated = consolidateSignatureTable(table);
                if (consolidated != null) {
                    sb.append(consolidated);
                    tablesConsolidated++;
                } else {
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
     * A two-column signature table as one cantSplit row whose two cells
     * stack each column's paragraphs. Null on any unexpected shape.
     */
    static String consolidateSignatureTable(String tableXml) {
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

        // Every row must have exactly two cells.
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

        // The first row's cell properties, without vAlign (stacked lines flow from the top).
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

    /** A cell's tcPr without {@code <w:vAlign .../>}. */
    private static String stripCellVAlign(String tcPr) {
        return tcPr.replaceAll("<w:vAlign\\b[^/]*/>", "");
    }

    /** The fallback: {@code <w:cantSplit/>} in every row's trPr. */
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

    /**
     * Drops the empty paragraph between a table's end and a page-break
     * paragraph, which otherwise spills onto a page of its own.
     */
    static String stripOrphanEmptyParagraphsBeforePageBreaks(String xml) {
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

    /**
     * The context stamped into the template (the console's docx-stamper
     * setup: MapAccessor so ${key} reads the map, images, null as "", and
     * unresolved expressions as "").
     */
    private Path fillTemplate(Map<String, Object> ctx) throws IOException {
        Path out = Files.createTempFile("web-agreement-", ".docx");
        OfficeStamperConfiguration cfg = OfficeStamperConfigurations.standard()
                .setEvaluationContextConfigurer(
                        spelCtx -> spelCtx.addPropertyAccessor(new MapAccessor()))
                .addResolver(Resolvers.image())
                .addResolver(Resolvers.nullToEmpty())
                .replaceUnresolvedExpressions(true)
                .unresolvedExpressionsDefaultValue("")
                .setFailOnUnresolvedExpression(false);
        StreamStamper<?> stamper = OfficeStampers.docxStamper(cfg);
        byte[] templateBytes = preprocessedTemplate();
        try (InputStream in = new ByteArrayInputStream(templateBytes);
             OutputStream os = Files.newOutputStream(out)) {
            stamper.stamp(in, ctx, os);
        }
        return out;
    }

    // ── LibreOffice, through the website's own slot ──────────────────

    /** A render's work, run once it is admitted. */
    @FunctionalInterface
    interface RenderWork<T> {
        T run() throws Exception;
    }

    /**
     * Runs {@code work} as one of at most {@link #MAX_IN_PROGRESS} renders,
     * or answers busy at once, before any of it runs. The admission is
     * given back in its own finally. Package-private for the tests.
     */
    <T> T admitted(RenderWork<T> work) throws Exception {
        if (!admission.tryAcquire()) {
            log.warn("Web agreement render refused: {} renders already in progress", MAX_IN_PROGRESS);
            throw new StorageUnavailableException(BUSY_MESSAGE, null);
        }
        try {
            return work.run();
        } finally {
            admission.release();
        }
    }

    /**
     * DOCX → PDF through the slot (see the class doc). The profile is kept
     * after a good conversion, so the next one reuses its LibreOffice; a
     * failed one wipes it. The slot is given back in its own finally, so
     * nothing in the cleanup can keep it. Package-private for the tests.
     */
    Path convertToPdf(Path docx) throws IOException, InterruptedException {
        Path outDir = docx.getParent();
        acquireSlot();
        try {
            boolean healthy = false;
            try {
                Files.createDirectories(profileDir);
                Path pdf = runLibreOffice(profileDir, docx, outDir);
                healthy = true;
                return pdf;
            } finally {
                if (!healthy) recycleProfileSlot(profileDir);
            }
        } finally {
            slot.release();
        }
    }

    /**
     * Takes the slot, or throws busy: at once when {@link #MAX_WAITING}
     * requests are already waiting, else after {@link #slotWait}. The line
     * is limited here; admission limits the renders in progress.
     */
    private void acquireSlot() {
        int ahead = waiting.getAndIncrement();
        try {
            if (ahead >= MAX_WAITING) {
                log.warn("Web agreement render refused: {} requests already waiting for the document slot", ahead);
                throw new StorageUnavailableException(BUSY_MESSAGE, null);
            }
            boolean acquired;
            try {
                acquired = slot.tryAcquire(slotWait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StorageUnavailableException(BUSY_MESSAGE, e);
            }
            if (!acquired) {
                log.warn("Web agreement render gave up after waiting {}s for the document slot",
                        slotWait.toSeconds());
                throw new StorageUnavailableException(BUSY_MESSAGE, null);
            }
        } finally {
            waiting.decrementAndGet();
        }
    }

    /**
     * The console's LibreOffice call, on this profile: output drained while
     * it runs (a full pipe would hang it), killed after 60 seconds, and a
     * missing PDF is a failure. Package-private so tests can stand in for
     * LibreOffice.
     */
    Path runLibreOffice(Path profile, Path docx, Path outDir) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(
                "libreoffice",
                "-env:UserInstallation=" + profile.toUri(),
                "--headless",
                "--convert-to", "pdf",
                "--outdir", outDir.toString(),
                docx.toString())
                .redirectErrorStream(true)
                .start();
        StringBuilder sink = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (sink.length() < 8192) sink.append(new String(buf, 0, n));
                }
            } catch (IOException ignored) { /* process died; nothing to read */ }
        });
        drain.setDaemon(true);
        drain.start();

        if (!p.waitFor(LIBREOFFICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            drain.join(1_000);
            throw new IOException(
                    "LibreOffice conversion timed out after "
                            + LIBREOFFICE_TIMEOUT_SECONDS + "s. Output: " + sink);
        }
        drain.join(2_000);
        if (p.exitValue() != 0) {
            throw new IOException(
                    "LibreOffice failed (exit " + p.exitValue() + "): " + sink);
        }
        String pdfName = docx.getFileName().toString()
                .replaceFirst("\\.docx$", ".pdf");
        Path pdf = outDir.resolve(pdfName);
        if (!Files.exists(pdf)) {
            // Exit 0 with no file: LibreOffice bailed early. Carry its output.
            throw new IOException(
                    "Expected PDF output missing: " + pdf + ". Output: " + sink);
        }
        return pdf;
    }

    /**
     * After a failed conversion: kill whatever LibreOffice still runs on
     * this profile (matched on its path, which only this slot uses) and
     * wipe the profile, so a stale lock can't fail the next one. Never
     * throws. Package-private so tests can make it fail.
     */
    void recycleProfileSlot(Path profile) {
        String plainPath = profile.toString();
        try {
            ProcessHandle.allProcesses()
                    .filter(h -> h.info().commandLine()
                            .map(cmd -> cmd.contains(plainPath))
                            .orElse(false))
                    .forEach(h -> {
                        log.warn("Recycling web agreement slot {}: killing LibreOffice pid {}",
                                profile, h.pid());
                        h.destroyForcibly();
                    });
        } catch (Exception e) {
            log.warn("Couldn't sweep LibreOffice for web agreement slot {}: {}",
                    profile, e.getMessage());
        }
        safeDeleteRecursively(profile);
        try {
            Files.createDirectories(profile);
        } catch (Exception e) {
            log.warn("Couldn't recreate web agreement slot {}: {}", profile, e.getMessage());
        }
    }

    /**
     * Best-effort recursive delete. Never throws: a walk that meets a vanished
     * or unreadable entry fails with an unchecked exception (the console's
     * copy only catches IOException, so that one escapes there), which is
     * caught here too, and a second pass usually finishes the job.
     */
    static void safeDeleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException | RuntimeException ignored) { /* best effort */ }
                });
                return;
            } catch (IOException | RuntimeException e) {
                if (attempt == 2) {
                    log.warn("Couldn't clean up web agreement LibreOffice profile {}: {}", dir, e.getMessage());
                }
            }
        }
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
