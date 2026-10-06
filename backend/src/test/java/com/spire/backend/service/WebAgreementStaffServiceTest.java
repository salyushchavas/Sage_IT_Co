package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.entity.AgreementRequest;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementRequestRepository;
import com.spire.backend.repository.ConsultantApplicationRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementEventRepository;
import com.spire.backend.repository.WebAgreementRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The staff side of the website agreement: who may start one and for whom,
 * ERMs only reach their own while admins reach all, lists carry no
 * sensitive PII, the three kinds of change request do what the console's
 * do, a request can be taken back until the participant acts on it, and
 * the ERM's verify is a one-time step on a signed agreement.
 */
class WebAgreementStaffServiceTest {

    private static final long PAT = 10L;
    private static final long ERM = 20L;
    private static final long OTHER_ERM = 21L;
    private static final long OPS = 30L;
    private static final long SYS = 31L;
    private static final long COACH = 40L;

    private final List<WebAgreement> agreements = new ArrayList<>();
    private final List<WebAgreementEvent> events = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    /** Website-agreement emails; on for most tests so the email paths stay covered. */
    private boolean emailsOn = true;
    private final List<AgreementRequest> requests = new ArrayList<>();
    private final java.util.Set<Long> notAsked = new java.util.HashSet<>();
    private AgreementRequestRepository requestRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private EmailTemplateService emails;
    private WebAgreementRenderer renderer;
    private WebAgreementRepository repo;
    private EntityManager entityManager;
    private WebAgreementStaffService service;
    private MockHttpServletRequest request;
    private User pat;

