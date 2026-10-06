package com.spire.backend.service;

import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.exception.IncompleteSubmissionException;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The participant's side of the website agreement: they only ever reach
 * their own agreement, the console's status guards and revision scope
 * hold, submit stamps and emails the owner ERM, consent is recorded once,
 * and uploads are checked like the console's.
 */
class WebAgreementParticipantServiceTest {

    private static final long PAT = 10L;
    private static final long OTHER = 99L;
    private static final long ERM = 20L;
    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
            .getBytes(StandardCharsets.UTF_8);
    private static final String SIG = "data:image/png;base64,iVBORw0KGgo=";

    private final List<WebAgreement> agreements = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private WebAgreementEventService events;
    private WebAgreementFileService files;
    private EmailTemplateService emails;
    private WebAgreementSettings settings;
    private WebAgreementParticipantService service;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        WebAgreementRepository repo = mock(WebAgreementRepository.class);
        when(repo.findByParticipantUserIdAndDeletedFalseOrderByCreatedAtDesc(anyLong()))
                .thenAnswer(inv -> agreements.stream()
                        .filter(a -> a.getParticipantUserId().equals(inv.getArgument(0)))
                        .filter(a -> !Boolean.TRUE.equals(a.getDeleted()))
                        .sorted(Comparator.comparing(WebAgreement::getCreatedAt).reversed())
                        .toList());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> users.stream()
                .filter(u -> u.getId().equals(inv.getArgument(0))).findFirst());
        when(userRepo.findAll()).thenAnswer(inv -> List.copyOf(users));
        events = mock(WebAgreementEventService.class);
        files = mock(WebAgreementFileService.class);
        emails = mock(EmailTemplateService.class);
        when(emails.sendWebAgreementSignedEmail(any(), any())).thenReturn(true);
        settings = mock(WebAgreementSettings.class);
        when(settings.emailsEnabled()).thenReturn(true);
        service = new WebAgreementParticipantService(repo, events, files, mock(WebAgreementRenderer.class),
                mock(AgreementContentService.class), mock(AgreementDocumentService.class), emails, userRepo, settings);

        users.add(user(PAT, "pat@x.com", "PARTICIPANT", true));
        users.add(user(ERM, "erm@sage.test", "ERM", true));
        request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9");
        request.addHeader("User-Agent", "JUnit");
    }

    private static User user(long id, String email, String role, boolean active) {
        return User.builder().id(id).email(email).fullName("User " + id)
                .role(Role.builder().name(role).build()).isActive(active).build();
    }

    private WebAgreement agreement(String status) {
        WebAgreement a = WebAgreementRulesTest.complete();
        a.setId((long) agreements.size() + 1);
        a.setApplicationId("app-" + a.getId());
        a.setParticipantUserId(PAT);
        a.setOwnerUserId(ERM);
        a.setStatus(status);
        a.setCreatedAt(LocalDateTime.now().minusMinutes(10 - agreements.size()));
        agreements.add(a);
        return a;
    }

    // ── Only their own agreement ─────────────────────────────────────

    @Test
    void someoneElseNeverReachesTheAgreement() {
        agreement("SUBMITTED");
        assertNull(service.getMine(OTHER, request));
        assertThrows(ResourceNotFoundException.class,
                () -> service.fill(OTHER, new WebAgreementRules.WebAgreementFillPatch(), request));
        assertThrows(ResourceNotFoundException.class, () -> service.recordConsent(OTHER, request));
        assertThrows(ResourceNotFoundException.class,
                () -> service.submit(OTHER, SIG, SIG, "Pat Lee", request));
        assertThrows(ResourceNotFoundException.class,
                () -> service.uploadDoc(OTHER, WebAgreementRules.Doc.WORKAUTH, PNG, "image/png", request));
        verifyNoInteractions(files);
    }

    @Test
    void theReadSkipsCancelledAgreementsAndCarriesTheRequirements() {
        agreement("CANCELLED");
        assertNull(service.getMine(PAT, request));
        WebAgreement live = agreement("SUBMITTED");
        WebAgreement read = service.getMine(PAT, request);
        assertSame(live, read);
        assertNotNull(read.getEffectiveRequirements());
        verify(events).append(eq(live.getId()), eq(WebAgreementEvent.EventType.ACCESSED),
                eq(WebAgreementEvent.ActorType.PARTICIPANT), eq(PAT), anyMap(), same(request));
    }

    // ── Status guards and scope ──────────────────────────────────────

    @Test
    void onceSignedNothingCanBeChanged() {
        agreement("VERIFIED");
        WebAgreementRules.WebAgreementFillPatch patch = new WebAgreementRules.WebAgreementFillPatch();
        patch.primaryPhone = "555";
        assertThrows(IllegalStateException.class, () -> service.fill(PAT, patch, request));
        assertThrows(IllegalStateException.class, () -> service.submit(PAT, SIG, SIG, "Pat Lee", request));
        assertThrows(IllegalStateException.class,
                () -> service.uploadDoc(PAT, WebAgreementRules.Doc.WORKAUTH, PNG, "image/png", request));
        assertThrows(IllegalStateException.class,
                () -> service.setChequeMetadata(PAT, 0, new WebAgreementRules.ChequeMetadataPatch(), request));
        assertThrows(IllegalStateException.class, () -> service.previewImages(PAT, null));
    }

    @Test
    void aRevisionRoundOnlyOpensItsOwnSections() {
        WebAgreement a = agreement("REVISION_REQUESTED");
        a.setRevisionSections("[{\"key\":\"appendix2\"}]");
        WebAgreementRules.WebAgreementFillPatch outside = new WebAgreementRules.WebAgreementFillPatch();
        outside.primaryPhone = "555 000 0000";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.fill(PAT, outside, request));
        assertTrue(e.getMessage().contains("primaryPhone"));
        assertEquals("555 201 3344", a.getPrimaryPhone());

        WebAgreementRules.WebAgreementFillPatch inside = new WebAgreementRules.WebAgreementFillPatch();
        inside.achBankName = "Chase";
        service.fill(PAT, inside, request);
        assertEquals("Chase", a.getAchBankName());
        verify(events).append(eq(a.getId()), eq(WebAgreementEvent.EventType.CONSULTANT_FILLED),
                eq(WebAgreementEvent.ActorType.PARTICIPANT), eq(PAT), anyMap(), same(request));
    }

    @Test
    void aDocumentRoundUnlocksOnlyThatDocument() {
        WebAgreement a = agreement("REVISION_REQUESTED");
        a.setRevisionSections("[{\"key\":\"doc:dl-doc\"}]");
        when(files.storeUpload(any(), anyString(), any(), anyString())).thenReturn("stored-dl");
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadDoc(PAT, WebAgreementRules.Doc.STATE_ID_DOC, PNG, "image/png", request));
        service.uploadDoc(PAT, WebAgreementRules.Doc.DL_DOC, PNG, "image/png", request);
        assertEquals("stored-dl", a.getDlDocS3Key());
    }

    @Test
    void fillRecomposesTheName() {
        WebAgreement a = agreement("SUBMITTED");
        WebAgreementRules.WebAgreementFillPatch patch = new WebAgreementRules.WebAgreementFillPatch();
        patch.middleName = "Q";
        service.fill(PAT, patch, request);
        assertEquals("Pat Q Lee", a.getConsultantName());
    }

    // ── Submit ───────────────────────────────────────────────────────

    @Test
    void submitStampsEverythingAndEmailsTheOwnerErm() throws Exception {
        WebAgreement a = agreement("REVISION_REQUESTED");
        a.setRevisionSections("[{\"key\":\"signature\"}]");
        a.setRevisionPrevStatus("VERIFIED");
        a.setRevisionUndoSnapshot("{}");
        a.setConsultantCopyReleased(true);
        when(files.storeSignature(any(), eq(SIG), eq("consultant"))).thenReturn("sig-primary");
        when(files.storeSignature(any(), eq(SIG), eq("consultant-final"))).thenReturn("sig-final");

        WebAgreement out = service.submit(PAT, SIG, SIG, "  Pat Lee ", request);

        assertEquals("VERIFIED", out.getStatus());
        assertEquals("sig-primary", out.getSignatureS3Key());
        assertEquals("sig-final", out.getFinalSignatureS3Key());
        assertEquals("Pat Lee", out.getSignedLegalName());
        assertEquals("203.0.113.9", out.getSignedIp(), "the proxy-appended last hop, not the spoofable first");
        assertEquals("203.0.113.9", out.getFinalSigningIp());
        assertEquals("JUnit", out.getSignedUserAgent());
        assertNotNull(out.getSignatureDate());
        assertTrue(out.getSectionSignatureDates().contains("main-agreement"));
        assertNull(out.getRevisionSections());
        assertNull(out.getRevisionUndoSnapshot());
        assertFalse(out.getConsultantCopyReleased());
        verify(events).append(eq(a.getId()), eq(WebAgreementEvent.EventType.SIGNED),
                eq(WebAgreementEvent.ActorType.PARTICIPANT), eq(PAT), anyMap(), same(request));
        User erm = users.get(1);
        verify(emails).sendWebAgreementSignedEmail(a, erm);
        verify(events).append(eq(a.getId()), eq(WebAgreementEvent.EventType.EMAIL_SENT),
                eq(WebAgreementEvent.ActorType.SYSTEM), isNull(), anyMap(), isNull());
    }

    @Test
    void anInactiveOwnerHandsTheReviewToAnOperationsAdmin() throws Exception {
        agreement("SUBMITTED");
        users.get(1).setIsActive(false);
        User ops = user(30L, "ops@sage.test", "OPERATIONS_ADMIN", true);
        users.add(user(31L, "sys@sage.test", "SYSTEM_ADMIN", true));
        users.add(ops);
        when(files.storeSignature(any(), any(), any())).thenReturn("sig");
        service.submit(PAT, SIG, SIG, "Pat Lee", request);
        verify(emails).sendWebAgreementSignedEmail(any(), eq(ops));
    }

    @Test
    void withEmailsOffTheSubmitTellsNobody() throws Exception {
        WebAgreement a = agreement("SUBMITTED");
        when(settings.emailsEnabled()).thenReturn(false);
        when(files.storeSignature(any(), any(), any())).thenReturn("sig");
        service.submit(PAT, SIG, SIG, "Pat Lee", request);
        assertEquals("VERIFIED", a.getStatus());
        verifyNoInteractions(emails);
        verify(events, never()).append(anyLong(), eq(WebAgreementEvent.EventType.EMAIL_SENT),
                any(), any(), anyMap(), any());
    }

    @Test
    void aFailedEmailNeverUndoesTheSubmit() throws Exception {
        WebAgreement a = agreement("SUBMITTED");
        when(files.storeSignature(any(), any(), any())).thenReturn("sig");
        when(emails.sendWebAgreementSignedEmail(any(), any())).thenThrow(new RuntimeException("smtp down"));
        service.submit(PAT, SIG, SIG, "Pat Lee", request);
        assertEquals("VERIFIED", a.getStatus());
        verify(events, never()).append(anyLong(), eq(WebAgreementEvent.EventType.EMAIL_SENT),
                any(), any(), anyMap(), any());
    }

    @Test
    void anIncompleteAgreementIsRefusedWithWhatIsMissing() throws Exception {
        WebAgreement a = agreement("SUBMITTED");
        a.setFirstName(null);
        a.setAffirmedExhibitA(false);
        IncompleteSubmissionException e = assertThrows(IncompleteSubmissionException.class,
                () -> service.submit(PAT, null, "not-a-data-url", "Pat Lee", request));
        assertTrue(e.getMissingFields().contains("firstName"));
        assertTrue(e.getMissingAffirmations().contains("affirmedExhibitA"));
        assertTrue(e.isMissingSignature());
        assertTrue(e.isMissingFinalSignature());
        assertEquals("SUBMITTED", a.getStatus());
        verify(files, never()).storeSignature(any(), any(), any());
        verifyNoInteractions(emails);
    }

    @Test
    void aStoredPrimarySignatureCanBeReused() throws Exception {
        WebAgreement a = agreement("SUBMITTED");
        a.setSignatureS3Key("sig-old");
        when(files.storeSignature(any(), eq(SIG), eq("consultant-final"))).thenReturn("sig-final");
        service.submit(PAT, null, SIG, "Pat Lee", request);
        assertEquals("sig-old", a.getSignatureS3Key());
        verify(files, never()).storeSignature(any(), any(), eq("consultant"));
    }

    @Test
    void aBlankLegalNameIsRefused() {
        agreement("SUBMITTED");
        assertThrows(IllegalArgumentException.class, () -> service.submit(PAT, SIG, SIG, "  ", request));
    }

    // ── Consent ──────────────────────────────────────────────────────

    @Test
    void consentIsRecordedOnce() {
        WebAgreement a = agreement("SUBMITTED");
        service.recordConsent(PAT, request);
        LocalDateTime given = a.getConsentGivenAt();
        assertNotNull(given);
        assertEquals("v1.0", a.getConsentVersion());
        assertEquals("203.0.113.9", a.getConsentIp());

        MockHttpServletRequest later = new MockHttpServletRequest();
        later.setRemoteAddr("10.0.0.1");
        service.recordConsent(PAT, later);
        assertEquals(given, a.getConsentGivenAt());
        assertEquals("203.0.113.9", a.getConsentIp());
        verify(events, times(1)).append(anyLong(), eq(WebAgreementEvent.EventType.CONSENT_GIVEN),
                any(), any(), anyMap(), any());
    }

    // ── Uploads ──────────────────────────────────────────────────────

    @Test
    void uploadsAreCheckedLikeTheConsole() {
        agreement("SUBMITTED");
        IllegalArgumentException svg = assertThrows(IllegalArgumentException.class,
                () -> service.uploadDoc(PAT, WebAgreementRules.Doc.WORKAUTH, SVG, "image/svg+xml", request));
        assertEquals("Work-authorization document must be an image (JPG/PNG/HEIC) or PDF.", svg.getMessage());
        IllegalArgumentException disguised = assertThrows(IllegalArgumentException.class,
                () -> service.uploadDoc(PAT, WebAgreementRules.Doc.SSN_DOC, SVG, "image/png", request));
        assertEquals("SSN document must be an image (JPG/PNG/HEIC) or PDF.", disguised.getMessage());
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> service.uploadDoc(PAT, WebAgreementRules.Doc.OFFER_LETTER, new byte[0], "image/png", request));
        assertEquals("Offer letter file is empty.", empty.getMessage());
        byte[] huge = new byte[(int) WebAgreementRules.MAX_UPLOAD_BYTES + 1];
        System.arraycopy(PNG, 0, huge, 0, PNG.length);
        IllegalArgumentException big = assertThrows(IllegalArgumentException.class,
                () -> service.uploadDoc(PAT, WebAgreementRules.Doc.WORKAUTH, huge, "image/png", request));
        assertEquals("Work-authorization file is too large (>10 MB).", big.getMessage());
        verify(files, never()).storeUpload(any(), any(), any(), any());
    }

    @Test
    void aGoodUploadIsStoredRecordedAndAudited() {
        WebAgreement a = agreement("SUBMITTED");
        a.setWorkAuthDocS3Key(null);
        when(files.storeUpload(same(a), eq("workauth"), same(PNG), eq("image/png"))).thenReturn("stored-wa");
        service.uploadDoc(PAT, WebAgreementRules.Doc.WORKAUTH, PNG, "IMAGE/PNG", request);
        assertEquals("stored-wa", a.getWorkAuthDocS3Key());
        assertEquals("image/png", a.getWorkAuthDocContentType());
        assertNotNull(a.getWorkAuthDocUploadedAt());
        verify(events).append(eq(a.getId()), eq(WebAgreementEvent.EventType.WORK_AUTH_UPLOADED),
                eq(WebAgreementEvent.ActorType.PARTICIPANT), eq(PAT), anyMap(), same(request));
    }

    @Test
    void chequeZeroIsMirroredAndItsNumberSurvivesANewFile() {
        WebAgreement a = agreement("SUBMITTED");
        service.setChequeMetadata(PAT, 0, metadata("1001", "2026-09-15"), request);
        when(files.storeUpload(any(), eq("cheque-0"), any(), eq("image/png"))).thenReturn("stored-c0");
        service.uploadChequeAt(PAT, 0, PNG, "image/png", request);
        WebAgreementRules.ChequeEntry e = WebAgreementRules.parseCheques(a).get(0);
        assertEquals("1001", e.number());
        assertEquals("stored-c0", e.s3Key());
        assertEquals("stored-c0", a.getChequeS3Key());
        assertEquals("image/png", a.getChequeContentType());
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChequeAt(PAT, 51, PNG, "image/png", request));
        verify(events).append(eq(a.getId()), eq(WebAgreementEvent.EventType.CHEQUE_METADATA_UPDATED),
                eq(WebAgreementEvent.ActorType.PARTICIPANT), eq(PAT), anyMap(), same(request));
    }

    private static WebAgreementRules.ChequeMetadataPatch metadata(String number, String date) {
        WebAgreementRules.ChequeMetadataPatch p = new WebAgreementRules.ChequeMetadataPatch();
        p.number = number;
        p.date = date;
        return p;
    }

    @Test
    void readingADocumentNamesItAfterTheAgreement() {
        WebAgreement a = agreement("SUBMITTED");
        WebAgreementFileService.Download d = new WebAgreementFileService.Download(PNG, "image/png", "x.png");
        when(files.download(eq(a.getWorkAuthDocS3Key()), any(), eq("SageITCO-WorkAuth_" + a.getApplicationId())))
                .thenReturn(d);
        assertSame(d, service.readDoc(PAT, WebAgreementRules.Doc.WORKAUTH));
        // No cheque on file at either index.
        assertNull(service.readCheque(PAT, 0));
        assertNull(service.readCheque(PAT, 3));
    }
}
