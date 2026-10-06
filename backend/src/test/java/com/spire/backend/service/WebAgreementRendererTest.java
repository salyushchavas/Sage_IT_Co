package com.spire.backend.service;

import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.repository.UserRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.beans.PropertyDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The website agreement renders with the console's template through a
 * transient, never-saved console object. That object must never point at
 * console data: no id, no console owner, no storage keys (the console's
 * renderer would read them from the console's storage). The ERM email in the
 * document is the website owner ERM's.
 */
class WebAgreementRendererTest {

    private AgreementDocumentService docs;
    private WebAgreementFileService files;
    private WebAgreementRenderer renderer;

    @BeforeEach
    void setUp() {
        docs = mock(AgreementDocumentService.class);
        files = mock(WebAgreementFileService.class);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(20L)).thenReturn(Optional.of(
                User.builder().id(20L).email("erm@sage.test").fullName("Erin M").build()));
        renderer = new WebAgreementRenderer(docs, files, users);
    }

    private static WebAgreement signedAgreement() {
        WebAgreement a = WebAgreementRulesTest.complete();
        a.setId(7L);
        a.setApplicationId("0b5e-uuid");
        a.setParticipantUserId(10L);
        a.setOwnerUserId(20L);
        a.setStatus("VERIFIED");
        a.setAchRoutingNumber("021000021");
        a.setRequireAppendix2(true);
        a.setSignatureS3Key("s3:participant-documents/10/sig.png");
        a.setFinalSignatureS3Key("s3:participant-documents/10/final.png");
        a.setChequeS3Key("s3:participant-documents/10/c0.png");
        a.setCheques("[{\"index\":0,\"number\":\"1001\",\"date\":\"2026-09-15\",\"s3Key\":\"s3:k0\"}]");
        a.setDeletedBy(5L);
        return a;
    }

    @Test
    void theTransientObjectNeverPointsAtConsoleData() throws Exception {
        ConsultantApplication t = renderer.toTransient(signedAgreement());

        assertNull(t.getId(), "id must stay null so nothing resolves to a console row");
        assertNull(t.getOwnerErmId(), "no console owner");
        assertEquals("web-0b5e-uuid", t.getApplicationId());
        for (PropertyDescriptor pd : org.springframework.beans.BeanUtils
                .getPropertyDescriptors(ConsultantApplication.class)) {
            String n = pd.getName();
            if (n.endsWith("S3Key") || n.equals("s3Key")) {
                assertNull(pd.getReadMethod().invoke(t), n + " must not be set");
            }
        }
        assertNull(t.getSignatureImage());
        assertNull(t.getFinalSignatureImage());
        assertNull(t.getDeletedBy());
        assertFalse(t.getCheques().contains("s3:k0"), "cheque file pointers stay behind");
        assertTrue(t.getCheques().contains("1001"), "cheque numbers are rendered");
        // Same-named data is copied.
        assertEquals("Pat", t.getFirstName());
        assertEquals("021000021", t.getAchRoutingNumber());
        assertEquals(Boolean.TRUE, t.getRequireAppendix2());
        assertEquals("VERIFIED", t.getStatus());
        assertEquals("78701", t.getAddressZip());
    }

    @Test
    void clauseValuesUseTheOwnerErmsWebsiteEmail() {
        Map<String, String> console = new HashMap<>();
        console.put("ermEmail", "ermuser@sageitco.com");
        console.put("ermName", "");
        console.put("effectiveDate", "10-06-2026");
        when(docs.nonEditableDisplayValues(any())).thenReturn(console);

        Map<String, String> values = renderer.contentValues(signedAgreement());

        assertEquals("erm@sage.test", values.get("ermEmail"));
        assertEquals("", values.get("ermName"));
        assertEquals("10-06-2026", values.get("effectiveDate"));
        ArgumentCaptor<ConsultantApplication> seen = ArgumentCaptor.forClass(ConsultantApplication.class);
        verify(docs).nonEditableDisplayValues(seen.capture());
        assertNull(seen.getValue().getId());
        assertNull(seen.getValue().getOwnerErmId());
    }

    @Test
    void theRenderOverridesInjectTheStoredSignaturesAndTheErmEmail() throws Exception {
        WebAgreement a = signedAgreement();
        a.setAffirmedAppendix2(true);
        a.setWorkAuthDocS3Key(null); // no attachments: the body comes back untouched
        when(files.signatureDataUrl(a.getSignatureS3Key())).thenReturn("data:image/png;base64,AAA");
        when(files.signatureDataUrl(a.getFinalSignatureS3Key())).thenReturn("data:image/png;base64,BBB");
        byte[] body = onePagePdf();
        when(docs.renderPdfBytes(any(), any())).thenReturn(body);

        assertArrayEquals(body, renderer.renderPdf(a, true, null));

        ArgumentCaptor<ConsultantApplication> app = ArgumentCaptor.forClass(ConsultantApplication.class);
        ArgumentCaptor<AgreementDocumentService.ContextOverrides> ov =
                ArgumentCaptor.forClass(AgreementDocumentService.ContextOverrides.class);
        verify(docs).renderPdfBytes(app.capture(), ov.capture());
        assertNull(app.getValue().getId());
        assertNull(app.getValue().getSignatureS3Key());

        AgreementDocumentService svc = mock(AgreementDocumentService.class);
        when(svc.buildImageFromDataUrl("data:image/png;base64,AAA")).thenReturn("PRIMARY");
        when(svc.buildImageFromDataUrl("data:image/png;base64,BBB")).thenReturn("FINAL");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("ermSignatureImage", "ERM");
        ov.getValue().apply(ctx, svc);
        assertEquals("PRIMARY", ctx.get("signatureImage"));
        assertEquals("FINAL", ctx.get("finalSignatureImage"), "the ERM preview shows the closing signature");
        assertEquals("PRIMARY", ctx.get("appendix2Signature"), "an affirmed appendix is signed");
        assertEquals("", ctx.get("appendix1Signature"), "an appendix never affirmed stays unsigned");
        assertEquals("", ctx.get("ermSignatureImage"), "the console's ERM preview blanks the ERM signature");
        assertEquals("erm@sage.test", ctx.get("ermEmail"));
    }

    @Test
    void theParticipantPreviewUsesTheDraftSignatureAndHidesTheClosingOne() throws Exception {
        WebAgreement a = signedAgreement();
        when(docs.renderPdfBytes(any(), any())).thenReturn(onePagePdf());
        renderer.renderPdf(a, false, "data:image/png;base64,DRAFT");
        ArgumentCaptor<AgreementDocumentService.ContextOverrides> ov =
                ArgumentCaptor.forClass(AgreementDocumentService.ContextOverrides.class);
        verify(docs).renderPdfBytes(any(), ov.capture());

        AgreementDocumentService svc = mock(AgreementDocumentService.class);
        when(svc.buildImageFromDataUrl("data:image/png;base64,DRAFT")).thenReturn("DRAFT");
        Map<String, Object> ctx = new HashMap<>();
        ov.getValue().apply(ctx, svc);
        assertEquals("DRAFT", ctx.get("signatureImage"));
        assertEquals("", ctx.get("finalSignatureImage"));
        assertEquals("erm@sage.test", ctx.get("ermEmail"));
    }

    @Test
    void uploadedDocumentsAreAppendedBehindADividerButNeverCheques() throws Exception {
        WebAgreement a = signedAgreement();
        a.setWorkAuthDocContentType("image/png");
        when(files.readBytes(a.getWorkAuthDocS3Key())).thenReturn(png());
        when(docs.renderPdfBytes(any(), any())).thenReturn(onePagePdf());

        byte[] out = renderer.renderPdf(a, true, null);

        try (PDDocument doc = Loader.loadPDF(out)) {
            assertEquals(3, doc.getNumberOfPages(), "body + divider + the work-authorization image");
        }
        verify(files, never()).readBytes(a.getChequeS3Key());
    }

    @Test
    void aMissingLibreOfficeBecomesAShortPreviewError() throws Exception {
        when(docs.renderPdfBytes(any(), any()))
                .thenThrow(new IOException("Cannot run program \"libreoffice\":\nerror=2"));
        WebAgreementRenderer.RenderException e = assertThrows(WebAgreementRenderer.RenderException.class,
                () -> renderer.renderPdf(signedAgreement(), true, null));
        String reason = WebAgreementRenderer.previewErrorReason(e);
        assertTrue(reason.startsWith("IOException: Cannot run program"), reason);
        assertFalse(reason.contains("\n"));
    }

    private static byte[] onePagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] png() throws IOException {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
