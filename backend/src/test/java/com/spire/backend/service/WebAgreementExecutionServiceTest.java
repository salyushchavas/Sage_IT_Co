package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.controller.WebAgreementExecutionController;
import com.spire.backend.controller.WebAgreementStaffController;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.entity.WebAgreementStaffTitle;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.StorageUnavailableException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementEventRepository;
import com.spire.backend.repository.WebAgreementRepository;
import com.spire.backend.repository.WebAgreementStaffTitleRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The end of the chain (the console's countersign, executed-PDF download,
 * /me prefill and Phase 2 advance): the countersign form is prefilled with
 * the caller's own name and title; "Approve & sign" needs READY_TO_SIGN, a
 * name, a title and a drawn signature, completes the agreement and then,
 * best effort, stores the final PDF (both keys in Phase 1, only s3Key in
 * Phase 2) and stays COMPLETED when that fails; the ERM's download streams
 * the stored PDF with no status check; the advance reopens an executed
 * Phase 1 agreement, clearing and keeping exactly what the console does,
 * the SSN trap included. Only the owner or an admin; no email.
 */
class WebAgreementExecutionServiceTest {

    private static final long PAT = 10L, ERM = 20L, OTHER_ERM = 21L, OFF_ERM = 22L, OPS = 30L, SYS = 31L,
            COACH = 40L, MGR = 50L;

    private static final String SIG = "data:image/png;base64,iVBORw0KGgo=";
    private static final byte[] FINAL = "%PDF final executed agreement".getBytes();
    private static final String FILENAME = "SageITCO-Agreement_Pat-Lee_Data-Engineering.pdf";

    private final List<User> users = new ArrayList<>();
    private final List<WebAgreement> agreements = new ArrayList<>();
    private final List<WebAgreementEvent> events = new ArrayList<>();
    private final List<WebAgreementStaffTitle> titles = new ArrayList<>();
    /** The website storage: stored value → bytes. */
    private final Map<String, byte[]> stored = new HashMap<>();
    /** Every agreement save as written: [status, s3Key, phase1FinalPdfS3Key]. */
    private final List<String[]> saves = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private DocumentStorageService storage;
    private WebAgreementRenderer renderer;
    private WebAgreementExecutionService service;
    private WebAgreementExecutionController controller;
    private MockHttpServletRequest request;
    private int uploads;

    @BeforeEach
    void setUp() {
        user(PAT, "PARTICIPANT", "Pat Lee", true);
        user(ERM, "ERM", "Erin Manager", true);
        user(OTHER_ERM, "ERM", "Other Erm", true);
        user(OFF_ERM, "ERM", "Off Erm", false);
        user(OPS, "OPERATIONS_ADMIN", "Ops Admin", true);
        user(SYS, "SYSTEM_ADMIN", "Sys Admin", true);
        user(COACH, "COACH", "Coach Carter", true);
        user(MGR, "MANAGER", "Mona Manager", true);

        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> users.stream()
                .filter(u -> u.getId().equals(inv.getArgument(0))).findFirst());

