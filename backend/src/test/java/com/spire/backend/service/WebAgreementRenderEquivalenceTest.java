package com.spire.backend.service;

import com.cloudinary.Cloudinary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.dto.AgreementContent;
import com.spire.backend.dto.WebAgreementContent;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.repository.AgreementUserRepository;
import com.spire.backend.repository.UserRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.beans.PropertyDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Local proof that the website's own engine draws the same documents the
 * console's renderer drew for the website. The same agreement data goes
 * through the old path (the console's AgreementDocumentService on the
 * never-saved console object and with the overrides WebAgreementRenderer
 * used to hand it, both copied below as they were) and through the new one
 * (WebAgreementRenderer on WebAgreementDocumentEngine, which draws the
 * WebAgreement itself), for every mode the website uses. Page count,
 * the text of every page (PDFBox), the images on every page and every pixel
 * at 50 DPI must match; so must the on-screen clause values (but for
 * ermEmail with no owner: blank now, never the console's address), the
 * clause sections (as JSON: the website's own clause shape serialises as
 * the console's did) and the page images. Nothing here is time-dependent between the
 * two runs except the watermark's minute, which is retried once.
 *
 * Runs only where LibreOffice is on PATH. Both renderers' LibreOffice
 * profiles go under a private temp folder, so a local backend's profiles
 * and processes are never touched. The console's code is only called here,
 * never changed.
 */
@EnabledIf("libreOfficeOnPath")
class WebAgreementRenderEquivalenceTest {

    private static final String FALLBACK_ERM_EMAIL = "ermuser@sageitco.com";
    private static final String OWNER_ERM_EMAIL = "erin.erm@sage.test";

    private static String originalTmp;
    private static Path isolated;
    private static AgreementDocumentService console;
    private static WebAgreementDocumentEngine engine;
    private static WebAgreementRenderer renderer;
    private static WebAgreementFileService files;
    private static final List<String> report = new ArrayList<>();

    static boolean libreOfficeOnPath() {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, "libreoffice"))) return true;
        }
        return false;
    }

    @BeforeAll
    static void setUp() throws Exception {
        originalTmp = System.getProperty("java.io.tmpdir");
        isolated = Files.createTempDirectory("web-render-equivalence");
        // The console's slots are found under java.io.tmpdir on first use.
        System.setProperty("java.io.tmpdir", isolated.toString());

        console = new AgreementDocumentService(mock(Cloudinary.class), mock(DocumentStorage.class),
                mock(AgreementUserRepository.class));
        ReflectionTestUtils.setField(console, "templateResource",
                new ClassPathResource("templates/SageITCO_Master_Agreement_TEMPLATE.docx"));
        ReflectionTestUtils.setField(console, "agreementErmEmail", FALLBACK_ERM_EMAIL);

        engine = new WebAgreementDocumentEngine();
        engine.profileDir = isolated.resolve("sage-web-lo-profiles").resolve("slot-0");

        files = mock(WebAgreementFileService.class);
        when(files.signatureDataUrl("s3:sig-primary")).thenReturn(signature("Pat Q Lee", 420, 130));
        when(files.signatureDataUrl("s3:sig-final")).thenReturn(signature("P. Lee", 300, 90));
        when(files.signatureDataUrl("s3:sig-erm")).thenReturn(signature("Erin M", 520, 160));
        when(files.readBytes("participant-documents/10/web-agreement-workauth.pdf")).thenReturn(attachmentPdf());
        when(files.readBytes("s3:dl.png")).thenReturn(png(640, 400, "DRIVER LICENSE"));
        UserRepository users = mock(UserRepository.class);
        when(users.findById(20L)).thenReturn(Optional.of(
                User.builder().id(20L).email(OWNER_ERM_EMAIL).fullName("Erin M").build()));
        renderer = new WebAgreementRenderer(engine, files, users);
    }

    @AfterAll
    static void tearDown() {
        System.setProperty("java.io.tmpdir", originalTmp);
        WebAgreementDocumentEngine.safeDeleteRecursively(isolated);
        System.out.println("==== web agreement render equivalence ====");
        report.forEach(System.out::println);
        System.out.println("==========================================");
    }

    // ── The modes ────────────────────────────────────────────────────

    private enum Mode { PARTICIPANT_PREVIEW, ERM_PREVIEW, FINAL }

    @Test
    void participantPreview() throws Exception {
        WebAgreement a = submitted();
        String draft = signature("Pat (draft)", 380, 110);
        compare("participant preview", oldRender(a, Mode.PARTICIPANT_PREVIEW, draft), renderer.renderPdf(a, false, draft));
    }

    @Test
    void ermPreview() throws Exception {
        WebAgreement a = submitted();
        compare("ERM preview", oldRender(a, Mode.ERM_PREVIEW, null), renderer.renderErmPreviewPdf(a));
    }

    @Test
    void consultantVersionBody() throws Exception {
        // The body WebAgreementVersionService certifies: the ERM preview of a verified agreement.
        WebAgreement a = submitted();
        a.setStatus("VERIFIED");
        a.setErmName("Erin M");
        a.setErmTitle("Engagement Manager");
        compare("consultant version body", oldRender(a, Mode.ERM_PREVIEW, null), renderer.renderErmPreviewPdf(a));
    }

    @Test
    void finalWithErmSignature() throws Exception {
        WebAgreement a = countersigned();
        compare("final (ERM signed)", oldRender(a, Mode.FINAL, null), renderer.renderFinalPdf(a));
    }

    @Test
    void phase2Final() throws Exception {
        WebAgreement a = phase2Countersigned();
        compare("Phase 2 final", oldRender(a, Mode.FINAL, null), renderer.renderFinalPdf(a));
    }

    @Test
    void blankTemplate() throws Exception {
        compare("blank template", console.getBlankPreviewPdfBytes(), renderer.renderBlankTemplatePdf());
    }

    @Test
    void clauseValues() {
        for (WebAgreement a : List.of(submitted(), countersigned(), phase2Countersigned())) {
            // The old contentValues, as it was.
            Map<String, String> before = new HashMap<>(console.nonEditableDisplayValues(consoleTransient(a)));
            before.put("ermEmail", OWNER_ERM_EMAIL);
            assertEquals(before, renderer.contentValues(a));
        }
        // With no owner the console printed its agreement-erm.email; ours leaves it blank.
        WebAgreement ownerless = submitted();
        ownerless.setOwnerUserId(null);
        Map<String, String> before = new HashMap<>(console.nonEditableDisplayValues(consoleTransient(ownerless)));
        assertEquals(FALLBACK_ERM_EMAIL, before.get("ermEmail"));
        before.put("ermEmail", "");
        assertEquals(before, renderer.contentValues(ownerless));
        report.add("clause values:            identical maps for 3 agreements; with no owner only ermEmail"
                + " differs (blank, not the console's address)");
    }

    @Test
    void clauseSections() throws Exception {
        AgreementContentService consoleClauses = new AgreementContentService();
        ReflectionTestUtils.setField(consoleClauses, "templateResource",
                new ClassPathResource("templates/SageITCO_Master_Agreement_TEMPLATE.docx"));
        consoleClauses.init();
        WebAgreementContentService ours = new WebAgreementContentService();
        ours.init();
        Map<String, List<AgreementContent.Block>> before = consoleClauses.getSections();
        Map<String, List<WebAgreementContent.Block>> after = ours.getSections();
        assertEquals(List.copyOf(before.keySet()), List.copyOf(after.keySet()));
        // What the participant's wizard reads: the JSON, field for field.
        ObjectMapper json = new ObjectMapper();
        assertEquals(json.writeValueAsString(before), json.writeValueAsString(after));
        Map<String, String> values = Map.of("ermEmail", OWNER_ERM_EMAIL, "effectiveDate", "10-01-2026");
        assertEquals(json.writeValueAsString(new AgreementContent(before, values)),
                json.writeValueAsString(new WebAgreementContent(after, values)));
        int blocks = before.values().stream().mapToInt(List::size).sum();
        assertTrue(blocks > 100, "the template was really parsed: " + blocks);
        report.add("clause sections:          identical JSON (" + before.size() + " sections, " + blocks + " blocks)");
    }

    @Test
    void pageImages() throws Exception {
        byte[] pdf = renderer.renderFinalPdf(countersigned());
        List<byte[]> before = console.renderWatermarkedPageImages(pdf, "mgr@sage.test", false);
        List<byte[]> after = renderer.renderCleanPageImages(pdf, "mgr@sage.test");
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) assertArrayEquals(before.get(i), after.get(i), "page " + (i + 1));

        // Watermarked: the stamp carries the minute, so a pair split by a minute change is retried once.
        List<byte[]> wBefore = null;
        List<byte[]> wAfter = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            wBefore = console.renderWatermarkedPageImages(pdf, "pat@x.com", true);
            wAfter = engine.renderWatermarkedPageImages(pdf, "pat@x.com", true);
            if (sameBytes(wBefore, wAfter)) break;
        }
        assertTrue(sameBytes(wBefore, wAfter), "watermarked pages");
        report.add("page images:              " + before.size() + " clean + " + wBefore.size()
                + " watermarked PNGs, byte-identical");
    }

    // ── The old path: the console's renderer with the overrides the renderer handed it ──

    /** WebAgreementRenderer.render as it was, on the console's AgreementDocumentService. */
    private byte[] oldRender(WebAgreement agreement, Mode mode, String primaryDataUrl) throws Exception {
        ConsultantApplication t = consoleTransient(agreement);
        String primary = files.signatureDataUrl(agreement.getSignatureS3Key());
        String closing = files.signatureDataUrl(agreement.getFinalSignatureS3Key());
        String erm = files.signatureDataUrl(agreement.getErmSignatureS3Key());
        String ermEmail = renderer.ownerErmEmail(agreement);
        boolean[] appendixAffirmed = {
                Boolean.TRUE.equals(agreement.getAffirmedAppendix1()),
                Boolean.TRUE.equals(agreement.getAffirmedAppendix2()),
                Boolean.TRUE.equals(agreement.getAffirmedAppendix3()),
                Boolean.TRUE.equals(agreement.getAffirmedAppendix4()),
                Boolean.TRUE.equals(agreement.getAffirmedAppendix5()),
        };
        AgreementDocumentService.ContextOverrides consoleOverrides = switch (mode) {
            case PARTICIPANT_PREVIEW -> AgreementDocumentService.consultantPreviewOverrides(primaryDataUrl);
            case ERM_PREVIEW -> AgreementDocumentService.ermPreviewOverrides();
            case FINAL -> null;
        };
        AgreementDocumentService.ContextOverrides overrides = (ctx, svc) -> {
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
            if (ermEmail != null) ctx.put("ermEmail", ermEmail);
        };
        // The console appended nothing for the transient (no console keys);
        // the renderer appended the website's uploads, and still does.
        return renderer.appendAttachments(console.renderPdfBytes(t, overrides), agreement);
    }

    /**
     * The renderer's old adapter, as it was: a never-saved console object
     * with the same-named properties copied, no id, owner, storage key,
     * Cloudinary id, URL or signature image, "web-" + the applicationId, and
     * the cheques without their file values. Only this test makes one.
     */
    private static ConsultantApplication consoleTransient(WebAgreement agreement) {
        Set<String> notCopied = new LinkedHashSet<>(List.of("id", "applicationId", "ownerErmId", "deletedBy"));
        for (Class<?> type : List.of(WebAgreement.class, ConsultantApplication.class)) {
            for (PropertyDescriptor pd : BeanUtils.getPropertyDescriptors(type)) {
                String n = pd.getName();
                if (n.endsWith("S3Key") || n.equals("s3Key")
                        || n.endsWith("PublicId") || n.endsWith("Url")
                        || n.equals("signatureImage") || n.endsWith("SignatureImage")) {
                    notCopied.add(n);
                }
            }
        }
        ConsultantApplication t = new ConsultantApplication();
        BeanUtils.copyProperties(agreement, t, notCopied.toArray(new String[0]));
        t.setId(null);
        t.setOwnerErmId(null);
        t.setApplicationId("web-" + agreement.getApplicationId());
        if (agreement.getCheques() != null && !agreement.getCheques().isBlank()) {
            List<WebAgreementRules.ChequeEntry> stripped = new ArrayList<>();
            for (WebAgreementRules.ChequeEntry e : WebAgreementRules.parseCheques(agreement)) {
                stripped.add(new WebAgreementRules.ChequeEntry(e.index(), e.number(), e.date(), "", "", ""));
            }
            t.setCheques(WebAgreementRules.serialiseCheques(stripped));
        } else {
            t.setCheques(null);
        }
        return t;
    }

    private static boolean sameBytes(List<byte[]> a, List<byte[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!java.util.Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }

    // ── The comparison ───────────────────────────────────────────────

    private static void compare(String mode, byte[] before, byte[] after) throws IOException {
        try (PDDocument a = Loader.loadPDF(before); PDDocument b = Loader.loadPDF(after)) {
            int pages = a.getNumberOfPages();
            assertEquals(pages, b.getNumberOfPages(), mode + ": page count");
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer ra = new PDFRenderer(a);
            PDFRenderer rb = new PDFRenderer(b);
            int chars = 0;
            int images = 0;
            long pixels = 0;
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String ta = stripper.getText(a);
                String tb = stripper.getText(b);
                assertEquals(ta, tb, mode + ": text of page " + p);
                chars += ta.length();
                int ia = imageCount(a.getPage(p - 1).getResources());
                assertEquals(ia, imageCount(b.getPage(p - 1).getResources()), mode + ": images on page " + p);
                images += ia;
                BufferedImage pa = ra.renderImageWithDPI(p - 1, 50f);
                BufferedImage pb = rb.renderImageWithDPI(p - 1, 50f);
                assertEquals(pa.getWidth(), pb.getWidth(), mode + ": width of page " + p);
                assertEquals(pa.getHeight(), pb.getHeight(), mode + ": height of page " + p);
                int[] da = pa.getRGB(0, 0, pa.getWidth(), pa.getHeight(), null, 0, pa.getWidth());
                int[] db = pb.getRGB(0, 0, pb.getWidth(), pb.getHeight(), null, 0, pb.getWidth());
                int differing = 0;
                for (int i = 0; i < da.length; i++) if (da[i] != db[i]) differing++;
                assertEquals(0, differing, mode + ": differing pixels on page " + p);
                pixels += da.length;
            }
            report.add(String.format("%-24s pages %d = %d; text identical on every page (%d chars); "
                            + "images per page identical (%d); %d pixels at 50 DPI identical",
                    mode + ":", pages, b.getNumberOfPages(), chars, images, pixels));
        }
    }

    private static int imageCount(PDResources res) throws IOException {
        if (res == null) return 0;
        int n = 0;
        for (COSName name : res.getXObjectNames()) {
            if (res.isImageXObject(name)) n++;
        }
        return n;
    }

    // ── The data ─────────────────────────────────────────────────────

    /** Signed by the participant and submitted: Appendices 1-3 affirmed, two uploads. */
    private static WebAgreement submitted() {
        WebAgreement a = WebAgreementRulesTest.complete();
        a.setId(7L);
        a.setApplicationId("0b5e-equivalence");
        a.setParticipantUserId(10L);
        a.setOwnerUserId(20L);
        a.setConsultantName("Pat Lee");
        a.setMiddleName("Q");
        a.setEffectiveDate(LocalDate.of(2026, 10, 1));
        a.setWorkAuthorizationCategory("Others");
        a.setWorkAuthorizationOther("H-1B transfer pending");
        a.setAddressLine2("Apt 4");
        a.setRatePeriod1("Months 1-3");
        a.setRateAmount1("$1,250.00");
        a.setTechnologyTrack("Java Full Stack");
        a.setCustomScopeNotes("Spring Boot and React");
        a.setRequireAppendix1(true);
        a.setAffirmedAppendix1(true);
        a.setEmployerPayrollEntity("Acme Payroll LLC");
        a.setImplementationPartner("Globex");
        a.setEndClient("Initech");
        a.setRoleTitle("Developer");
        a.setVerifiedStartDate(LocalDate.of(2026, 11, 2));
        a.setPayrollCycle("Bi-weekly");
        a.setRequireAppendix2(true);
        a.setAffirmedAppendix2(true);
        a.setAchAccountType("Checking");
        a.setAchBankName("Chase");
        a.setAchAccountHolderName("Pat Q Lee");
        a.setAchRoutingNumber("021000021");
        a.setAchAccountNumber("123456789");
        a.setAchNoticeEmail("pat@x.com");
        a.setAchDebitDates("15th of every month");
        a.setAchDebitAmounts("$416.67");
        a.setRequireAppendix3(true);
        a.setAffirmedAppendix3(true);
        a.setBgFullLegalName("Pat Q Lee");
        a.setBgOtherNamesUsed("None");
        a.setBgCurrentSameAsResidence(true);
        a.setBgDateOfBirth(LocalDate.of(1990, 5, 17));
        a.setBgFullSsn("123-45-6789");
        a.setBgDriverLicense("D1234567");
        a.setBgStateId("S7654321");
        a.setIdType("DL");
        a.setSignatureS3Key("s3:sig-primary");
        a.setFinalSignatureS3Key("s3:sig-final");
        a.setSignatureDate(LocalDateTime.of(2026, 10, 3, 14, 5));
        a.setSectionSignatureDates("{\"main-agreement\":\"2026-10-03T14:05:00\",\"appendix2\":\"2026-10-04T09:00:00\"}");
        a.setFinalSigningIp("203.0.113.9");
        a.setFinalSignedAt(LocalDateTime.of(2026, 10, 4, 9, 30));
        a.setWorkAuthDocContentType("application/pdf");
        a.setDlDocS3Key("s3:dl.png");
        a.setDlDocContentType("image/png");
        return a;
    }

    /** Approved and countersigned in Phase 1. */
    private static WebAgreement countersigned() {
        WebAgreement a = submitted();
        a.setStatus("COMPLETED");
        a.setPhase(1);
        a.setErmName("Erin M");
        a.setErmTitle("Engagement Manager");
        a.setErmSignatureS3Key("s3:sig-erm");
        a.setErmSignatureDate(LocalDateTime.of(2026, 10, 7, 9, 30));
        return a;
    }

    /** Reopened for Phase 2 and countersigned again: Phase 2 rates, Appendices 4-5, a legacy State ID. */
    private static WebAgreement phase2Countersigned() {
        WebAgreement a = countersigned();
        a.setPhase(2);
        a.setRatePeriod2("Months 4-12");
        a.setRateAmount2("$2,000.00");
        a.setPhase2DeliverablePeriod("Monthly");
        a.setRequireAppendix4(true);
        a.setAffirmedAppendix4(true);
        a.setPortalEntries("[{\"platform\":\"LinkedIn\",\"username\":\"patlee\"},"
                + "{\"platform\":\"Dice\",\"username\":\"plee90\"},{\"platform\":\"\",\"username\":\"x\"}]");
        a.setPortalAuthorizedActions("Apply to roles; update profile");
        a.setPortalEffectiveDate(LocalDate.of(2027, 1, 4));
        a.setPortalRevocationContact("ops@sageitco.com");
        a.setRequireAppendix5(true);
        a.setAffirmedAppendix5(true);
        a.setSecurityCheckCount("2");
        a.setCheques("[{\"index\":0,\"number\":\"1001\",\"date\":\"2026-09-15\",\"s3Key\":\"s3:k0\"},"
                + "{\"index\":1,\"number\":\"1002\",\"date\":\"2026-10-15\",\"s3Key\":\"s3:k1\"},"
                + "{\"index\":2,\"number\":\"1003\",\"date\":\"2026-11-15\",\"s3Key\":\"s3:k2\"}]");
        a.setSecurityCheckBank("Chase");
        a.setSecurityCheckHolderName("Pat Q Lee");
        a.setSecurityCheckAmount("$500.00");
        a.setIdType("STATE_ID");
        a.setBgStateId(null);
        a.setBgCurrentSameAsResidence(false);
        a.setBgCurrentAddressLine1("9 Elm St");
        a.setBgCurrentAddressCity("Dallas");
        a.setBgCurrentAddressState("TX");
        a.setBgCurrentAddressZip("75201");
        a.setSectionSignatureDates("{\"main-agreement\":\"2026-10-03T14:05:00\",\"appendix4\":\"2027-01-05T10:00:00\"}");
        a.setErmSignatureDate(LocalDateTime.of(2027, 1, 6, 16, 0));
        return a;
    }

    private static String signature(String text, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x10, 0x20, 0x60));
        g.setStroke(new BasicStroke(4f));
        g.setFont(new Font("Serif", Font.ITALIC, h / 2));
        g.drawString(text, 10, h * 2 / 3);
        g.drawLine(10, h - 12, w - 10, h - 18);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static byte[] png(int w, int h, String text) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font("SansSerif", Font.BOLD, 40));
        g.drawString(text, 20, h / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] attachmentPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(72, 700);
                cs.showText("EMPLOYMENT AUTHORIZATION DOCUMENT (test)");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
