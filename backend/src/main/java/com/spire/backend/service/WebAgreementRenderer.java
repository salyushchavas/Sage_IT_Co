package com.spire.backend.service;

import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.exception.StorageUnavailableException;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a website agreement with the console's template through the
 * website's own copy of the console's renderer
 * ({@link WebAgreementDocumentEngine}), which reads the {@link WebAgreement}
 * itself, so the wording is the same document and nothing of the console's
 * is called or used.
 *
 * What the engine leaves to this class, all through the website's storage:
 * <ul>
 *   <li>the stored signatures, injected through the context override;</li>
 *   <li>the uploaded documents, appended after the body in the console's
 *       order and with its divider page;</li>
 *   <li>{@code ermEmail}: always the owner ERM's website email, blank when
 *       the owner can't be found, never the console's
 *       {@code agreement-erm.email}.</li>
 * </ul>
 *
 * Three layouts, the console's: the participant preview (its consultant
 * preview overrides), the ERM preview (its {@code ermPreviewOverrides}:
 * main ERM signature blank) and the final PDF (its {@code renderPdfBytes(app,
 * null)}: every signature). Each first fills the signature slots the way
 * the console's buildContext would from the stored signatures, then applies
 * the console's override, so an affirmed appendix carries the ERM signature
 * whenever one is stored.
 *
 * Render permit: the engine's own LibreOffice slot, shared with nothing of
 * the console's. One website render at a time; five renders already in
 * progress, three already waiting for the slot, or a wait longer than 15
 * seconds, is a 503 ("The document
 * service is busy…") with nothing changed. The signatures and the owner's
 * email are read inside the override, so a render refused at admission
 * reads nothing. Rasterising pages (PDFBox only) needs no slot.
 *
 * Never calls generateAgreementPdf, storeConsultantVersionPdf,
 * readStoredPdfBytes or anything in ConsultantVersionService: nothing is
 * read from or stored in the console's storage here. Callers store what
 * they need through {@link WebAgreementFileService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebAgreementRenderer {

    /** The 503 answer when the engine's slot is busy. */
    static final String BUSY_MESSAGE = WebAgreementDocumentEngine.BUSY_MESSAGE;

    // PDFBox 3 standard fonts for the attachment dividers (our own copy;
    // the console's CertFonts is not used).
    private static final PDType1Font HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font HELVETICA_BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private final WebAgreementDocumentEngine engine;
    private final WebAgreementFileService fileService;
    private final UserRepository userRepository;

    /** The console's three signature layouts (see the class doc). */
    private enum Mode { PARTICIPANT_PREVIEW, ERM_PREVIEW, FINAL }

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

    // ── Values and documents ─────────────────────────────────────────

    /**
     * The non-editable values for the on-screen clauses: the console's own
     * set, with ermEmail = the owner ERM's website email, blank when there is
     * none (ermName / ermTitle stay blank until a countersign).
     */
    public Map<String, String> contentValues(WebAgreement agreement) {
        Map<String, String> values = new HashMap<>(engine.nonEditableDisplayValues(agreement));
        values.put("ermEmail", websiteErmEmail(agreement));
        return values;
    }

    /**
     * The agreement PDF with the uploaded documents appended. The participant
     * preview ({@code forErm=false}) uses the console's consultant preview
     * overrides (draft primary signature from {@code primaryDataUrl}; final
     * and ERM signatures blank); {@code forErm=true} is
     * {@link #renderErmPreviewPdf}. Throws {@link RenderException}, or
     * {@link StorageUnavailableException} (503) when the engine's slot is
     * busy.
     */
    public byte[] renderPdf(WebAgreement agreement, boolean forErm, String primaryDataUrl) {
        if (forErm) return renderErmPreviewPdf(agreement);
        return render(agreement, Mode.PARTICIPANT_PREVIEW, primaryDataUrl);
    }

    /**
     * The console's ERM preview ({@code ermPreviewOverrides}): the
     * participant's signatures, the main ERM signature blank, and each
     * affirmed appendix's ERM block carrying the stored ERM signature when
     * one exists (what the console's buildContext puts there; in normal
     * flow there is none yet). The body of a verified version and the live
     * fallback the approvers see. Throws like {@link #renderPdf}.
     */
    public byte[] renderErmPreviewPdf(WebAgreement agreement) {
        return render(agreement, Mode.ERM_PREVIEW, null);
    }

    /**
     * The executed agreement, the console's {@code renderPdfBytes(app,
     * null)}: every participant signature, the stored ERM signature on the
     * main block and on each affirmed appendix, ermName / ermTitle / the ERM
     * date from the agreement, uploads appended. No certificate.
     * Throws like {@link #renderPdf}.
     */
    public byte[] renderFinalPdf(WebAgreement agreement) {
        return render(agreement, Mode.FINAL, null);
    }

    private byte[] render(WebAgreement agreement, Mode mode, String primaryDataUrl) {
        boolean[] appendixAffirmed = {
                Boolean.TRUE.equals(agreement.getAffirmedAppendix1()),
                Boolean.TRUE.equals(agreement.getAffirmedAppendix2()),
                Boolean.TRUE.equals(agreement.getAffirmedAppendix3()),
                Boolean.TRUE.equals(agreement.getAffirmedAppendix4()),
                Boolean.TRUE.equals(agreement.getAffirmedAppendix5()),
        };
        WebAgreementDocumentEngine.ContextOverrides consoleOverrides = switch (mode) {
            case PARTICIPANT_PREVIEW -> WebAgreementDocumentEngine.consultantPreviewOverrides(primaryDataUrl);
            case ERM_PREVIEW -> WebAgreementDocumentEngine.ermPreviewOverrides();
            case FINAL -> null;
        };
        WebAgreementDocumentEngine.ContextOverrides overrides = (ctx, svc) -> {
            // Read here, once the engine admitted the render: a refused one reads nothing.
            String primary = fileService.signatureDataUrl(agreement.getSignatureS3Key());
            String closing = fileService.signatureDataUrl(agreement.getFinalSignatureS3Key());
            String erm = fileService.signatureDataUrl(agreement.getErmSignatureS3Key());
            // The stored signatures, in the same slots buildContext fills from
            // the console's own keys (an appendix block only when affirmed).
            Object primaryImage = primary == null ? "" : svc.buildImageFromDataUrl(primary);
            Object ermImage = erm == null ? "" : svc.buildImageFromDataUrl(erm);
            ctx.put("signatureImage", primaryImage);
            ctx.put("finalSignatureImage", closing == null ? "" : svc.buildImageFromDataUrl(closing));
            ctx.put("ermSignatureImage", ermImage);
            for (int i = 0; i < appendixAffirmed.length; i++) {
                ctx.put("appendix" + (i + 1) + "Signature", appendixAffirmed[i] ? primaryImage : "");
                ctx.put("appendix" + (i + 1) + "ErmSignature", appendixAffirmed[i] ? ermImage : "");
            }
            if (consoleOverrides != null) consoleOverrides.apply(ctx, svc);
            ctx.put("ermEmail", websiteErmEmail(agreement));
        };
        byte[] body = renderBody(agreement, overrides);
        return appendAttachments(body, agreement);
    }

    /**
     * The engine's renderPdfBytes. A busy slot stays a
     * {@link StorageUnavailableException} (503); any other render failure is
     * a {@link RenderException}.
     */
    private byte[] renderBody(WebAgreement agreement, WebAgreementDocumentEngine.ContextOverrides overrides) {
        try {
            return engine.renderPdfBytes(agreement, overrides);
        } catch (StorageUnavailableException busy) {
            throw busy;
        } catch (Exception e) {
            throw new RenderException(e);
        }
    }

    /**
     * The blank template PDF ("View full agreement"), cached by the engine
     * after the first render. Throws like {@link #renderPdf}.
     */
    public byte[] renderBlankTemplatePdf() {
        try {
            return engine.getBlankPreviewPdfBytes();
        } catch (StorageUnavailableException busy) {
            throw busy;
        } catch (Exception e) {
            throw new RenderException(e);
        }
    }

    /**
     * The participant's review view: the preview PDF rasterised into
     * watermarked PNG pages (CONFIDENTIAL • viewer email • UTC time).
     * Throws like {@link #renderPdf}.
     */
    public List<byte[]> renderPageImages(WebAgreement agreement, String primaryDataUrl, String viewerEmail) {
        byte[] pdf = renderPdf(agreement, false, primaryDataUrl);
        try {
            return engine.renderWatermarkedPageImages(pdf, viewerEmail, true);
        } catch (Exception e) {
            throw new RenderException(e);
        }
    }

    /**
     * The approvers' view of a PDF: one clean PNG per page at 110 DPI, no
     * watermark (the console's {@code renderWatermarkedPageImages(pdf,
     * viewerEmail, false)}). PDFBox only, so no slot. Throws
     * {@link RenderException}.
     */
    public List<byte[]> renderCleanPageImages(byte[] pdfBytes) {
        return renderCleanPageImages(pdfBytes, null);
    }

    /** As {@link #renderCleanPageImages(byte[])}; the viewer's email only goes in the log line. */
    public List<byte[]> renderCleanPageImages(byte[] pdfBytes, String viewerEmail) {
        try {
            return engine.renderWatermarkedPageImages(pdfBytes, viewerEmail, false);
        } catch (Exception e) {
            throw new RenderException(e);
        }
    }

    /** The ermEmail every document and clause view prints: the owner's website email, else blank. */
    String websiteErmEmail(WebAgreement agreement) {
        String email = ownerErmEmail(agreement);
        return email == null ? "" : email;
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
            cs.setFont(HELVETICA_BOLD, 16);
            cs.newLineAtOffset(54f, h - 56f);
            cs.showText(label);
            cs.endText();
            // copper sub-line (#C87D5C)
            cs.beginText();
            cs.setNonStrokingColor(0xC8 / 255f, 0x7D / 255f, 0x5C / 255f);
            cs.setFont(HELVETICA, 10);
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
}