        WebAgreementRepository repo = mock(WebAgreementRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            WebAgreement a = inv.getArgument(0);
            saves.add(new String[] {a.getStatus(), a.getS3Key(), a.getPhase1FinalPdfS3Key()});
            return a;
        });
        when(repo.findByApplicationId(anyString())).thenAnswer(inv -> agreements.stream()
                .filter(a -> a.getApplicationId().equals(inv.getArgument(0))).findFirst());

        WebAgreementEventRepository eventRepo = mock(WebAgreementEventRepository.class);
        when(eventRepo.save(any())).thenAnswer(inv -> {
            events.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        WebAgreementStaffTitleRepository titleRepo = mock(WebAgreementStaffTitleRepository.class);
        when(titleRepo.findByUserId(anyLong())).thenAnswer(inv -> titles.stream()
                .filter(t -> t.getUserId().equals(inv.getArgument(0))).findFirst());

        // The real file service over a fake website storage.
        storage = mock(DocumentStorageService.class);
        when(storage.upload(anyLong(), anyString(), any(), anyString())).thenAnswer(inv -> {
            String key = "s3:participant-documents/" + inv.getArgument(0) + "/" + inv.getArgument(1)
                    + "#" + (++uploads);
            stored.put(key, inv.getArgument(2));
            return new DocumentStorageService.StoredFile(key, key);
        });
        when(storage.readBytes(anyString())).thenAnswer(inv -> stored.get(inv.<String>getArgument(0)));
        WebAgreementFileService files = new WebAgreementFileService(storage, mock(HeicTranscoder.class));

        renderer = mock(WebAgreementRenderer.class);
        when(renderer.renderFinalPdf(any())).thenReturn(FINAL);

        service = new WebAgreementExecutionService(repo, files, renderer, new WebAgreementEventService(eventRepo),
                new WebAgreementAccess(repo, userRepo), new WebAgreementStaffTitleService(titleRepo));
        controller = new WebAgreementExecutionController(service);

        request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9");
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    private void user(long id, String role, String name, boolean active) {
        users.add(User.builder().id(id).fullName(name).email(name.replace(' ', '.').toLowerCase() + "@sageitco.com")
                .role(Role.builder().name(role).build()).isActive(active).build());
    }

    private void title(long userId, String title) {
        titles.add(WebAgreementStaffTitle.builder().id((long) titles.size() + 1).userId(userId).title(title).build());
    }

    /** A signed agreement owned by the ERM. */
    private WebAgreement agreement(String status, Integer phase) {
        WebAgreement a = WebAgreementRulesTest.complete();
        a.setId((long) agreements.size() + 1);
        a.setApplicationId("app-" + a.getId());
        a.setParticipantUserId(PAT);
        a.setOwnerUserId(ERM);
        a.setStatus(status);
        a.setPhase(phase);
        a.setConsultantName("Pat Lee");
        a.setSignedLegalName("Pat Lee");
        a.setTechnologyTrack("Data Engineering");
        a.setConsultantCopyReleased(true);
        a.setEffectiveDate(LocalDate.of(2026, 9, 1));
        agreements.add(a);
        return a;
    }

    /**
     * An executed Phase 1 agreement: both participant signatures, every
     * section affirmed, the countersignature and both final PDFs stored.
     */
    private WebAgreement executed() {
        WebAgreement a = agreement("COMPLETED", 1);
        a.setRequireAppendix1(true);
        a.setSignatureS3Key("s3:participant-documents/10/web-agreement-consultant-1.png");
        a.setSignedAt(LocalDateTime.of(2026, 9, 2, 10, 0));
        a.setSigningAt(LocalDateTime.of(2026, 9, 2, 10, 0));
        a.setSigningIp("198.51.100.4");
        a.setFinalSignatureS3Key("s3:participant-documents/10/web-agreement-consultant-final-1.png");
        a.setFinalSignedAt(LocalDateTime.of(2026, 9, 2, 10, 5));
        a.setFinalSigningIp("198.51.100.4");
        a.setSignatureDate(LocalDateTime.of(2026, 9, 2, 10, 5));
        for (int n = 1; n <= 5; n++) setAffirmed(a, n, true);
        a.setErmName("Erin Manager");
        a.setErmTitle("Engagement Manager");
        a.setErmSignatureS3Key("s3:participant-documents/10/web-agreement-erm-1.png");
        a.setErmSignatureDate(LocalDateTime.of(2026, 9, 20, 15, 0));
        a.setS3Key("s3:participant-documents/10/web-agreement-final-p1-1.pdf");
        a.setPhase1FinalPdfS3Key("s3:participant-documents/10/web-agreement-final-p1-1.pdf");
        a.setConsultantPdfS3Key("s3:participant-documents/10/web-agreement-consultant-version-p1-1.pdf");
        a.setDocumentHash("ab12");
        a.setApprovalVersionNumber(1);
        a.setEmployerPayrollEntity("Sage IT Co");
        a.setAchAccountNumber("000123");
        a.setBgFullSsn("123456789");
        return a;
    }

    private static void setAffirmed(WebAgreement a, int n, boolean v) {
        switch (n) {
            case 1 -> a.setAffirmedAppendix1(v);
            case 2 -> a.setAffirmedAppendix2(v);
            case 3 -> a.setAffirmedAppendix3(v);
            case 4 -> a.setAffirmedAppendix4(v);
            default -> a.setAffirmedAppendix5(v);
        }
    }

    private static boolean affirmed(WebAgreement a, int n) {
        return Boolean.TRUE.equals(switch (n) {
            case 1 -> a.getAffirmedAppendix1();
            case 2 -> a.getAffirmedAppendix2();
            case 3 -> a.getAffirmedAppendix3();
            case 4 -> a.getAffirmedAppendix4();
            default -> a.getAffirmedAppendix5();
        });
    }

    private List<WebAgreementEvent> eventsOf(WebAgreement a, WebAgreementEvent.EventType type) {
        return events.stream()
                .filter(e -> e.getAgreementId().equals(a.getId()) && e.getEventType().equals(type.name()))
                .toList();
    }

    private JsonNode meta(WebAgreementEvent e) throws IOException {
        return mapper.readTree(e.getMetadata());
    }

    private WebAgreement sign(WebAgreement a, long caller) {
        return service.approveAndSign(a.getApplicationId(), "Erin Manager", "Engagement Manager", SIG, caller, request);
    }

    private static Authentication auth(long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null);
    }

    private static WebAgreementExecutionService.Phase2Promotion promotion(
            Boolean a1, Boolean a2, Boolean a3, Boolean a4, Boolean a5, Boolean ssn) {
        WebAgreementExecutionService.Phase2Promotion p = new WebAgreementExecutionService.Phase2Promotion();
        p.appendix1 = a1;
        p.appendix2 = a2;
        p.appendix3 = a3;
        p.appendix4 = a4;
        p.appendix5 = a5;
        p.ssn = ssn;
        return p;
    }

    /** What the console's advance modal sends: every section not yet required ticked, SSN included. */
    private static WebAgreementExecutionService.Phase2Promotion asTheModalSends(WebAgreement a) {
        return promotion(!a.getRequireAppendix1(), !a.getRequireAppendix2(), !a.getRequireAppendix3(),
                !a.getRequireAppendix4(), !a.getRequireAppendix5(), !a.getRequireSsn());
    }

    // ── Signer profile ───────────────────────────────────────────────

    @Test
    void theSignerProfileIsTheCallersOwnNameAndTitle() {
        title(ERM, "Engagement Manager");
        title(SYS, "   ");
        Map<String, String> erm = service.signerProfile(ERM);
        assertEquals(List.of("fullName", "title"), List.copyOf(erm.keySet()));
        assertEquals("Erin Manager", erm.get("fullName"));
        assertEquals("Engagement Manager", erm.get("title"));
        assertEquals(Map.of("fullName", "Ops Admin", "title", ""), service.signerProfile(OPS), "no title set");
        assertEquals(Map.of("fullName", "Sys Admin", "title", ""), service.signerProfile(SYS), "a blank title is none");

        ResponseEntity<ApiResponse<Map<String, String>>> res = controller.signerProfile(auth(ERM));
        assertEquals(200, res.getStatusCode().value());
        assertEquals("Engagement Manager", res.getBody().getData().get("title"));

        for (long other : new long[] {MGR, COACH, PAT, OFF_ERM, 12345L}) {
            assertThrows(AccessDeniedException.class, () -> service.signerProfile(other), "caller " + other);
        }
    }

    // ── Approve & sign ───────────────────────────────────────────────

    @Test
    void theCountersignNeedsReadyToSignANameATitleAndADrawnSignature() {
        for (String status : List.of("SUBMITTED", "REVISION_REQUESTED", "VERIFIED", "AWAITING_APPROVALS",
                "APPROVAL_REVISION_REQUESTED", "COMPLETED", "CANCELLED")) {
            WebAgreement a = agreement(status, 1);
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> sign(a, ERM), status);
            assertEquals("Only READY_TO_SIGN applications can be countersigned (status=" + status
                    + "). All required approvals must be in first.", e.getMessage());
            // The status is checked first, as in the console.
            assertThrows(IllegalStateException.class,
                    () -> service.approveAndSign(a.getApplicationId(), "", "", "x", ERM, request));
        }

        WebAgreement a = agreement("READY_TO_SIGN", 1);
        String app = a.getApplicationId();
        for (String[] nameTitle : new String[][] {{null, "Engagement Manager"}, {"  ", "Engagement Manager"},
                {"Erin Manager", null}, {"Erin Manager", " "}}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.approveAndSign(app, nameTitle[0], nameTitle[1], SIG, ERM, request));
            assertEquals("ERM name and title are required to countersign.", e.getMessage());
        }
        for (String sig : new String[] {null, " ", "iVBORw0KGgo=", "data:application/pdf;base64,JVBERi0="}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.approveAndSign(app, "Erin Manager", "Engagement Manager", sig, ERM, request));
            assertEquals("An ERM signature image is required (data:image/...).", e.getMessage());
        }

        assertEquals("READY_TO_SIGN", a.getStatus());
        assertTrue(saves.isEmpty());
        assertTrue(events.isEmpty());
        verify(storage, never()).upload(anyLong(), anyString(), any(), anyString());
        verify(renderer, never()).renderFinalPdf(any());
    }

    @Test
    void aPhase1CountersignCompletesTheAgreementAndStoresTheFinalPdfAsBothKeys() throws Exception {
        WebAgreement a = agreement("READY_TO_SIGN", 1);
        a.setEffectiveDate(null);
        LocalDateTime participantDate = LocalDateTime.of(2026, 9, 2, 10, 5);
        a.setSignatureDate(participantDate);
        // What the render sees: the agreement already countersigned.
        String[] atRender = new String[3];
        when(renderer.renderFinalPdf(any())).thenAnswer(inv -> {
            WebAgreement r = inv.getArgument(0);
            atRender[0] = r.getStatus();
            atRender[1] = r.getErmSignatureS3Key();
            atRender[2] = r.getErmName();
            return FINAL;
        });

        // Name and title are taken as typed; nothing ties them to the signer.
        LocalDateTime before = LocalDateTime.now();
        WebAgreement out = service.approveAndSign(a.getApplicationId(), "Someone Else", "Director", SIG, ERM, request);
        assertSame(a, out);
        assertEquals("COMPLETED", a.getStatus());
        assertEquals("Someone Else", a.getErmName());
        assertEquals("Director", a.getErmTitle());
        assertFalse(a.getErmSignatureDate().isBefore(before));
        assertEquals(before.toLocalDate(), a.getEffectiveDate(), "an empty effective date becomes today");
        assertEquals(participantDate, a.getSignatureDate(), "the participant's date is untouched");

        // The signature: the participant's folder, a web-agreement name, never the console's prefix.
        String sigKey = a.getErmSignatureS3Key();
        assertTrue(sigKey.startsWith("s3:participant-documents/10/web-agreement-erm-"), sigKey);
        assertArrayEquals(java.util.Base64.getDecoder().decode("iVBORw0KGgo="), stored.get(sigKey));

        // The final PDF: rendered after the countersign, stored as a new file, both keys.
        assertArrayEquals(new String[] {"COMPLETED", sigKey, "Someone Else"}, atRender);
        String finalKey = a.getS3Key();
        assertTrue(finalKey.startsWith("s3:participant-documents/10/web-agreement-final-p1-"), finalKey);
        assertTrue(finalKey.endsWith(".pdf#2"), finalKey);
        assertEquals(finalKey, a.getPhase1FinalPdfS3Key());
        assertArrayEquals(FINAL, stored.get(finalKey));
        for (String key : stored.keySet()) assertFalse(key.contains("agreements/"), key);
        assertEquals(2, saves.size());
        assertArrayEquals(new String[] {"COMPLETED", null, null}, saves.get(0), "countersigned, then the PDF");
        assertArrayEquals(new String[] {"COMPLETED", finalKey, finalKey}, saves.get(1));

        WebAgreementEvent signed = eventsOf(a, WebAgreementEvent.EventType.APPROVED_AND_SIGNED).get(0);
        assertEquals("ERM", signed.getActorType());
        assertEquals(ERM, signed.getActorUserId(), "the real users.id");
        assertEquals("203.0.113.9", signed.getIpAddress());
        assertEquals(mapper.readTree("{\"ermName\":\"Someone Else\",\"ermTitle\":\"Director\"}"), meta(signed));

        WebAgreementEvent pdf = eventsOf(a, WebAgreementEvent.EventType.PDF_GENERATED).get(0);
        assertEquals("SYSTEM", pdf.getActorType());
        assertNull(pdf.getActorUserId());
        assertNull(pdf.getIpAddress());
        assertEquals(finalKey, meta(pdf).path("s3Key").asText());
        assertEquals("final", meta(pdf).path("kind").asText());
        assertEquals(2, events.size(), "no email event, nothing else");
    }

    @Test
    void aPhase2CountersignReplacesOnlyS3KeyAndKeepsThePhase1Copy() {
        WebAgreement a = agreement("READY_TO_SIGN", 2);
        a.setS3Key("s3:participant-documents/10/web-agreement-final-p1-old.pdf");
        a.setPhase1FinalPdfS3Key("s3:participant-documents/10/web-agreement-final-p1-old.pdf");
        LocalDate effective = a.getEffectiveDate();

        sign(a, ERM);
        assertEquals("COMPLETED", a.getStatus());
        assertTrue(a.getS3Key().startsWith("s3:participant-documents/10/web-agreement-final-p2-"), a.getS3Key());
        assertEquals("s3:participant-documents/10/web-agreement-final-p1-old.pdf", a.getPhase1FinalPdfS3Key());
        assertEquals(effective, a.getEffectiveDate(), "a set effective date stays");

        // An agreement without a phase counts as Phase 1.
        WebAgreement legacy = agreement("READY_TO_SIGN", null);
        sign(legacy, ERM);
        assertTrue(legacy.getS3Key().contains("web-agreement-final-p1-"), legacy.getS3Key());
        assertEquals(legacy.getS3Key(), legacy.getPhase1FinalPdfS3Key());
    }

    @Test
    void whenTheFinalPdfFailsTheAgreementStaysCompletedWithNoPdf() {
        List<RuntimeException> failures = List.of(
                new WebAgreementRenderer.RenderException(new IOException("soffice not found")),
                new StorageUnavailableException("The document service is busy. Please try again in a minute.", null));
        for (RuntimeException failure : failures) {
            doThrow(failure).when(renderer).renderFinalPdf(any());
            WebAgreement a = agreement("READY_TO_SIGN", 1);
            assertSame(a, sign(a, ERM));
            assertEquals("COMPLETED", a.getStatus());
            assertNotNull(a.getErmSignatureS3Key());
            assertNull(a.getS3Key());
            assertNull(a.getPhase1FinalPdfS3Key());
            assertEquals(1, eventsOf(a, WebAgreementEvent.EventType.APPROVED_AND_SIGNED).size());
            assertTrue(eventsOf(a, WebAgreementEvent.EventType.PDF_GENERATED).isEmpty());
        }

        // Storing the rendered PDF fails: the same.
        doReturn(FINAL).when(renderer).renderFinalPdf(any());
        doThrow(new StorageUnavailableException("The document store didn't answer.", null))
                .when(storage).upload(anyLong(), anyString(), any(), eq("application/pdf"));
        WebAgreement a = agreement("READY_TO_SIGN", 1);
        sign(a, ERM);
        assertEquals("COMPLETED", a.getStatus());
        assertNull(a.getS3Key());
        assertTrue(eventsOf(a, WebAgreementEvent.EventType.PDF_GENERATED).isEmpty());
    }

    @Test
    void aSignatureThatCantBeStoredIsAConflictAndChangesNothing() {
        doThrow(new StorageUnavailableException("The document store didn't answer.", null))
                .when(storage).upload(anyLong(), anyString(), any(), anyString());
        WebAgreement a = agreement("READY_TO_SIGN", 1);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> sign(a, ERM));
        assertEquals("Couldn't store ERM signature: The document store didn't answer.", e.getMessage());
        assertEquals("READY_TO_SIGN", a.getStatus());
        assertNull(a.getErmName());
        assertNull(a.getErmSignatureS3Key());
        assertNull(a.getErmSignatureDate());
        assertTrue(saves.isEmpty());
        assertTrue(events.isEmpty());

        // A malformed image the decoder can't read: the same 409.
        WebAgreement b = agreement("READY_TO_SIGN", 1);
        IllegalStateException bad = assertThrows(IllegalStateException.class,
                () -> service.approveAndSign(b.getApplicationId(), "Erin", "ERM", "data:image/png;base64", ERM, request));
        assertTrue(bad.getMessage().startsWith("Couldn't store ERM signature: "), bad.getMessage());
        assertEquals("READY_TO_SIGN", b.getStatus());
    }

    @Test
    void onlyTheOwnerOrAnAdminCanCountersignDownloadOrAdvance() {
        WebAgreement a = agreement("READY_TO_SIGN", 1);
        String app = a.getApplicationId();
        assertThrows(ResourceNotFoundException.class, () -> sign(a, OTHER_ERM));
        assertThrows(ResourceNotFoundException.class, () -> service.downloadFinal(app, OTHER_ERM));
        assertThrows(ResourceNotFoundException.class,
                () -> service.advanceToPhase2(app, null, OTHER_ERM, request));
        for (long other : new long[] {MGR, COACH, PAT, OFF_ERM}) {
            assertThrows(AccessDeniedException.class, () -> sign(a, other), "caller " + other);
            assertThrows(AccessDeniedException.class, () -> service.downloadFinal(app, other));
        }
        assertThrows(ResourceNotFoundException.class, () -> sign(agreement("READY_TO_SIGN", 1), OTHER_ERM));
        assertTrue(saves.isEmpty());

        // An Operations admin countersigns any agreement, as itself.
        sign(a, OPS);
        assertEquals("COMPLETED", a.getStatus());
        assertEquals(OPS, eventsOf(a, WebAgreementEvent.EventType.APPROVED_AND_SIGNED).get(0).getActorUserId());
        WebAgreement b = agreement("READY_TO_SIGN", 1);
        sign(b, SYS);
        assertEquals("COMPLETED", b.getStatus());

        // An archived agreement is gone for everyone.
        WebAgreement archived = agreement("READY_TO_SIGN", 1);
        archived.setDeleted(true);
        assertThrows(ResourceNotFoundException.class, () -> sign(archived, SYS));
        assertThrows(ResourceNotFoundException.class, () -> service.downloadFinal(archived.getApplicationId(), ERM));
        assertThrows(ResourceNotFoundException.class,
                () -> service.approveAndSign("nope", "Erin Manager", "Engagement Manager", SIG, ERM, request));
    }

    // ── The executed PDF ─────────────────────────────────────────────

    @Test
    void theErmDownloadStreamsTheStoredFinalPdfWithNoStatusCheck() {
        WebAgreement a = agreement("READY_TO_SIGN", 1);
        assertNull(service.downloadFinal(a.getApplicationId(), ERM), "nothing stored yet");
        ResponseEntity<byte[]> none = controller.downloadPdf(a.getApplicationId(), null, auth(ERM));
        assertEquals(404, none.getStatusCode().value());

        sign(a, ERM);
        WebAgreementFileService.Download pdf = service.downloadFinal(a.getApplicationId(), ERM);
        assertArrayEquals(FINAL, pdf.bytes());
        assertEquals("application/pdf", pdf.contentType());
        assertEquals(FILENAME, pdf.filename());

        ResponseEntity<byte[]> inline = controller.downloadPdf(a.getApplicationId(), null, auth(ERM));
        assertEquals(200, inline.getStatusCode().value());
        assertArrayEquals(FINAL, inline.getBody());
        assertEquals("application/pdf", inline.getHeaders().getContentType().toString());
        assertEquals("inline; filename=\"" + FILENAME + "\"",
                inline.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("private, no-store", inline.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals(FINAL.length, inline.getHeaders().getContentLength());
        ResponseEntity<byte[]> attachment = controller.downloadPdf(a.getApplicationId(), "ATTACHMENT", auth(OPS));
        assertEquals("attachment; filename=\"" + FILENAME + "\"",
                attachment.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));

        // No status check: after the Phase 2 advance it still serves the Phase 1 PDF.
        service.advanceToPhase2(a.getApplicationId(), null, ERM, request);
        assertEquals("SUBMITTED", a.getStatus());
        assertArrayEquals(FINAL, service.downloadFinal(a.getApplicationId(), ERM).bytes());
    }

    @Test
    void aStoredFinalPdfThatCantBeReadIsBadGateway() {
        WebAgreement gone = agreement("COMPLETED", 1);
        gone.setS3Key("s3:participant-documents/10/web-agreement-final-p1-gone.pdf");
        assertThrows(WebAgreementExecutionService.FinalPdfUnreadable.class,
                () -> service.downloadFinal(gone.getApplicationId(), ERM));
        ResponseEntity<byte[]> res = controller.downloadPdf(gone.getApplicationId(), null, auth(ERM));
        assertEquals(502, res.getStatusCode().value());
        assertNull(res.getBody());

        WebAgreement down = agreement("COMPLETED", 1);
        down.setS3Key("s3:participant-documents/10/web-agreement-final-p1-down.pdf");
        doThrow(new StorageUnavailableException("The document store didn't answer.", null))
                .when(storage).readBytes(down.getS3Key());
        assertEquals(502, controller.downloadPdf(down.getApplicationId(), "attachment", auth(ERM))
                .getStatusCode().value());
        verify(renderer, never()).renderFinalPdf(any());
    }

    // ── Phase 2 ──────────────────────────────────────────────────────

    @Test
    void onlyACompletedPhase1AgreementCanBeAdvanced() {
        for (String status : List.of("SUBMITTED", "REVISION_REQUESTED", "VERIFIED", "AWAITING_APPROVALS",
                "APPROVAL_REVISION_REQUESTED", "READY_TO_SIGN", "CANCELLED")) {
            WebAgreement a = agreement(status, 1);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service.advanceToPhase2(a.getApplicationId(), null, ERM, request), status);
            assertEquals("Only a COMPLETED agreement can be advanced to Phase 2 (status=" + status + ").",
                    e.getMessage());
        }
        WebAgreement p2 = agreement("COMPLETED", 2);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.advanceToPhase2(p2.getApplicationId(), null, ERM, request));
        assertEquals("This agreement is already at Phase 2.", e.getMessage());
        assertTrue(saves.isEmpty());
        assertTrue(events.isEmpty());

        // A row without a phase counts as Phase 1; advancing twice is refused.
        WebAgreement legacy = agreement("COMPLETED", null);
        service.advanceToPhase2(legacy.getApplicationId(), null, ERM, request);
        assertEquals(2, legacy.getPhase());
        assertThrows(IllegalStateException.class,
                () -> service.advanceToPhase2(legacy.getApplicationId(), null, ERM, request));
    }

    @Test
    void withNoChoiceEveryOptionalAppendixIsPromotedAndTheSsnIsLeftAlone() throws Exception {
        WebAgreement a = executed();
        service.advanceToPhase2(a.getApplicationId(), null, ERM, request);
        assertTrue(a.getRequireAppendix1());
        assertTrue(a.getRequireAppendix2() && a.getRequireAppendix3() && a.getRequireAppendix4()
                && a.getRequireAppendix5());
        assertFalse(a.getRequireSsn(), "the default path leaves the SSN alone");
        assertEquals("[{\"key\":\"appendix2\"},{\"key\":\"appendix3\"},{\"key\":\"appendix4\"},{\"key\":\"appendix5\"}]",
                a.getPhase2ReopenedSections());
        assertTrue(affirmed(a, 1), "Appendix 1 was already required: kept");
        for (int n = 2; n <= 5; n++) assertFalse(affirmed(a, n), "appendix " + n + " re-affirmed");
        assertEquals(List.of("appendix2", "appendix3", "appendix4", "appendix5"),
                mapper.convertValue(meta(eventsOf(a, WebAgreementEvent.EventType.ADVANCED_TO_PHASE_2).get(0))
                        .path("promoted"), List.class));

        // A body with every field null is the same default.
        WebAgreement b = executed();
        service.advanceToPhase2(b.getApplicationId(), promotion(null, null, null, null, null, null), ERM, request);
        assertEquals(a.getPhase2ReopenedSections(), b.getPhase2ReopenedSections());
        assertFalse(b.getRequireSsn());
    }

    @Test
    void anExplicitChoicePromotesOnlyTheTickedSectionsAndTheSsnIsNeverInScope() throws Exception {
        WebAgreement a = executed();
        service.advanceToPhase2(a.getApplicationId(), promotion(null, true, false, null, null, true), ERM, request);
        assertTrue(a.getRequireAppendix2());
        assertFalse(a.getRequireAppendix3(), "false leaves it alone");
        assertFalse(a.getRequireAppendix4(), "missing leaves it alone");
        assertFalse(a.getRequireAppendix5());
        assertTrue(a.getRequireSsn());
        assertEquals("[{\"key\":\"appendix2\"}]", a.getPhase2ReopenedSections(), "appendix keys only");
        assertFalse(affirmed(a, 2));
        for (int n : new int[] {1, 3, 4, 5}) assertTrue(affirmed(a, n), "appendix " + n + " kept");
        assertEquals(List.of("appendix2", "ssn"),
                mapper.convertValue(meta(eventsOf(a, WebAgreementEvent.EventType.ADVANCED_TO_PHASE_2).get(0))
                        .path("promoted"), List.class), "the event names the SSN, the scope doesn't");
        assertEquals(Set.of("appendix2"), WebAgreementRules.consultantWriteScope(a).orElseThrow());

        // Nothing ticked: nothing promoted, nothing writable but the signatures.
        WebAgreement b = executed();
        service.advanceToPhase2(b.getApplicationId(), promotion(false, false, false, false, false, false),
                ERM, request);
        assertEquals("[]", b.getPhase2ReopenedSections());
        assertEquals(Set.of(), WebAgreementRules.consultantWriteScope(b).orElseThrow());
        assertFalse(b.getRequireAppendix2());
        for (int n = 1; n <= 5; n++) assertTrue(affirmed(b, n));
    }

    @Test
    void theAdvanceClearsAndKeepsExactlyWhatTheConsoleDoesAndRecordsTheClearedFiles() throws Exception {
        WebAgreement a = executed();
        String ermSig = a.getErmSignatureS3Key();
        String finalSig = a.getFinalSignatureS3Key();
        LocalDateTime ermDate = a.getErmSignatureDate();
        LocalDateTime signedAt = a.getSignedAt();

        WebAgreement out = service.advanceToPhase2(a.getApplicationId(), promotion(false, true, false, false, false, false),
                ERM, request);
        assertSame(a, out);
        assertEquals("SUBMITTED", a.getStatus());
        assertEquals(2, a.getPhase());

        // Cleared.
        assertNull(a.getFinalSignatureS3Key());
        assertNull(a.getFinalSignedAt());
        assertNull(a.getFinalSigningIp());
        assertFalse(affirmed(a, 2), "the promoted appendix only");
        assertNull(a.getErmName());
        assertNull(a.getErmTitle());
        assertNull(a.getErmSignatureS3Key());
        assertNull(a.getSignatureDate());

        // Kept.
        assertEquals("s3:participant-documents/10/web-agreement-consultant-1.png", a.getSignatureS3Key());
        assertEquals("Pat Lee", a.getSignedLegalName());
        assertEquals(signedAt, a.getSignedAt());
        assertEquals(signedAt, a.getSigningAt());
        assertEquals("198.51.100.4", a.getSigningIp());
        assertTrue(a.getAffirmedMainAgreement() && a.getAffirmedExhibitA() && a.getAffirmedExhibitB());
        for (int n : new int[] {1, 3, 4, 5}) assertTrue(affirmed(a, n));
        assertEquals("s3:participant-documents/10/web-agreement-final-p1-1.pdf", a.getS3Key());
        assertEquals("s3:participant-documents/10/web-agreement-final-p1-1.pdf", a.getPhase1FinalPdfS3Key());
        assertEquals(ermDate, a.getErmSignatureDate(), "the Phase 1 countersign date stays (console quirk)");
        assertTrue(a.getConsultantCopyReleased());
        assertEquals("s3:participant-documents/10/web-agreement-consultant-version-p1-1.pdf", a.getConsultantPdfS3Key());
        assertEquals("ab12", a.getDocumentHash());
        assertEquals(1, a.getApprovalVersionNumber());
        assertEquals("Sage IT Co", a.getEmployerPayrollEntity());
        assertEquals("000123", a.getAchAccountNumber());
        assertEquals("123456789", a.getBgFullSsn());
        assertEquals(LocalDate.of(2026, 9, 1), a.getEffectiveDate());
        assertEquals(1, saves.size());
        assertEquals("SUBMITTED", saves.get(0)[0]);

        // The event: the real id, what was promoted, and the two files let go of.
        List<WebAgreementEvent> advanced = eventsOf(a, WebAgreementEvent.EventType.ADVANCED_TO_PHASE_2);
        assertEquals(1, advanced.size());
        WebAgreementEvent e = advanced.get(0);
        assertEquals("ERM", e.getActorType());
        assertEquals(ERM, e.getActorUserId());
        assertEquals("203.0.113.9", e.getIpAddress());
        JsonNode m = meta(e);
        assertEquals(new TreeSet<>(List.of("ermUserId", "promoted", "clearedErmSignatureKey",
                "clearedFinalSignatureKey")), new TreeSet<>(iterable(m)));
        assertEquals(ERM, m.path("ermUserId").asLong());
        assertEquals(ermSig, m.path("clearedErmSignatureKey").asText());
        assertEquals(finalSig, m.path("clearedFinalSignatureKey").asText());
        assertEquals(1, events.size(), "no email event");

        // An admin advancing: the event carries the admin's id; nothing to clear is "".
        WebAgreement b = executed();
        b.setErmSignatureS3Key(null);
        b.setFinalSignatureS3Key(null);
        service.advanceToPhase2(b.getApplicationId(), null, OPS, request);
        JsonNode mb = meta(eventsOf(b, WebAgreementEvent.EventType.ADVANCED_TO_PHASE_2).get(0));
        assertEquals(OPS, mb.path("ermUserId").asLong());
        assertEquals("", mb.path("clearedErmSignatureKey").asText());
        assertEquals("", mb.path("clearedFinalSignatureKey").asText());
    }

    @Test
    void theSsnTrapIsTheConsolesBehaviour() {
        // Phase 1: Appendix 3 required and filled, the SSN not required and blank.
        WebAgreement a = executed();
        a.setRequireAppendix1(false);
        a.setRequireAppendix3(true);
        a.setRequireSsn(false);
        a.setBgFullSsn(null);
        a.setBgFullLegalName("Pat Q Lee");
        a.setBgOtherNamesUsed("None");
        a.setBgCurrentSameAsResidence(true);
        a.setBgDateOfBirth(LocalDate.of(1990, 1, 1));
        a.setBgDriverLicense("D1234");
        a.setDlDocS3Key("s3:participant-documents/10/web-agreement-dl.pdf");

        // The modal's default ticks: every optional section, the SSN included.
        WebAgreementExecutionService.Phase2Promotion body = asTheModalSends(a);
        assertEquals(Boolean.FALSE, body.appendix3, "already required: not sent as a promotion");
        assertEquals(Boolean.TRUE, body.ssn);
        service.advanceToPhase2(a.getApplicationId(), body, ERM, request);

        assertTrue(a.getRequireSsn(), "the SSN is now required");
        Set<String> scope = WebAgreementRules.consultantWriteScope(a).orElseThrow();
        assertFalse(scope.contains("appendix3"), "but Appendix 3 is not reopened");
        assertFalse(scope.contains("ssn"));
        assertEquals("appendix3", WebAgreementRules.FIELD_SECTION.get("bgFullSsn"),
                "so the SSN field is locked for the participant");
        assertTrue(WebAgreementRules.collectMissingConsultantFields(a).contains("bgFullSsn"),
                "while submit still reports it missing");
    }

    // ── Transactions and the routes ──────────────────────────────────

    @Test
    void countersignAndAdvanceAreOneTransactionAndARenderFailureStillCommits() throws Exception {
        Method signM = WebAgreementExecutionService.class.getMethod("approveAndSign", String.class, String.class,
                String.class, String.class, Long.class, HttpServletRequest.class);
        Method advanceM = WebAgreementExecutionService.class.getMethod("advanceToPhase2", String.class,
                WebAgreementExecutionService.Phase2Promotion.class, Long.class, HttpServletRequest.class);
        for (Method m : List.of(signM, advanceM)) {
            Transactional tx = m.getAnnotation(Transactional.class);
            assertNotNull(tx, m.getName());
            assertFalse(tx.readOnly(), m.getName());
        }
        assertTrue(WebAgreementExecutionService.class.getMethod("downloadFinal", String.class, Long.class)
                .getAnnotation(Transactional.class).readOnly());
        assertTrue(WebAgreementExecutionService.class.getMethod("signerProfile", Long.class)
                .getAnnotation(Transactional.class).readOnly());

        CountingManager txManager = new CountingManager();
        ProxyFactory pf = new ProxyFactory(service);
        pf.setProxyTargetClass(true);
        pf.addAdvice(new TransactionInterceptor((TransactionManager) txManager,
                new AnnotationTransactionAttributeSource()));
        WebAgreementExecutionController proxied =
                new WebAgreementExecutionController((WebAgreementExecutionService) pf.getProxy());

        // The render fails inside the countersign: it still commits, COMPLETED.
        doThrow(new WebAgreementRenderer.RenderException(null)).when(renderer).renderFinalPdf(any());
        WebAgreement a = agreement("READY_TO_SIGN", 1);
        WebAgreementExecutionController.ApproveAndSignBody body = new WebAgreementExecutionController.ApproveAndSignBody();
        body.ermName = "Erin Manager";
        body.ermTitle = "Engagement Manager";
        body.ermSignatureBase64 = SIG;
        ResponseEntity<ApiResponse<WebAgreement>> res = proxied.approveAndSign(a.getApplicationId(), body,
                auth(ERM), request);
        assertEquals("Approved and signed", res.getBody().getMessage());
        assertEquals("COMPLETED", res.getBody().getData().getStatus());
        assertEquals(1, txManager.committed);
        assertEquals(0, txManager.rolledBack);

        // A refused countersign rolls back.
        WebAgreement early = agreement("AWAITING_APPROVALS", 1);
        assertThrows(IllegalStateException.class,
                () -> proxied.approveAndSign(early.getApplicationId(), body, auth(ERM), request));
        assertEquals(1, txManager.rolledBack);

        // The advance through the route, with no body.
        WebAgreement done = executed();
        ResponseEntity<ApiResponse<WebAgreement>> adv = proxied.advanceToPhase2(done.getApplicationId(), null,
                auth(ERM), request);
        assertEquals("Advanced to Phase 2", adv.getBody().getMessage());
        assertEquals("SUBMITTED", adv.getBody().getData().getStatus());
        assertEquals(2, txManager.committed);
    }

    @Test
    void theControllerHasTheStaffRolesAndOnlyItsFourRoutes() {
        Class<WebAgreementExecutionController> c = WebAgreementExecutionController.class;
        assertArrayEquals(new String[] {"/api/web-agreements"}, c.getAnnotation(RequestMapping.class).value());
        assertEquals(WebAgreementStaffController.class.getAnnotation(PreAuthorize.class).value(),
                c.getAnnotation(PreAuthorize.class).value());
        Set<String> routes = new TreeSet<>();
        for (Method m : c.getDeclaredMethods()) {
            GetMapping get = m.getAnnotation(GetMapping.class);
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (get != null) routes.add("GET " + get.value()[0]);
            if (post != null) routes.add("POST " + post.value()[0]);
        }
        assertEquals(new TreeSet<>(List.of("GET /signer-profile", "POST /{appId}/approve-and-sign",
                "GET /{appId}/download-pdf", "POST /{appId}/advance-to-phase-2")), routes);
    }

    @Test
    void besideTheStaffRoutesTheLiteralSignerProfileAndTheDownloadReachThisController() throws Exception {
        title(ERM, "Engagement Manager");
        WebAgreementStaffService staff = mock(WebAgreementStaffService.class);
        WebAgreementApprovalService approvals = mock(WebAgreementApprovalService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new WebAgreementStaffController(staff, approvals), controller).build();

        // /signer-profile is not read as the staff detail's /{appId}.
        MvcResult profile = mvc.perform(MockMvcRequestBuilders.get("/api/web-agreements/signer-profile")
                .principal(auth(ERM))).andReturn();
        assertEquals(200, profile.getResponse().getStatus());
        JsonNode data = mapper.readTree(profile.getResponse().getContentAsString()).path("data");
        assertEquals("Erin Manager", data.path("fullName").asText());
        assertEquals("Engagement Manager", data.path("title").asText());

        // /{appId}/download-pdf is ours, not one of the staff document routes.
        WebAgreement a = agreement("READY_TO_SIGN", 1);
        sign(a, ERM);
        MvcResult pdf = mvc.perform(MockMvcRequestBuilders.get("/api/web-agreements/" + a.getApplicationId()
                + "/download-pdf").param("disposition", "attachment").principal(auth(ERM))).andReturn();
        assertEquals(200, pdf.getResponse().getStatus());
        assertArrayEquals(FINAL, pdf.getResponse().getContentAsByteArray());
        verifyNoInteractions(staff, approvals);
    }

    private static List<String> iterable(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static final class CountingManager implements PlatformTransactionManager {
        int committed;
        int rolledBack;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack++;
        }
    }
}
