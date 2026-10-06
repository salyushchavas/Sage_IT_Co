package com.spire.backend.service;

import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.beans.PropertyDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a website agreement with the console's own template and renderer
 * ({@link AgreementDocumentService}, read-only), so the wording is the same
 * document. It does that through an adapter: a TRANSIENT, never-saved
 * {@link ConsultantApplication} copied from the {@link WebAgreement}.
 *
 * Rules the adapter keeps (the console's renderer reads the console's
 * storage and tables through these fields):
 * <ul>
 *   <li>{@code id} stays null and {@code ownerErmId} stays null, so nothing
 *       resolves to a console row or console user;</li>
 *   <li>{@code applicationId} is {@code "web-" + applicationId};</li>
 *   <li>no *S3Key is ever set (they would be read from the console's
 *       storage). Uploaded documents are appended here instead, read through
 *       the website's storage, in the console's order and with its divider
 *       page; the signatures are injected through the context override;</li>
 *   <li>{@code ermEmail} is the owner ERM's website email.</li>
 * </ul>
 * Never calls generateAgreementPdf, storeConsultantVersionPdf or anything in
 * ConsultantVersionService: nothing rendered here is stored.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebAgreementRenderer {

    /** Properties never copied onto the transient object. */
    private static final Set<String> NOT_COPIED = notCopied();

    private final AgreementDocumentService agreementDocumentService;
    private final WebAgreementFileService fileService;
    private final UserRepository userRepository;

    /** A render failed (LibreOffice missing or crashed, a bad template…). Carries the cause. */
    public static class RenderException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RenderException(Throwable cause) {
            super(cause == null ? "Render failed" : cause.getMessage(), cause);
        }
    }

    /**
     * The short, header-safe reason for an {@code X-Preview-Error} header
     * (the console's previewFailure): exception class + message, at most 300
     * characters, single-line ASCII. Unwraps {@link RenderException}.
     */
    public static String previewErrorReason(Throwable e) {
        Throwable t = (e instanceof RenderException && e.getCause() != null) ? e.getCause() : e;
        if (t == null) return "Unknown";
        String reason = t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + t.getMessage());
        if (reason.length() > 300) reason = reason.substring(0, 300) + "…";
        // Header values must be single-line ISO-8859-1.
        return reason.replaceAll("[\\r\\n]+", " ").replaceAll("[^\\x20-\\x7E]", "?");
    }

    // ── The adapter ──────────────────────────────────────────────────

    /**
     * The never-saved console object the renderer reads. Same-named
     * properties are copied; see the class doc for what is left out.
     */
    public ConsultantApplication toTransient(WebAgreement agreement) {
        ConsultantApplication t = new ConsultantApplication();
        BeanUtils.copyProperties(agreement, t, NOT_COPIED.toArray(new String[0]));
        t.setId(null);
        t.setOwnerErmId(null);
        t.setApplicationId("web-" + agreement.getApplicationId());
        // Only the numbers and dates are rendered; the file pointers stay behind.
        t.setCheques(chequesWithoutFiles(agreement));
        return t;
    }

    /**
     * The non-editable values for the on-screen clauses: the console's own
     * set, with ermEmail = the owner ERM's website email (ermName / ermTitle
     * stay blank until a countersign).
     */
    public Map<String, String> contentValues(WebAgreement agreement) {
        Map<String, String> values = new HashMap<>(
                agreementDocumentService.nonEditableDisplayValues(toTransient(agreement)));
        String ermEmail = ownerErmEmail(agreement);
        if (ermEmail != null) values.put("ermEmail", ermEmail);
        return values;
    }

    /**
     * The agreement PDF with the uploaded documents appended. The participant
     * preview ({@code forErm=false}) uses the console's consultant preview
     * overrides (draft primary signature from {@code primaryDataUrl}; final
     * and ERM signatures blank); the ERM preview uses the console's ERM
     * overrides (ERM signature blank). Throws {@link RenderException}.
     */
    public byte[] renderPdf(WebAgreement agreement, boolean forErm, String primaryDataUrl) {
        ConsultantApplication t = toTransient(agreement);
        String primary = fileService.signatureDataUrl(agreement.getSignatureS3Key());
        String closing = fileService.signatureDataUrl(agreement.getFinalSignatureS3Key());
        String ermEmail = ownerErmEmail(agreement);
        boolean[] appendixAffirmed = {
                Boolean.TRUE.equals(agreement.getAffirmedAppendix1()),
                Boolean.TRUE.equals(agreement.getAffirmedAppendix2()),
                Boolean.TRUE.equals(agreement.getAffirmedAppendix3()),
                Boolean.TRUE.equals(agreement.getAffirmedAppendix4()),
                Boolean.TRUE.equals(agreement.getAffirmedAppendix5()),
        };
        AgreementDocumentService.ContextOverrides consoleOverrides = forErm
                ? AgreementDocumentService.ermPreviewOverrides()
                : AgreementDocumentService.consultantPreviewOverrides(primaryDataUrl);
        AgreementDocumentService.ContextOverrides overrides = (ctx, svc) -> {
            // The stored signatures, in the same slots buildContext fills from
            // the console's own keys (an appendix block only when affirmed).
            Object primaryImage = primary == null ? "" : svc.buildImageFromDataUrl(primary);
            ctx.put("signatureImage", primaryImage);
            ctx.put("finalSignatureImage", closing == null ? "" : svc.buildImageFromDataUrl(closing));
            for (int i = 0; i < appendixAffirmed.length; i++) {
                ctx.put("appendix" + (i + 1) + "Signature", appendixAffirmed[i] ? primaryImage : "");
            }
            consoleOverrides.apply(ctx, svc);
            if (ermEmail != null) ctx.put("ermEmail", ermEmail);
        };
        try {
            byte[] body = agreementDocumentService.renderPdfBytes(t, overrides);
            return appendAttachments(body, agreement);
        } catch (RenderException e) {
            throw e;
        } catch (Exception e) {
            throw new RenderException(e);
        }
    }

    /**
     * The participant's review view: the preview PDF rasterised into
     * watermarked PNG pages (CONFIDENTIAL • viewer email • UTC time).
     * Throws {@link RenderException}.
     */
    public List<byte[]> renderPageImages(WebAgreement agreement, String primaryDataUrl, String viewerEmail) {
        byte[] pdf = renderPdf(agreement, false, primaryDataUrl);
        try {
            return agreementDocumentService.renderWatermarkedPageImages(pdf, viewerEmail, true);
        } catch (Exception e) {
            throw new RenderException(e);
        }
    }

    /** The owner ERM's website email, or null when the owner can't be found. */
    public String ownerErmEmail(WebAgreement agreement) {
        if (agreement.getOwnerUserId() == null) return null;
        return userRepository.findById(agreement.getOwnerUserId())
                .map(User::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .orElse(null);
    }

    // ── Appended documents (the console's appendAttachments) ─────────

    /**
     * Appends the uploaded documents after the body: Work Authorization →
     * Offer Letter → Driver's License (State ID for a legacy idType row) →
     * State ID → SSN, each behind a divider page. Never cheques. A single bad
     * attachment is skipped (logged), never failing the render.
     */
    byte[] appendAttachments(byte[] body, WebAgreement a) {
        boolean hasWorkAuth = WebAgreementRules.nonBlank(a.getWorkAuthDocS3Key());
        boolean hasOffer = WebAgreementRules.nonBlank(a.getOfferLetterS3Key());
        boolean hasDl = WebAgreementRules.nonBlank(a.getDlDocS3Key());
        boolean hasStateId = WebAgreementRules.nonBlank(a.getStateIdDocS3Key());
        boolean hasSsn = WebAgreementRules.nonBlank(a.getSsnDocS3Key());
        if (!hasWorkAuth && !hasOffer && !hasDl && !hasStateId && !hasSsn) return body;
        try (PDDocument doc = Loader.loadPDF(body)) {
            if (hasWorkAuth) {
                appendOneAttachment(doc, "Attachment — Work Authorization Document",
                        a.getWorkAuthDocS3Key(), a.getWorkAuthDocContentType());
            }
            if (hasOffer) {
                appendOneAttachment(doc, "Attachment — Offer Letter",
                        a.getOfferLetterS3Key(), a.getOfferLetterContentType());
            }
            if (hasDl) {
                boolean legacyStateId = !hasStateId && "STATE_ID".equals(a.getIdType());
                appendOneAttachment(doc,
                        legacyStateId ? "Attachment — State ID" : "Attachment — Driver's License",
                        a.getDlDocS3Key(), a.getDlDocContentType());
            }
            if (hasStateId) {
                appendOneAttachment(doc, "Attachment — State ID",
                        a.getStateIdDocS3Key(), a.getStateIdDocContentType());
            }
            if (hasSsn) {
                appendOneAttachment(doc, "Attachment — SSN Document",
                        a.getSsnDocS3Key(), a.getSsnDocContentType());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to append attachments for web agreement {}: {}",
                    a.getApplicationId(), e.getMessage());
            return body; // never block the agreement PDF on an attachment hiccup
        }
    }

    /** Append one upload: prepare its content first, then a divider + the content. */
    private void appendOneAttachment(PDDocument doc, String label, String stored, String contentType) {
        try {
            byte[] bytes = fileService.readBytes(stored);
            if (bytes == null || bytes.length == 0) return;
            boolean isPdf = "application/pdf".equalsIgnoreCase(contentType)
                    || WebAgreementRules.hasPdfSignature(bytes);
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
            log.warn("Skipping attachment '{}': {}", label, e.getMessage());
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

    // ── Helpers ──────────────────────────────────────────────────────

    /** The cheques JSON with only index/number/date (no stored file values). */
    private static String chequesWithoutFiles(WebAgreement agreement) {
        if (agreement.getCheques() == null || agreement.getCheques().isBlank()) return null;
        List<WebAgreementRules.ChequeEntry> stripped = new ArrayList<>();
        for (WebAgreementRules.ChequeEntry e : WebAgreementRules.parseCheques(agreement)) {
            stripped.add(new WebAgreementRules.ChequeEntry(e.index(), e.number(), e.date(), "", "", ""));
        }
        return WebAgreementRules.serialiseCheques(stripped);
    }

    /**
     * id, applicationId and the owner are set by hand; every *S3Key / s3Key
     * property is left out (the renderer would read it from the console's
     * storage); deletedBy differs in type.
     */
    private static Set<String> notCopied() {
        Set<String> names = new LinkedHashSet<>(List.of("id", "applicationId", "ownerErmId", "deletedBy"));
        for (PropertyDescriptor pd : BeanUtils.getPropertyDescriptors(WebAgreement.class)) {
            String n = pd.getName();
            if (n.endsWith("S3Key") || n.equals("s3Key")) names.add(n);
        }
        return Set.copyOf(names);
    }
}