    @BeforeEach
    void setUp() {
        repo = mock(WebAgreementRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            WebAgreement a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId((long) agreements.size() + 1);
                a.setCreatedAt(LocalDateTime.now());
                agreements.add(a);
            }
            return a;
        });
        when(repo.findByApplicationId(anyString())).thenAnswer(inv -> agreements.stream()
                .filter(a -> a.getApplicationId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByParticipantUserIdAndDeletedFalseOrderByCreatedAtDesc(anyLong()))
                .thenAnswer(inv -> live(a -> a.getParticipantUserId().equals(inv.getArgument(0))));
        when(repo.findForUpdateByParticipantUserIdAndDeletedFalse(anyLong()))
                .thenAnswer(inv -> live(a -> a.getParticipantUserId().equals(inv.getArgument(0))));
        when(repo.findByParticipantUserIdInAndDeletedFalse(any())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return live(a -> ids.contains(a.getParticipantUserId()));
        });
        when(repo.findByDeletedFalse(any())).thenAnswer(inv -> page(live(a -> true), inv.getArgument(0)));
        when(repo.findByStatusAndDeletedFalse(anyString(), any())).thenAnswer(inv ->
                page(live(a -> a.getStatus().equals(inv.getArgument(0))), inv.getArgument(1)));
        when(repo.findByOwnerUserIdAndDeletedFalse(anyLong(), any())).thenAnswer(inv ->
                page(live(a -> a.getOwnerUserId().equals(inv.getArgument(0))), inv.getArgument(1)));
        when(repo.findByOwnerUserIdAndStatusAndDeletedFalse(anyLong(), anyString(), any())).thenAnswer(inv ->
                page(live(a -> a.getOwnerUserId().equals(inv.getArgument(0))
                        && a.getStatus().equals(inv.getArgument(1))), inv.getArgument(2)));

        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> users.stream()
                .filter(u -> u.getId().equals(inv.getArgument(0))).findFirst());
        when(userRepo.findAll()).thenAnswer(inv -> List.copyOf(users));
        when(userRepo.findAllById(any())).thenAnswer(inv -> {
            Iterable<?> ids = inv.getArgument(0);
            List<Object> wanted = new ArrayList<>();
            ids.forEach(wanted::add);
            return users.stream().filter(u -> wanted.contains(u.getId())).toList();
        });

        WebAgreementEventRepository eventRepo = mock(WebAgreementEventRepository.class);
        when(eventRepo.save(any())).thenAnswer(inv -> {
            WebAgreementEvent e = inv.getArgument(0);
            if (e.getCreatedAt() == null) e.setCreatedAt(LocalDateTime.now());
            events.add(e);
            return e;
        });
        when(eventRepo.findByAgreementIdOrderByCreatedAtDesc(anyLong())).thenAnswer(inv -> events.stream()
                .filter(e -> e.getAgreementId().equals(inv.getArgument(0))).toList());
        when(eventRepo.findByAgreementIdAndEventTypeInAndCreatedAtAfter(anyLong(), any(), any()))
                .thenAnswer(inv -> {
                    Collection<?> types = inv.getArgument(1);
                    LocalDateTime after = inv.getArgument(2);
                    return events.stream()
                            .filter(e -> e.getAgreementId().equals(inv.getArgument(0)))
                            .filter(e -> types.contains(e.getEventType()))
                            .filter(e -> e.getCreatedAt().isAfter(after))
                            .toList();
                });

        AgreementRequestRepository requestRepo = mock(AgreementRequestRepository.class);
        when(requestRepo.findAllByOrderByRequestedAtAsc()).thenAnswer(inv -> List.copyOf(requests));
        when(requestRepo.findByUserId(anyLong())).thenAnswer(inv -> requests.stream()
                .filter(r -> r.getUserId().equals(inv.getArgument(0))).findFirst());
        // Starting an agreement locks the participant's "I'm ready" row; everyone asked unless a test says not.
        when(requestRepo.findForUpdateByUserId(anyLong())).thenAnswer(inv -> notAsked.contains(inv.<Long>getArgument(0))
                ? Optional.empty()
                : Optional.of(AgreementRequest.builder().userId(inv.getArgument(0)).requestedAt(LocalDateTime.now()).build()));
        this.requestRepo = requestRepo;
        ProgramSelectionRepository programs = mock(ProgramSelectionRepository.class);
        when(programs.findFirstByUserIdOrderBySelectionDateDesc(anyLong())).thenReturn(Optional.empty());

        emails = mock(EmailTemplateService.class);
        when(emails.sendWebAgreementReadyToFill(any())).thenReturn(true);
        when(emails.sendWebAgreementRevisionRequest(any(), any())).thenReturn(true);
        when(emails.sendWebAgreementRevisionWithdrawn(any())).thenReturn(true);
        when(emails.sendWebAgreementVerifiedEmail(any())).thenReturn(true);
        renderer = mock(WebAgreementRenderer.class);

        WebAgreementSettings settings = mock(WebAgreementSettings.class);
        when(settings.emailsEnabled()).thenAnswer(inv -> emailsOn);
        MasterAgreementService master = new MasterAgreementService(requestRepo, repo, userRepo,
                programs, emails, mock(RecordService.class), settings);
        service = new WebAgreementStaffService(repo, new WebAgreementEventService(eventRepo),
                mock(WebAgreementFileService.class), renderer, master, emails, userRepo, requestRepo, settings);
        entityManager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        pat = User.builder().id(PAT).email("Pat.Lee@x.com").fullName("Pat Q Lee")
                .participantId("SAGE-2026-00010").role(Role.builder().name("PARTICIPANT").build())
                .isActive(true).agreementComplete(true).build();
        users.add(pat);
        users.add(user(ERM, "ERM"));
        users.add(user(OTHER_ERM, "ERM"));
        users.add(user(OPS, "OPERATIONS_ADMIN"));
        users.add(user(SYS, "SYSTEM_ADMIN"));
        users.add(user(COACH, "COACH"));

        request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9");
        request.addHeader("User-Agent", "JUnit");
    }

    private static User user(long id, String role) {
        return User.builder().id(id).email("u" + id + "@sage.test").fullName("Staff " + id)
                .role(Role.builder().name(role).build()).isActive(true).build();
    }

    private List<WebAgreement> live(Predicate<WebAgreement> filter) {
        return agreements.stream().filter(a -> !Boolean.TRUE.equals(a.getDeleted())).filter(filter).toList();
    }

    private static Page<WebAgreement> page(List<WebAgreement> rows, Pageable pageable) {
        return new PageImpl<>(rows, pageable, rows.size());
    }

    /** A participant-signed agreement owned by ERM, with everything an ERM can send back. */
    private WebAgreement signed() {
        WebAgreement a = WebAgreementRulesTest.complete();
        a.setId((long) agreements.size() + 1);
        a.setApplicationId("app-" + a.getId());
        a.setParticipantUserId(PAT);
        a.setOwnerUserId(ERM);
        a.setStatus("VERIFIED");
        a.setCreatedAt(LocalDateTime.now().minusDays(1));
        a.setRatePeriod1("Months 1-12");
        a.setRateAmount1("$500");
        a.setRatePeriod2("Months 13-18");
        a.setRateAmount2("$700");
        a.setAchDebitDates("15th of every month");
        a.setAchDebitAmounts("$416.67");
        a.setPhase2DeliverablePeriod("Monthly");
        a.setAffirmedAppendix1(true);
        a.setAffirmedAppendix2(true);
        a.setAffirmedAppendix3(true);
        a.setAffirmedAppendix4(true);
        a.setAffirmedAppendix5(true);
        a.setSignatureS3Key("sig-primary");
        a.setFinalSignatureS3Key("sig-final");
        a.setSigningAt(LocalDateTime.of(2026, 10, 1, 9, 30));
        a.setSigningIp("203.0.113.9");
        a.setFinalSignedAt(LocalDateTime.of(2026, 10, 1, 9, 31));
        a.setFinalSigningIp("203.0.113.9");
        a.setSignatureDate(LocalDateTime.of(2026, 10, 1, 9, 31));
        a.setWorkAuthDocContentType("application/pdf");
        a.setWorkAuthDocUploadedAt(LocalDateTime.of(2026, 9, 30, 12, 0));
        a.setDlDocS3Key("dl-file");
        a.setCheques("[{\"index\":0,\"number\":\"1001\",\"date\":\"2026-11-01\",\"s3Key\":\"cheque-file\","
                + "\"contentType\":\"image/png\",\"uploadedAt\":\"2026-09-30T12:00\"}]");
        a.setChequeS3Key("cheque-file");
        a.setBgFullSsn("123456789");
        a.setBgDriverLicense("D1234");
        a.setAchRoutingNumber("021000021");
        a.setAchAccountNumber("000123");
        a.setBgDateOfBirth(LocalDate.of(1990, 1, 1));
        agreements.add(a);
        return a;
    }

    private CreateBuilder body() {
        return new CreateBuilder();
    }

    /** Small builder over the create form's body. */
    private static final class CreateBuilder {
        final WebAgreementStaffService.CreateBody b = new WebAgreementStaffService.CreateBody();

        CreateBuilder() {
            b.participantUserId = PAT;
            b.consultantEmail = "  Pat.Lee@X.com ";
            b.firstName = "Pat";
            b.middleName = "Q";
            b.lastName = "Lee";
            b.ratePeriod1 = "Months 1-12";
            b.rateAmount1 = "$500";
            b.visaStatus = "H1B";
            b.visaStatusOther = "ignored unless Others";
            b.requireAppendix3 = true;
            b.technologyTrack = "Cloud & DevOps";
        }
    }

    private List<WebAgreementEvent> eventsOf(WebAgreement a, WebAgreementEvent.EventType type) {
        return events.stream()
                .filter(e -> e.getAgreementId().equals(a.getId()) && e.getEventType().equals(type.name()))
                .toList();
    }

    private JsonNode sections(String json) throws Exception {
        return mapper.readTree(json);
    }

    // ── Create ───────────────────────────────────────────────────────

    @Test
    void createStartsItForTheCallerAndEmailsTheParticipant() {
        WebAgreement a = service.create(body().b, ERM, request);

        assertEquals("SUBMITTED", a.getStatus());
        assertEquals(PAT, a.getParticipantUserId());
        assertEquals(ERM, a.getOwnerUserId(), "the caller owns it");
        assertEquals("pat.lee@x.com", a.getConsultantEmail());
        assertEquals("Pat Q Lee", a.getConsultantName());
        assertEquals("H1B", a.getWorkAuthorizationCategory());
        assertNull(a.getWorkAuthorizationOther(), "only kept for Others");
        assertTrue(a.getRequireAppendix3());
        assertFalse(a.getRequireAppendix1());
        assertEquals(LocalDate.now(), a.getEffectiveDate());
        assertNotNull(a.getApplicationId());

        WebAgreementEvent created = eventsOf(a, WebAgreementEvent.EventType.CREATED).get(0);
        assertEquals("ERM", created.getActorType());
        assertEquals(ERM, created.getActorUserId(), "the real users.id, not a sentinel");
        assertEquals("203.0.113.9", created.getIpAddress());
        verify(emails).sendWebAgreementReadyToFill(a);
        assertEquals(1, eventsOf(a, WebAgreementEvent.EventType.EMAIL_SENT).size());
    }

    @Test
    void aFailedEmailNeverUndoesTheCreate() {
        when(emails.sendWebAgreementReadyToFill(any())).thenReturn(false);
        WebAgreement a = service.create(body().b, ERM, request);
        assertEquals("SUBMITTED", a.getStatus());
        assertTrue(eventsOf(a, WebAgreementEvent.EventType.EMAIL_SENT).isEmpty());
    }

    @Test
    void aSecondOpenAgreementIsRefusedButACancelledOneDoesNotCount() {
        WebAgreement first = service.create(body().b, ERM, request);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.create(body().b, OTHER_ERM, request));
        assertTrue(e.getMessage().contains("already has an open agreement"));

        first.setStatus("CANCELLED");
        assertDoesNotThrow(() -> service.create(body().b, OTHER_ERM, request));
    }

    @Test
    void theOfficeConsoleIsNeverConsulted() {
        // The website copy has no contact with the office's agreements console:
        // creating needs only the website's own records.
        assertDoesNotThrow(() -> service.create(body().b, ERM, request));
    }

    @Test
    void createChecksWhoItIsForAndWhoIsAsking() {
        CreateBuilder unknown = body();
        unknown.b.participantUserId = 999L;
        assertThrows(ResourceNotFoundException.class, () -> service.create(unknown.b, ERM, request));

        CreateBuilder notParticipant = body();
        notParticipant.b.participantUserId = COACH;
        assertThrows(IllegalArgumentException.class, () -> service.create(notParticipant.b, ERM, request));

        CreateBuilder noEmail = body();
        noEmail.b.consultantEmail = " ";
        assertThrows(IllegalArgumentException.class, () -> service.create(noEmail.b, ERM, request));

        assertThrows(AccessDeniedException.class, () -> service.create(body().b, COACH, request));
        assertThrows(AccessDeniedException.class, () -> service.create(body().b, PAT, request));

        pat.setAgreementComplete(false);
        assertThrows(IllegalStateException.class, () -> service.create(body().b, ERM, request));
        assertTrue(agreements.isEmpty());
        verifyNoInteractions(emails);
    }

    @Test
    void anInactiveAccountGetsNoAgreement() {
        pat.setIsActive(false);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.create(body().b, ERM, request));
        assertTrue(e.getMessage().contains("inactive"));
        assertTrue(agreements.isEmpty());
        verifyNoInteractions(emails);
    }

    @Test
    void twoStartsForOneParticipantQueueAndTheCheckSeesTheLatest() {
        service.create(body().b, ERM, request);
        // The participant's "I'm ready" row is locked before the check (never
        // their account), and the check is the locking read (not the plain one
        // a stale snapshot could answer).
        verify(requestRepo).findForUpdateByUserId(PAT);
        verify(entityManager, never()).lock(any(), any());
        verify(repo).findForUpdateByParticipantUserIdAndDeletedFalse(PAT);
        verify(repo, never()).findByParticipantUserIdAndDeletedFalseOrderByCreatedAtDesc(anyLong());
    }

    @Test
    void someoneWhoHasntAskedGetsNoAgreement() {
        notAsked.add(PAT);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.create(body().b, ERM, request));
        assertTrue(e.getMessage().contains("asked"));
        assertTrue(agreements.isEmpty());
        verifyNoInteractions(emails);
    }

    @Test
    void everyStaffCallerSeesWhoIsReady() {
        requests.add(AgreementRequest.builder().userId(PAT).requestedAt(LocalDateTime.now()).build());
        assertEquals(1, service.requests(OTHER_ERM).size());
        assertEquals(1, service.requests(OPS).size());
        assertEquals("Pat", service.request(ERM, PAT).firstName());
        assertThrows(AccessDeniedException.class, () -> service.requests(COACH));
        assertThrows(AccessDeniedException.class, () -> service.request(COACH, PAT));

        service.create(body().b, ERM, request);
        assertTrue(service.requests(OTHER_ERM).isEmpty(), "started: no longer waiting");
        assertThrows(ResourceNotFoundException.class, () -> service.request(OTHER_ERM, PAT),
                "started: nothing to prefill");
    }

    @Test
    void theCreateFormOnlyReadsSomeoneOnTheReadyList() {
        // Not asked yet.
        assertThrows(ResourceNotFoundException.class, () -> service.request(ERM, PAT));
        // Staff accounts and unknown ids look the same: not found.
        for (long id : new long[] {OTHER_ERM, OPS, SYS, COACH, 999L}) {
            requests.add(AgreementRequest.builder().userId(id).requestedAt(LocalDateTime.now()).build());
            ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                    () -> service.request(ERM, id));
            assertEquals("This participant isn't waiting for an agreement.", e.getMessage());
        }

        requests.add(AgreementRequest.builder().userId(PAT).requestedAt(LocalDateTime.now()).build());
        assertEquals("SAGE-2026-00010", service.request(ERM, PAT).participantId());
        pat.setIsActive(false);
        assertThrows(ResourceNotFoundException.class, () -> service.request(ERM, PAT), "inactive");
        pat.setIsActive(true);
        service.create(body().b, ERM, request);
        assertThrows(ResourceNotFoundException.class, () -> service.request(ERM, PAT), "already started");
    }

    // ── Who sees what ────────────────────────────────────────────────

    @Test
    void anotherErmGetsNotFoundWhileAdminsReachEveryAgreement() {
        WebAgreement a = signed();
        assertThrows(ResourceNotFoundException.class, () -> service.detail(a.getApplicationId(), OTHER_ERM));
        assertThrows(ResourceNotFoundException.class, () -> service.verify(a.getApplicationId(), OTHER_ERM, request));
        assertThrows(ResourceNotFoundException.class, () -> service.cancel(a.getApplicationId(), OTHER_ERM, request));
        assertThrows(ResourceNotFoundException.class,
                () -> service.readDoc(a.getApplicationId(), WebAgreementRules.Doc.WORKAUTH, OTHER_ERM));
        assertThrows(AccessDeniedException.class, () -> service.detail(a.getApplicationId(), COACH));

        Map<String, Object> view = service.detail(a.getApplicationId(), OPS);
        assertSame(a, view.get("application"));
        assertEquals("123456789", a.getBgFullSsn(), "the detail keeps the PII");
        assertEquals("Staff 20", a.getOwnerName());
        assertNotNull(view.get("events"));
        assertDoesNotThrow(() -> service.verify(a.getApplicationId(), SYS, request));
        assertEquals(String.valueOf(SYS), a.getConsultantCopyReleasedBy());

        a.setDeleted(true);
        assertThrows(ResourceNotFoundException.class, () -> service.detail(a.getApplicationId(), SYS),
                "archived is gone for everyone");
    }

    @Test
    void theListIsTheErmsOwnForAnErmEveryoneForAdminsAndHasNoSensitivePii() {
        WebAgreement mine = signed();
        WebAgreement theirs = signed();
        theirs.setOwnerUserId(OTHER_ERM);
        Pageable p = PageRequest.of(0, 20);

        List<WebAgreement> ermRows = service.list(null, p, ERM).getContent();
        assertEquals(List.of(mine), ermRows);
        assertEquals(2, service.list("ALL", p, OPS).getContent().size());
        assertEquals(2, service.list("VERIFIED", p, SYS).getContent().size());
        assertTrue(service.list("SUBMITTED", p, ERM).getContent().isEmpty());

        for (WebAgreement row : List.of(mine, theirs)) {
            assertNull(row.getBgFullSsn());
            assertNull(row.getBgDriverLicense());
            assertNull(row.getAchRoutingNumber());
            assertNull(row.getAchAccountNumber());
            assertNull(row.getBgDateOfBirth());
        }
        assertEquals("Staff 20", mine.getOwnerName());
        assertEquals("Staff 21", theirs.getOwnerName());
        verify(entityManager, atLeastOnce()).detach(mine);
        verify(repo).findByOwnerUserIdAndDeletedFalse(eq(ERM), any());
    }

    // ── Change requests ──────────────────────────────────────────────

    @Test
    void aSectionRevisionAddsTheCorrectedSectionsAndReArmsTheirTicks() throws Exception {
        WebAgreement a = signed();
        WebAgreement out = service.requestRevision(a.getApplicationId(),
                sections("[{\"key\":\"exhibit-a\",\"note\":\"Wrong track\"},{\"key\":\"bogus\"}]"),
                "1st of every month", null,      // ACH changed → appendix2
                null, "$550", null, null,        // rate changed → main-agreement
                "Quarterly",                     // deliverables changed → appendix1
                ERM, request);

        assertEquals("REVISION_REQUESTED", out.getStatus());
        assertEquals(1, out.getRevisionCount());
        assertEquals(List.of("exhibit-a", "appendix2", "main-agreement", "appendix1"),
                List.copyOf(WebAgreementRules.parseSectionScopeKeys(out.getRevisionSections())));
        assertEquals("1st of every month", out.getAchDebitDates());
        assertEquals("$416.67", out.getAchDebitAmounts(), "not sent: unchanged");
        assertEquals("$550", out.getRateAmount1());
        assertEquals("Quarterly", out.getPhase2DeliverablePeriod());
        assertFalse(out.getAffirmedExhibitA());
        assertFalse(out.getAffirmedAppendix2());
        assertFalse(out.getAffirmedMainAgreement());
        assertFalse(out.getAffirmedAppendix1());
        assertTrue(out.getAffirmedExhibitB(), "not in scope: kept");
        assertTrue(out.getAffirmedAppendix3());
        assertTrue(out.getCurrentRevisionRemarks().startsWith("Please revise: Exhibit A (Wrong track); "));
        assertEquals("VERIFIED", out.getRevisionPrevStatus());
        assertNotNull(out.getRevisionUndoSnapshot());
        assertEquals(ERM, eventsOf(a, WebAgreementEvent.EventType.REVISION_REQUESTED).get(0).getActorUserId());
        verify(emails).sendWebAgreementRevisionRequest(eq(a), eq(out.getCurrentRevisionRemarks()));
    }

    @Test
    void unchangedCorrectionsOpenNothingAndAnEmptyRequestIsRefused() {
        WebAgreement a = signed();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.requestRevision(a.getApplicationId(), null,
                        "15th of every month", "$416.67", "Months 1-12", "$500", null, null, "Monthly",
                        ERM, request));
        assertEquals("Select at least one section to revise.", e.getMessage());
    }

    @Test
    void aRevisionNeedsASignedAgreementButIsStillAllowedAfterVerify() throws Exception {
        WebAgreement a = signed();
        a.setStatus("SUBMITTED");
        assertThrows(IllegalStateException.class, () -> service.requestRevision(a.getApplicationId(),
                sections("[{\"key\":\"cover\"}]"), null, null, null, null, null, null, null, ERM, request));
        assertThrows(IllegalStateException.class,
                () -> service.requestSignatureRevision(a.getApplicationId(), null, ERM, request));
        assertThrows(IllegalStateException.class, () -> service.requestDocumentRevision(
                a.getApplicationId(), List.of("doc:workauth"), null, ERM, request));

        a.setStatus("VERIFIED");
        a.setConsultantCopyReleased(true);
        assertDoesNotThrow(() -> service.requestRevision(a.getApplicationId(),
                sections("[{\"key\":\"cover\"}]"), null, null, null, null, null, null, null, ERM, request));
    }

    @Test
    void aSignatureRevisionClearsBothSignaturesOnly() {
        WebAgreement a = signed();
        WebAgreement out = service.requestSignatureRevision(a.getApplicationId(), " Please sign clearly ",
                ERM, request);
        assertNull(out.getSignatureS3Key());
        assertNull(out.getFinalSignatureS3Key());
        assertNull(out.getSigningAt());
        assertNull(out.getFinalSignedAt());
        assertNull(out.getSignatureDate());
        assertTrue(out.getAffirmedMainAgreement(), "content and ticks untouched");
        assertEquals("[{\"key\":\"signature\",\"note\":\"Please sign clearly\"}]", out.getRevisionSections());
        assertEquals("Please re-sign the agreement with a clear, valid signature. Please sign clearly",
                out.getCurrentRevisionRemarks());
        assertEquals("REVISION_REQUESTED", out.getStatus());
    }

    @Test
    void aDocumentRevisionClearsThePointersButKeepsChequeNumbers() {
        WebAgreement a = signed();
        WebAgreement out = service.requestDocumentRevision(a.getApplicationId(),
                List.of("doc:workauth", "doc:cheque", "doc:nope", "doc:workauth"), "Blurry", ERM, request);
        assertNull(out.getWorkAuthDocS3Key());
        assertNull(out.getWorkAuthDocContentType());
        assertNull(out.getChequeS3Key());
        WebAgreementRules.ChequeEntry cheque = WebAgreementRules.parseCheques(out).get(0);
        assertEquals("1001", cheque.number());
        assertEquals("", cheque.s3Key());
        assertEquals("dl-file", out.getDlDocS3Key(), "not requested: kept");
        assertEquals(List.of("doc:workauth", "doc:cheque"),
                List.copyOf(WebAgreementRules.parseSectionScopeKeys(out.getRevisionSections())));
        assertEquals("Please re-upload the following document(s): work-authorization document, "
                + "security cheque(s). Blurry", out.getCurrentRevisionRemarks());

        WebAgreement b = signed();
        assertThrows(IllegalArgumentException.class, () -> service.requestDocumentRevision(
                b.getApplicationId(), List.of("doc:nope"), null, ERM, request));
    }

    // ── Take back ────────────────────────────────────────────────────

    @Test
    void aRequestCanBeTakenBackUntilTheParticipantActs() throws Exception {
        WebAgreement a = signed();
        a.setConsultantCopyReleased(true);
        service.requestRevision(a.getApplicationId(), sections("[{\"key\":\"exhibit-b\"}]"),
                null, null, null, "$550", null, null, null, ERM, request);
        // Opening the page doesn't count; only writes do.
        events.add(WebAgreementEvent.builder().agreementId(a.getId())
                .eventType(WebAgreementEvent.EventType.ACCESSED.name()).actorType("PARTICIPANT")
                .createdAt(a.getRevisionRequestedAt().plusSeconds(5)).build());

        Map<String, Object> view = service.detail(a.getApplicationId(), ERM);
        WebAgreement shown = (WebAgreement) view.get("application");
        assertTrue(shown.getRevisionRevocable());
        assertFalse(shown.getRevisionConsultantActed());
        assertEquals(List.of("Rate amount 1"), shown.getRevisionRevokeReverts());

        WebAgreement out = service.revokeRevision(a.getApplicationId(), ERM, request);
        assertEquals("VERIFIED", out.getStatus());
        assertTrue(out.getConsultantCopyReleased(), "back on the desk it came from");
        assertEquals(0, out.getRevisionCount());
        assertEquals("$500", out.getRateAmount1());
        assertTrue(out.getAffirmedExhibitB());
        assertTrue(out.getAffirmedMainAgreement());
        assertNull(out.getRevisionSections());
        assertNull(out.getCurrentRevisionRemarks());
        assertNull(out.getRevisionUndoSnapshot());
        assertNull(out.getRevisionPrevStatus());
        WebAgreementEvent revoked = eventsOf(a, WebAgreementEvent.EventType.REVISION_REVOKED).get(0);
        assertTrue(revoked.getMetadata().contains("Rate amount 1"));
        verify(emails).sendWebAgreementRevisionWithdrawn(a);
    }

    @Test
    void signaturesAndDocumentsComeBackOnATakeBack() {
        WebAgreement a = signed();
        service.requestSignatureRevision(a.getApplicationId(), null, ERM, request);
        service.revokeRevision(a.getApplicationId(), ERM, request);
        assertEquals("sig-primary", a.getSignatureS3Key());
        assertEquals("sig-final", a.getFinalSignatureS3Key());
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 31), a.getSignatureDate());

        service.requestDocumentRevision(a.getApplicationId(), List.of("doc:workauth", "doc:cheque"), null,
                ERM, request);
        service.revokeRevision(a.getApplicationId(), ERM, request);
        assertEquals("participant-documents/10/web-agreement-workauth.pdf", a.getWorkAuthDocS3Key());
        assertEquals("application/pdf", a.getWorkAuthDocContentType());
        assertEquals("cheque-file", a.getChequeS3Key());
        assertEquals("cheque-file", WebAgreementRules.parseCheques(a).get(0).s3Key());
        assertEquals("VERIFIED", a.getStatus());
    }

    @Test
    void takeBackIsRefusedOnceTheParticipantStartedWorking() throws Exception {
        WebAgreement a = signed();
        service.requestRevision(a.getApplicationId(), sections("[{\"key\":\"appendix2\"}]"),
                null, null, null, null, null, null, null, ERM, request);
        events.add(WebAgreementEvent.builder().agreementId(a.getId())
                .eventType(WebAgreementEvent.EventType.CONSULTANT_FILLED.name()).actorType("PARTICIPANT")
                .actorUserId(PAT).createdAt(a.getRevisionRequestedAt().plusSeconds(5)).build());

        WebAgreement shown = (WebAgreement) service.detail(a.getApplicationId(), ERM).get("application");
        assertFalse(shown.getRevisionRevocable());
        assertTrue(shown.getRevisionConsultantActed());
        assertEquals(WebAgreementStaffService.CONSULTANT_ACTED_REASON, shown.getRevisionRevokeBlockedReason());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.revokeRevision(a.getApplicationId(), ERM, request));
        assertEquals(WebAgreementStaffService.CONSULTANT_ACTED_REASON, e.getMessage());
        assertEquals("REVISION_REQUESTED", a.getStatus());
    }

    @Test
    void thereIsNothingToTakeBackWithoutAnOpenRequestOrItsRecord() {
        WebAgreement a = signed();
        assertThrows(IllegalStateException.class, () -> service.revokeRevision(a.getApplicationId(), ERM, request));
        a.setStatus("REVISION_REQUESTED");
        a.setRevisionCount(1);
        assertThrows(IllegalStateException.class, () -> service.revokeRevision(a.getApplicationId(), ERM, request));
        WebAgreement shown = service.decorateRevokeState(a);
        assertFalse(shown.getRevisionRevocable());
        assertNotNull(shown.getRevisionRevokeBlockedReason());
    }

    // ── Verify, cancel, resend, contact, preview ─────────────────────

    @Test
    void verifyIsAOneTimeStepOnASignedAgreement() {
        WebAgreement a = signed();
        a.setStatus("SUBMITTED");
        assertThrows(IllegalStateException.class, () -> service.verify(a.getApplicationId(), ERM, request));

        a.setStatus("VERIFIED");
        WebAgreement out = service.verify(a.getApplicationId(), ERM, request);
        assertEquals("VERIFIED", out.getStatus(), "the status stays; the release flag flips");
        assertTrue(out.getConsultantCopyReleased());
        assertNotNull(out.getConsultantCopyReleasedAt());
        assertEquals("20", out.getConsultantCopyReleasedBy());
        assertEquals(ERM, eventsOf(a, WebAgreementEvent.EventType.VERIFIED).get(0).getActorUserId());
        verify(emails).sendWebAgreementVerifiedEmail(a);

        assertThrows(IllegalStateException.class, () -> service.verify(a.getApplicationId(), ERM, request));
    }

    @Test
    void cancelEndsAnyOpenAgreementButNotACompletedOne() throws Exception {
        WebAgreement a = signed();
        service.requestRevision(a.getApplicationId(), sections("[{\"key\":\"cover\"}]"),
                null, null, null, null, null, null, null, ERM, request);
        WebAgreement out = service.cancel(a.getApplicationId(), ERM, request);
        assertEquals("CANCELLED", out.getStatus());
        assertNull(out.getRevisionUndoSnapshot(), "the open request ends for good");
        assertEquals(1, eventsOf(a, WebAgreementEvent.EventType.CANCELLED).size());

        WebAgreement done = signed();
        done.setStatus("COMPLETED");
        assertThrows(IllegalStateException.class, () -> service.cancel(done.getApplicationId(), ERM, request));
    }

    @Test
    void resendOnlyWhileTheParticipantIsFillingAndAFailureIsReported() {
        WebAgreement a = signed();
        assertThrows(IllegalStateException.class, () -> service.resend(a.getApplicationId(), ERM, request));

        a.setStatus("SUBMITTED");
        service.resend(a.getApplicationId(), ERM, request);
        verify(emails).sendWebAgreementReadyToFill(a);
        assertEquals(1, eventsOf(a, WebAgreementEvent.EventType.INVITE_RESENT).size());
        assertEquals(1, eventsOf(a, WebAgreementEvent.EventType.EMAIL_SENT).size());

        when(emails.sendWebAgreementReadyToFill(any())).thenReturn(false);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.resend(a.getApplicationId(), ERM, request));
        assertTrue(e.getMessage().startsWith("Couldn't resend invite"));
    }

    @Test
    void withEmailsOffNothingIsSentAndThereIsNothingToResend() throws Exception {
        emailsOn = false;
        WebAgreement a = service.create(body().b, ERM, request);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.resend(a.getApplicationId(), ERM, request));
        assertTrue(e.getMessage().contains("switched off"));
        a.setStatus("VERIFIED");
        service.requestRevision(a.getApplicationId(), sections("[{\"key\":\"appendix2\"}]"),
                null, null, null, null, null, null, null, ERM, request);
        service.revokeRevision(a.getApplicationId(), ERM, request);
        service.verify(a.getApplicationId(), ERM, request);
        verifyNoInteractions(emails);
        assertTrue(eventsOf(a, WebAgreementEvent.EventType.EMAIL_SENT).isEmpty());
        assertEquals(false, service.detail(a.getApplicationId(), ERM).get("emailsEnabled"));
    }

    @Test
    void theContactFixNeedsAValidEmail() {
        WebAgreement a = signed();
        assertThrows(IllegalArgumentException.class,
                () -> service.updateContact(a.getApplicationId(), "not-an-email", null, ERM, request));
        WebAgreement out = service.updateContact(a.getApplicationId(), " Pat.New@X.com ", "Patricia Ann Lee",
                ERM, request);
        assertEquals("pat.new@x.com", out.getConsultantEmail());
        assertEquals("Patricia Ann Lee", out.getConsultantName());
        assertEquals("Patricia", out.getFirstName());
        assertEquals("Ann", out.getMiddleName());
        assertEquals("Lee", out.getLastName());
        assertEquals(1, eventsOf(a, WebAgreementEvent.EventType.CONSULTANT_CONTACT_UPDATED).size());

        a.setStatus("CANCELLED");
        assertThrows(IllegalStateException.class,
                () -> service.updateContact(a.getApplicationId(), "pat@x.com", null, ERM, request));
    }

    @Test
    void thePreviewIsOnlyForASignedAgreement() {
        WebAgreement a = signed();
        a.setStatus("REVISION_REQUESTED");
        assertThrows(IllegalStateException.class, () -> service.previewPdf(a.getApplicationId(), ERM));
        verifyNoInteractions(renderer);

        a.setStatus("VERIFIED");
        a.setSignedLegalName("Pat Lee");
        when(renderer.renderPdf(a, true, null)).thenReturn("%PDF-1.7".getBytes());
        when(renderer.toTransient(a)).thenReturn(ConsultantApplication.builder()
                .applicationId("web-" + a.getApplicationId()).signedLegalName("Pat Lee").build());
        WebAgreementFileService.Download pdf = service.previewPdf(a.getApplicationId(), ERM);
        assertEquals("application/pdf", pdf.contentType());
        assertTrue(pdf.filename().startsWith("preview-SageITCO-Agreement_"));
        verify(renderer).renderPdf(a, true, null);
    }
}
