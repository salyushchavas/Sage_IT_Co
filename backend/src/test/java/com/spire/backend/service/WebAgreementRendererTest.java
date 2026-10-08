package com.spire.backend.service;

import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.exception.GlobalExceptionHandler;
import com.spire.backend.exception.StorageUnavailableException;
import com.spire.backend.repository.UserRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The website agreement renders with the console's template through the
 * website's own engine, which draws the WebAgreement itself: no console
 * object is made or passed. No storage key ever prints; the signatures come
 * from the website's storage through the override, read only once the
 * engine admitted the render. The ERM email in the document is the website
 * owner ERM's, blank when there is none: the console's agreement-erm.email
 * never prints. The three signature layouts are the console's; a busy
 * engine stays a 503 and any other failure is a short preview error.
 */
class WebAgreementRendererTest {

    private WebAgreementDocumentEngine docs;
    private WebAgreementFileService files;
    private WebAgreementRenderer renderer;

    @BeforeEach
    void setUp() {
        docs = mock(WebAgreementDocumentEngine.class);
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
    void theEngineDrawsTheWebsiteAgreementItself() throws Exception {
        WebAgreement a = signedAgreement();
        when(docs.renderPdfBytes(any(), any())).thenReturn(onePagePdf());
        a.setWorkAuthDocS3Key(null);
        renderer.renderPdf(a, false, null);
        renderer.renderErmPreviewPdf(a);
        renderer.renderFinalPdf(a);
        verify(docs, times(3)).renderPdfBytes(same(a), any());
        renderer.contentValues(a);
        verify(docs).nonEditableDisplayValues(same(a));
    }

    @Test
    void noStorageKeyEverPrintsAndTheCountersignValuesDo() throws Exception {
        // A real engine fills the real template; the filled document is read
        // instead of converted.
        List<String> filled = new ArrayList<>();
        List<Map<String, Object>> contexts = new ArrayList<>();
        WebAgreementDocumentEngine engine = fillOnlyEngine(contexts, filled);
        WebAgreementRenderer real = new WebAgreementRenderer(engine, mock(WebAgreementFileService.class),
                mock(UserRepository.class));
        WebAgreement a = signedAgreement();
        a.setWorkAuthDocS3Key(null);
        a.setDlDocS3Key("s3:KEY-dl");
        a.setErmSignatureS3Key("s3:KEY-erm");
        a.setS3Key("s3:KEY-final");
        a.setConsultantPdfS3Key("s3:KEY-v1");
        a.setPhase1FinalPdfS3Key("s3:KEY-p1");
        a.setSignatureS3Key("s3:KEY-sig");
        a.setFinalSignatureS3Key("s3:KEY-closing");
        a.setChequeS3Key("s3:KEY-c0");
        a.setCheques("[{\"index\":0,\"number\":\"1001\",\"date\":\"2026-09-15\",\"s3Key\":\"s3:KEY-k0\"}]");
        a.setRequireAppendix5(true);
        a.setAffirmedAppendix5(true);
        a.setSecurityCheckCount("1");
        a.setErmName("Erin M");
        a.setErmTitle("Engagement Manager");

        real.renderFinalPdf(a);
        real.renderErmPreviewPdf(a);

        assertEquals(2, filled.size());
        for (String doc : filled) {
            assertFalse(doc.contains("KEY-"), "no storage key prints");
            assertTrue(doc.contains("1001"), "the cheque number prints");
            assertTrue(doc.contains("Erin M") && doc.contains("Engagement Manager"), "the countersign values print");
        }
        for (Map<String, Object> ctx : contexts) {
            assertFalse(ctx.values().stream().anyMatch(v -> String.valueOf(v).contains("KEY-")));
        }
    }

    @Test
    void aRenderRefusedAtAdmissionReadsNoSignatureAndNoOwner() throws Exception {
        WebAgreementDocumentEngine engine = new WebAgreementDocumentEngine();
        UserRepository users = mock(UserRepository.class);
        WebAgreementRenderer real = new WebAgreementRenderer(engine, files, users);
        engine.admission.drainPermits();
        try {
            WebAgreement a = countersignedAgreement();
            assertThrows(StorageUnavailableException.class, () -> real.renderFinalPdf(a));
            assertThrows(StorageUnavailableException.class, () -> real.renderPdf(a, false, null));
            assertThrows(StorageUnavailableException.class, () -> real.renderPageImages(a, null, "pat@x.com"));
        } finally {
            engine.admission.release(WebAgreementDocumentEngine.MAX_IN_PROGRESS);
        }
        verify(files, never()).signatureDataUrl(any());
        verify(files, never()).readBytes(any());
        verifyNoInteractions(users);
    }

    @Test
    void theFinalPdfSignsTheErmBlocksMainAndAffirmedAppendices() throws Exception {
        WebAgreement a = countersignedAgreement();
        when(docs.renderPdfBytes(any(), any())).thenReturn(onePagePdf());

        renderer.renderFinalPdf(a);

        ArgumentCaptor<WebAgreementDocumentEngine.ContextOverrides> ov =
                ArgumentCaptor.forClass(WebAgreementDocumentEngine.ContextOverrides.class);
        verify(docs).renderPdfBytes(same(a), ov.capture());
        verify(files, never()).signatureDataUrl(any());

        Map<String, Object> ctx = applied(ov.getValue());
        verify(files).signatureDataUrl(a.getErmSignatureS3Key());
        assertEquals("PRIMARY", ctx.get("signatureImage"));
        assertEquals("FINAL", ctx.get("finalSignatureImage"));
        assertEquals("ERM", ctx.get("ermSignatureImage"), "the final PDF carries the ERM signature");
        assertEquals("PRIMARY", ctx.get("appendix2Signature"));
        assertEquals("ERM", ctx.get("appendix2ErmSignature"), "an affirmed appendix is countersigned");
        assertEquals("", ctx.get("appendix1Signature"));
        assertEquals("", ctx.get("appendix1ErmSignature"), "an appendix never affirmed stays unsigned");
        assertEquals("erm@sage.test", ctx.get("ermEmail"));
    }

    @Test
    void theErmPreviewBlanksOnlyTheMainErmBlock() throws Exception {
        // The console's ermPreviewOverrides blanks the main block only; its
        // buildContext keeps the stored ERM signature on affirmed appendices.
        WebAgreement a = countersignedAgreement();
        when(docs.renderPdfBytes(any(), any())).thenReturn(onePagePdf());

        renderer.renderErmPreviewPdf(a);

        ArgumentCaptor<WebAgreementDocumentEngine.ContextOverrides> ov =
                ArgumentCaptor.forClass(WebAgreementDocumentEngine.ContextOverrides.class);
        verify(docs).renderPdfBytes(any(), ov.capture());
        Map<String, Object> ctx = applied(ov.getValue());
        assertEquals("", ctx.get("ermSignatureImage"), "the main ERM block is blank");
        assertEquals("ERM", ctx.get("appendix2ErmSignature"));
        assertEquals("", ctx.get("appendix1ErmSignature"));
        assertEquals("PRIMARY", ctx.get("signatureImage"));
        assertEquals("FINAL", ctx.get("finalSignatureImage"));
    }

    @Test
    void theErmPreviewHasNoErmSignatureWhenNoneIsStored() throws Exception {
        WebAgreement a = countersignedAgreement();
        a.setErmSignatureS3Key(null);
        when(docs.renderPdfBytes(any(), any())).thenReturn(onePagePdf());

        renderer.renderPdf(a, true, null); // the existing ERM preview call

        ArgumentCaptor<WebAgreementDocumentEngine.ContextOverrides> ov =
                ArgumentCaptor.forClass(WebAgreementDocumentEngine.ContextOverrides.class);
        verify(docs).renderPdfBytes(any(), ov.capture());
        Map<String, Object> ctx = applied(ov.getValue());
        assertEquals("", ctx.get("ermSignatureImage"));
        assertEquals("", ctx.get("appendix2ErmSignature"));
        verify(files).signatureDataUrl(null);
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
        verify(docs).nonEditableDisplayValues(any(WebAgreement.class));
    }

    @Test
    void theConsolesErmAddressNeverPrintsEvenWhenTheOwnerCantBeFound() throws Exception {
        String consoleAddress = consoleErmAddress();
        assertEquals("ermuser@sageitco.com", consoleAddress);
        // A real engine builds the whole context and fills the real template; the
        // context is kept after the renderer's overrides and the filled document
        // is read instead of converted.
        List<Map<String, Object>> contexts = new ArrayList<>();
        List<String> filled = new ArrayList<>();
        WebAgreementDocumentEngine engine = fillOnlyEngine(contexts, filled);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(20L)).thenReturn(Optional.of(User.builder().id(20L).email("erm@sage.test").build()));
        when(users.findById(21L)).thenReturn(Optional.of(User.builder().id(21L).email("  ").build()));
        WebAgreementRenderer real = new WebAgreementRenderer(engine, mock(WebAgreementFileService.class), users);

        // No owner, a blank email, a missing user: blank, in every layout and in the clause values.
        for (Long owner : Arrays.asList(null, 21L, 22L)) {
            WebAgreement a = signedAgreement();
            a.setWorkAuthDocS3Key(null);
            a.setOwnerUserId(owner);
            contexts.clear();
            filled.clear();
            real.renderPdf(a, false, null);
            real.renderErmPreviewPdf(a);
            real.renderFinalPdf(a);
            assertEquals(3, contexts.size());
            assertEquals(3, filled.size());
            for (Map<String, Object> ctx : contexts) {
                assertEquals("", ctx.get("ermEmail"), "owner " + owner);
                assertFalse(ctx.values().stream().anyMatch(v -> String.valueOf(v).contains(consoleAddress)),
                        "owner " + owner);
            }
            for (String doc : filled) assertFalse(doc.contains(consoleAddress), "owner " + owner);
            Map<String, String> values = real.contentValues(a);
            assertEquals("", values.get("ermEmail"), "owner " + owner);
            assertFalse(values.containsValue(consoleAddress), "owner " + owner);
        }

        // With the owner found, the owner's website email.
        WebAgreement owned = signedAgreement();
        owned.setWorkAuthDocS3Key(null);
        contexts.clear();
        real.renderFinalPdf(owned);
        assertEquals("erm@sage.test", contexts.get(0).get("ermEmail"));
        assertEquals("erm@sage.test", real.contentValues(owned).get("ermEmail"));
    }

    @Test
    void theOverridesWriteTheWebsiteValueOverAConsoleOne() throws Exception {
        // Even a context arriving with the console's address leaves with the website's value.
        WebAgreement ownerless = signedAgreement();
        ownerless.setOwnerUserId(null);
        when(docs.renderPdfBytes(any(), any())).thenReturn(onePagePdf());
        Map<String, String> console = new HashMap<>();
        console.put("ermEmail", consoleErmAddress());
        when(docs.nonEditableDisplayValues(any())).thenReturn(console);

        renderer.renderFinalPdf(ownerless);
        ArgumentCaptor<WebAgreementDocumentEngine.ContextOverrides> ov =
                ArgumentCaptor.forClass(WebAgreementDocumentEngine.ContextOverrides.class);
        verify(docs).renderPdfBytes(any(), ov.capture());
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("ermEmail", consoleErmAddress());
        ov.getValue().apply(ctx, mock(WebAgreementDocumentEngine.class));
        assertEquals("", ctx.get("ermEmail"));
        assertEquals("", renderer.contentValues(ownerless).get("ermEmail"));
    }

    /** The console's agreement-erm.email default (application.properties). */
    private static String consoleErmAddress() throws IOException {
        Properties props = new Properties();
        try (var in = WebAgreementRendererTest.class.getResourceAsStream("/application.properties")) {
            props.load(in);
        }
        String raw = props.getProperty("agreement-erm.email");
        return raw.substring(raw.indexOf(':') + 1, raw.lastIndexOf('}'));
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

        ArgumentCaptor<WebAgreementDocumentEngine.ContextOverrides> ov =
                ArgumentCaptor.forClass(WebAgreementDocumentEngine.ContextOverrides.class);
        verify(docs).renderPdfBytes(same(a), ov.capture());

        WebAgreementDocumentEngine svc = mock(WebAgreementDocumentEngine.class);
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
        ArgumentCaptor<WebAgreementDocumentEngine.ContextOverrides> ov =
                ArgumentCaptor.forClass(WebAgreementDocumentEngine.ContextOverrides.class);
        verify(docs).renderPdfBytes(any(), ov.capture());

        WebAgreementDocumentEngine svc = mock(WebAgreementDocumentEngine.class);
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

    // ── Busy and failed renders ──────────────────────────────────────

    @Test
    void aBusyEngineStaysA503AndIsNeverAPreviewError() throws Exception {
        // The engine's own slot (WebAgreementDocumentEngineTest) answers busy.
        when(docs.renderPdfBytes(any(), any())).thenThrow(
                new StorageUnavailableException(WebAgreementRenderer.BUSY_MESSAGE, null));
        StorageUnavailableException e = assertThrows(StorageUnavailableException.class,
                () -> renderer.renderFinalPdf(signedAgreement()));
        assertEquals("The document service is busy. Please try again in a minute.", e.getMessage());
        assertThrows(StorageUnavailableException.class,
                () -> renderer.renderPageImages(signedAgreement(), null, "pat@x.com"));
        assertThrows(StorageUnavailableException.class, () -> renderer.renderPdf(signedAgreement(), true, null));
        verify(docs, never()).renderWatermarkedPageImages(any(), any(), anyBoolean());
        // The existing handler answers 503 with that message.
        var response = new GlobalExceptionHandler().handleStorage(e);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(e.getMessage(), response.getBody().getMessage());
    }

    @Test
    void theBlankTemplateComesFromTheEngine() throws Exception {
        byte[] pdf = onePagePdf();
        when(docs.getBlankPreviewPdfBytes()).thenReturn(pdf);
        assertArrayEquals(pdf, renderer.renderBlankTemplatePdf());

        when(docs.getBlankPreviewPdfBytes()).thenThrow(new IOException("Cannot run program \"libreoffice\""));
        WebAgreementRenderer.RenderException e = assertThrows(WebAgreementRenderer.RenderException.class,
                () -> renderer.renderBlankTemplatePdf());
        assertTrue(WebAgreementRenderer.previewErrorReason(e).startsWith("IOException: Cannot run program"));

        reset(docs);
        when(docs.getBlankPreviewPdfBytes()).thenThrow(
                new StorageUnavailableException(WebAgreementRenderer.BUSY_MESSAGE, null));
        assertThrows(StorageUnavailableException.class, () -> renderer.renderBlankTemplatePdf());
    }

    @Test
    void cleanPageImagesAreUnwatermarkedAndNeverRender() throws Exception {
        byte[] pdf = onePagePdf();
        when(docs.renderWatermarkedPageImages(any(), any(), anyBoolean())).thenReturn(List.of(new byte[]{1}));
        assertEquals(1, renderer.renderCleanPageImages(pdf, "mgr@sage.test").size());
        assertEquals(1, renderer.renderCleanPageImages(pdf).size());
        verify(docs).renderWatermarkedPageImages(pdf, "mgr@sage.test", false);
        verify(docs).renderWatermarkedPageImages(pdf, null, false);
        verify(docs, never()).renderPdfBytes(any(), any());
    }

    @Test
    void theParticipantsPagesAreWatermarked() throws Exception {
        byte[] pdf = onePagePdf();
        WebAgreement a = signedAgreement();
        a.setWorkAuthDocS3Key(null);
        when(docs.renderPdfBytes(any(), any())).thenReturn(pdf);
        when(docs.renderWatermarkedPageImages(any(), any(), anyBoolean())).thenReturn(List.of(new byte[]{1}));
        assertEquals(1, renderer.renderPageImages(a, null, "pat@x.com").size());
        verify(docs).renderWatermarkedPageImages(pdf, "pat@x.com", true);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Signed by the participant, approved and countersigned; Appendix 2 affirmed. */
    private WebAgreement countersignedAgreement() {
        WebAgreement a = signedAgreement();
        a.setStatus("COMPLETED");
        a.setWorkAuthDocS3Key(null);
        a.setAffirmedAppendix2(true);
        a.setErmName("Erin M");
        a.setErmTitle("Engagement Manager");
        a.setErmSignatureS3Key("s3:participant-documents/10/erm.png");
        a.setErmSignatureDate(LocalDateTime.of(2026, 10, 7, 9, 30));
        when(files.signatureDataUrl(a.getSignatureS3Key())).thenReturn("data:image/png;base64,AAA");
        when(files.signatureDataUrl(a.getFinalSignatureS3Key())).thenReturn("data:image/png;base64,BBB");
        when(files.signatureDataUrl(a.getErmSignatureS3Key())).thenReturn("data:image/png;base64,CCC");
        return a;
    }

    /**
     * A real engine that keeps each context after the renderer's overrides
     * and reads the filled template instead of converting it.
     */
    private static WebAgreementDocumentEngine fillOnlyEngine(List<Map<String, Object>> contexts, List<String> filled) {
        return new WebAgreementDocumentEngine() {
            @Override
            public byte[] renderPdfBytes(WebAgreement agreement, ContextOverrides overrides) throws Exception {
                return super.renderPdfBytes(agreement, (ctx, svc) -> {
                    overrides.apply(ctx, svc);
                    contexts.add(new HashMap<>(ctx));
                });
            }

            @Override
            Path convertToPdf(Path docx) throws IOException {
                try (XWPFDocument d = new XWPFDocument(Files.newInputStream(docx));
                     XWPFWordExtractor text = new XWPFWordExtractor(d)) {
                    filled.add(text.getText());
                }
                Path pdf = Files.createTempFile("web-agreement-renderer-test", ".pdf");
                Files.write(pdf, onePagePdf());
                return pdf;
            }
        };
    }

    /** Runs a captured override over an empty context with images PRIMARY / FINAL / ERM. */
    private static Map<String, Object> applied(WebAgreementDocumentEngine.ContextOverrides overrides) {
        WebAgreementDocumentEngine svc = mock(WebAgreementDocumentEngine.class);
        when(svc.buildImageFromDataUrl("data:image/png;base64,AAA")).thenReturn("PRIMARY");
        when(svc.buildImageFromDataUrl("data:image/png;base64,BBB")).thenReturn("FINAL");
        when(svc.buildImageFromDataUrl("data:image/png;base64,CCC")).thenReturn("ERM");
        Map<String, Object> ctx = new HashMap<>();
        overrides.apply(ctx, svc);
        return ctx;
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
