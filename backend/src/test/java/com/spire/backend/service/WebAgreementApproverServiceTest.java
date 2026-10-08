package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.controller.WebAgreementApproverController;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementApproval;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.entity.WebAgreementVersion;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.StorageUnavailableException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementApprovalRepository;
import com.spire.backend.repository.WebAgreementEventRepository;
import com.spire.backend.repository.WebAgreementRepository;
import com.spire.backend.repository.WebAgreementVersionRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The approvers' side of a website agreement (the console's approver
 * surface): the gate is the caller's own role, the System Admin names one;
 * the queue holds only my routed or un-routed gates of the current round
 * while awaiting approvals; a decision on someone else's gate is "not
 * found", on a decided gate a conflict, and a revision needs a note; the
 * last approval makes the agreement READY_TO_SIGN and a decline sends it
 * back to the ERM leaving the other gate PENDING; previews are the routed
 * version (or a live render) as page images; downloads need my approval;
 * and no agreement JSON carries the SSN, ID or bank numbers, the decision
 * response being stripped only after the decision has committed.
 */
class WebAgreementApproverServiceTest {

    private static final long PAT = 10L, ERM = 20L, OPS = 30L, SYS = 31L, COACH = 40L,
            MGR_ONE = 50L, MGR_TWO = 51L, MGR_OFF = 52L, ACC_ONE = 60L, GONE_ERM = 99L;

    private static final byte[] LIVE_PREVIEW = "%PDF live ERM preview".getBytes();
    private static final byte[] LIVE_FINAL = "%PDF live final".getBytes();
    private static final String FILENAME = "SageITCO-Agreement_Pat-Lee_Data-Engineering.pdf";

    private final List<User> users = new ArrayList<>();
    private final List<WebAgreement> agreements = new ArrayList<>();
    /** web_agreement_approvals. */
    private final List<WebAgreementApproval> gates = new ArrayList<>();
    /** web_agreement_versions. */
    private final List<WebAgreementVersion> versions = new ArrayList<>();
    private final List<WebAgreementEvent> events = new ArrayList<>();
    /** The website storage: stored value → bytes. */
    private final Map<String, byte[]> stored = new HashMap<>();
    /** Every agreement save as written: [applicationId, status, bgFullSsn, achAccountNumber]. */
    private final List<String[]> saves = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private WebAgreementRepository repo;
    private WebAgreementApprovalRepository gateRepo;
    private WebAgreementRenderer renderer;
    private EntityManager entityManager;
    private WebAgreementApproverService service;
    private MockHttpServletRequest request;
    private LocalDateTime clock = LocalDateTime.of(2026, 10, 7, 9, 0);

    @BeforeEach
    void setUp() {
        user(PAT, "PARTICIPANT", "Pat Lee", true);
        user(ERM, "ERM", "Erin Manager", true);
        user(OPS, "OPERATIONS_ADMIN", "Ops Admin", true);
        user(SYS, "SYSTEM_ADMIN", "Sys Admin", true);
        user(COACH, "COACH", "Coach Carter", true);
        user(MGR_ONE, "MANAGER", "Mona One", true);
        user(MGR_TWO, "MANAGER", "Al Two", true);
        user(MGR_OFF, "MANAGER", "Off Manager", false);
        user(ACC_ONE, "ACCOUNTS", "Ash Counts", true);

        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> users.stream()
                .filter(u -> u.getId().equals(inv.getArgument(0))).findFirst());
        when(userRepo.findAllById(any())).thenAnswer(inv -> {
            List<Object> wanted = new ArrayList<>();
            inv.<Iterable<?>>getArgument(0).forEach(wanted::add);
            return users.stream().filter(u -> wanted.contains(u.getId())).toList();
        });

        repo = mock(WebAgreementRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            WebAgreement a = inv.getArgument(0);
            saves.add(new String[] {a.getApplicationId(), a.getStatus(), a.getBgFullSsn(), a.getAchAccountNumber()});
            return a;
        });
        when(repo.findByApplicationId(anyString())).thenAnswer(inv -> agreements.stream()
                .filter(a -> a.getApplicationId().equals(inv.getArgument(0))).findFirst());
        when(repo.findById(anyLong())).thenAnswer(inv -> agreements.stream()
                .filter(a -> a.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.findAllById(any())).thenAnswer(inv -> {
            List<Object> wanted = new ArrayList<>();
            inv.<Iterable<?>>getArgument(0).forEach(wanted::add);
            return agreements.stream().filter(a -> wanted.contains(a.getId())).toList();
        });

        gateRepo = mock(WebAgreementApprovalRepository.class);
        when(gateRepo.save(any(WebAgreementApproval.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gateRepo.findByStatusAndRole(anyString(), anyString())).thenAnswer(inv -> gates.stream()
                .filter(g -> g.getStatus().equals(inv.getArgument(0)) && g.getRole().equals(inv.getArgument(1)))
                .toList());
        when(gateRepo.maxRound(anyLong())).thenAnswer(inv -> gates.stream()
                .filter(g -> g.getAgreementId().equals(inv.getArgument(0)))
                .map(WebAgreementApproval::getRound).max(Integer::compare).orElse(null));
        when(gateRepo.maxRounds(any())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            Map<Long, Integer> max = new HashMap<>();
            for (WebAgreementApproval g : gates) {
                if (ids.contains(g.getAgreementId())) max.merge(g.getAgreementId(), g.getRound(), Math::max);
            }
            return max.entrySet().stream().map(e -> new Object[] {e.getKey(), e.getValue()}).toList();
        });
        when(gateRepo.findFirstByAgreementIdAndRoleAndRound(anyLong(), anyString(), anyInt())).thenAnswer(inv ->
                gates.stream().filter(g -> g.getAgreementId().equals(inv.getArgument(0))
                        && g.getRole().equals(inv.getArgument(1))
                        && g.getRound().equals(inv.getArgument(2))).findFirst());
        when(gateRepo.findByAgreementIdAndRound(anyLong(), anyInt())).thenAnswer(inv -> gates.stream()
                .filter(g -> g.getAgreementId().equals(inv.getArgument(0)) && g.getRound().equals(inv.getArgument(1)))
                .toList());
        when(gateRepo.findByAgreementIdOrderByCreatedAtAsc(anyLong())).thenAnswer(inv -> gatesOf(inv.getArgument(0)));
        when(gateRepo.findByAgreementIdIn(any())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return gates.stream().filter(g -> ids.contains(g.getAgreementId())).toList();
        });
        when(gateRepo.findByRoleAndApproverUserId(anyString(), anyLong())).thenAnswer(inv -> gates.stream()
                .filter(g -> g.getRole().equals(inv.getArgument(0))
                        && Objects.equals(g.getApproverUserId(), inv.getArgument(1))).toList());
        when(gateRepo.existsByAgreementIdAndRoleAndApproverUserId(anyLong(), anyString(), anyLong())).thenAnswer(inv ->
                gates.stream().anyMatch(g -> g.getAgreementId().equals(inv.getArgument(0))
                        && g.getRole().equals(inv.getArgument(1))
                        && Objects.equals(g.getApproverUserId(), inv.getArgument(2))));
        when(gateRepo.existsByAgreementIdAndRoleAndStatusAndDecidedBy(anyLong(), anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> gates.stream().anyMatch(g -> g.getAgreementId().equals(inv.getArgument(0))
                        && g.getRole().equals(inv.getArgument(1))
                        && g.getStatus().equals(inv.getArgument(2))
                        && Objects.equals(g.getDecidedBy(), inv.getArgument(3))));
        when(gateRepo.findByStatusAndRoleAndDecidedByOrderByDecidedAtDesc(anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> gates.stream()
                        .filter(g -> g.getStatus().equals(inv.getArgument(0))
                                && g.getRole().equals(inv.getArgument(1))
                                && Objects.equals(g.getDecidedBy(), inv.getArgument(2)))
                        .sorted(Comparator.comparing(WebAgreementApproval::getDecidedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .toList());

        WebAgreementVersionRepository versionRepo = mock(WebAgreementVersionRepository.class);
        when(versionRepo.findTopByAgreementIdOrderByVersionNumberDesc(anyLong())).thenAnswer(inv -> versions.stream()
                .filter(v -> v.getAgreementId().equals(inv.getArgument(0)))
                .max(Comparator.comparing(WebAgreementVersion::getVersionNumber)));
        when(versionRepo.findByAgreementIdAndVersionNumber(anyLong(), anyInt())).thenAnswer(inv -> versions.stream()
                .filter(v -> v.getAgreementId().equals(inv.getArgument(0))
                        && v.getVersionNumber().equals(inv.getArgument(1))).findFirst());

        WebAgreementEventRepository eventRepo = mock(WebAgreementEventRepository.class);
        when(eventRepo.save(any())).thenAnswer(inv -> {
            WebAgreementEvent e = inv.getArgument(0);
            if (e.getCreatedAt() == null) e.setCreatedAt(tick());
            events.add(e);
            return e;
        });
        when(eventRepo.findByAgreementIdInAndEventType(any(), anyString())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return events.stream().filter(e -> ids.contains(e.getAgreementId())
                    && e.getEventType().equals(inv.getArgument(1))).toList();
        });

        // The real file service over a fake website storage.
        DocumentStorageService storage = mock(DocumentStorageService.class);
        when(storage.readBytes(anyString())).thenAnswer(inv -> stored.get(inv.<String>getArgument(0)));
        WebAgreementFileService files = new WebAgreementFileService(storage, mock(HeicTranscoder.class));

        // The renderer: live renders are fixed bytes; rasterising hands the
        // PDF back as page 1, so a test can see which PDF was shown.
        renderer = mock(WebAgreementRenderer.class);
        when(renderer.renderErmPreviewPdf(any())).thenReturn(LIVE_PREVIEW);
        when(renderer.renderFinalPdf(any())).thenReturn(LIVE_FINAL);
        when(renderer.renderCleanPageImages(any(), any())).thenAnswer(inv ->
                List.of(inv.<byte[]>getArgument(0), "page 2".getBytes()));

        WebAgreementAccess access = new WebAgreementAccess(repo, userRepo);
        service = new WebAgreementApproverService(repo, gateRepo, versionRepo, files, renderer,
                new WebAgreementEventService(eventRepo), access, new WebAgreementApprovalSummary(gateRepo, eventRepo));
        entityManager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9");
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    private void user(long id, String role, String name, boolean active) {
        users.add(User.builder().id(id).fullName(name).email(name.replace(' ', '.').toLowerCase() + "@sageitco.com")
                .role(Role.builder().name(role).build()).isActive(active).build());
    }

    private LocalDateTime tick() {
        clock = clock.plusMinutes(1);
        return clock;
    }

    /** A signed agreement owned by the ERM, with the PII the lists must never carry. */
    private WebAgreement agreement(String status, int phase) {
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
        a.setUpdatedAt(tick());
        a.setBgFullSsn("123456789");
        a.setAchAccountNumber("000123");
        a.setAchRoutingNumber("021000021");
        a.setBgDriverLicense("D1234");
        a.setBgStateId("S999");
        a.setBgDateOfBirth(LocalDate.of(1990, 1, 1));
        agreements.add(a);
        return a;
    }

    /** A gate row; a decided one is decided by its approver. */
    private WebAgreementApproval gate(WebAgreement a, String role, int round, Long approver, String status) {
        WebAgreementApproval g = WebAgreementApproval.builder()
                .id((long) gates.size() + 1).agreementId(a.getId()).role(role).status(status)
                .phase(a.getPhase()).round(round).approverUserId(approver)
                .approverName(approver == null ? null : name(approver)).createdAt(tick()).build();
        if (!"PENDING".equals(status)) {
            g.setDecidedBy(approver);
            g.setDecidedByName(name(approver));
            g.setDecidedAt(tick());
        }
        gates.add(g);
        return g;
    }

    private void version(WebAgreement a, int n) {
        String key = "s3:participant-documents/10/web-agreement-consultant-version-a" + a.getId() + "-v" + n + ".pdf";
        stored.put(key, ("%PDF version V" + n + " of " + a.getApplicationId()).getBytes());
        versions.add(WebAgreementVersion.builder().id((long) versions.size() + 1).agreementId(a.getId())
                .versionNumber(n).s3Key(key).phase(a.getPhase()).build());
    }

    private String name(Long id) {
        return users.stream().filter(u -> u.getId().equals(id)).map(User::getFullName).findFirst().orElse(null);
    }

    private List<WebAgreementApproval> gatesOf(Long agreementId) {
        return gates.stream().filter(g -> g.getAgreementId().equals(agreementId))
                .sorted(Comparator.comparing(WebAgreementApproval::getCreatedAt)).toList();
    }

    private List<WebAgreementEvent> eventsOf(WebAgreement a, WebAgreementEvent.EventType type) {
        return events.stream()
                .filter(e -> e.getAgreementId().equals(a.getId()) && e.getEventType().equals(type.name()))
                .toList();
    }

    private List<Object> queueOf(long caller, String role) {
        return service.queue(role, caller).stream().map(i -> i.get("application")).toList();
    }

    private static String page(Map<String, Object> payload, int i) {
        return new String(Base64.getDecoder().decode((String) ((List<?>) payload.get("pages")).get(i)));
    }

    private static Authentication auth(long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null);
    }

    private static void assertNoPii(WebAgreement a) {
        assertNull(a.getBgFullSsn());
        assertNull(a.getBgDriverLicense());
        assertNull(a.getBgStateId());
        assertNull(a.getAchAccountNumber());
        assertNull(a.getAchRoutingNumber());
        assertNull(a.getBgDateOfBirth());
    }

    // ── Which gate ───────────────────────────────────────────────────

    @Test
    void theGateIsTheApproversOwnRoleAndTheSystemAdminMustNameOne() {
        assertEquals("MANAGER", service.resolveGate(MGR_ONE, null).role());
        assertEquals("MANAGER", service.resolveGate(MGR_ONE, "ACCOUNTS").role(), "an approver's own gate wins");
        assertEquals("ACCOUNTS", service.resolveGate(ACC_ONE, "MANAGER").role());
        assertEquals("ACCOUNTS", service.resolveGate(SYS, " accounts ").role());

        IllegalArgumentException none = assertThrows(IllegalArgumentException.class, () -> service.queue(null, SYS));
        assertEquals("role (MANAGER|ACCOUNTS) is required for the super-admin.", none.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.queue("  ", SYS));
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> service.queue("ERM", SYS));
        assertEquals("role must be MANAGER or ACCOUNTS.", bad.getMessage());

        for (long other : new long[] {ERM, OPS, COACH, PAT, MGR_OFF, 12345L}) {
            AccessDeniedException e = assertThrows(AccessDeniedException.class,
                    () -> service.queue("MANAGER", other), "caller " + other);
            assertEquals("Approver role required.", e.getMessage());
        }
    }

    // ── The Pending queue ────────────────────────────────────────────

    @Test
    void theQueueHoldsOnlyMyRoutedOrUnroutedGatesOfTheCurrentRoundWhileAwaitingApprovals() {
        WebAgreement routedToOne = agreement("AWAITING_APPROVALS", 1);
        gate(routedToOne, "MANAGER", 1, MGR_ONE, "PENDING");
        WebAgreement unrouted = agreement("AWAITING_APPROVALS", 1);
        gate(unrouted, "MANAGER", 1, null, "PENDING");
        WebAgreement reSentToTwo = agreement("AWAITING_APPROVALS", 1);
        gate(reSentToTwo, "MANAGER", 1, MGR_ONE, "REVISION_REQUESTED");
        gate(reSentToTwo, "MANAGER", 2, MGR_TWO, "PENDING");
        WebAgreement staleRound = agreement("AWAITING_APPROVALS", 1);
        gate(staleRound, "MANAGER", 1, MGR_ONE, "PENDING");   // left open by an earlier revision
        gate(staleRound, "MANAGER", 2, MGR_TWO, "PENDING");
        WebAgreement backWithParticipant = agreement("REVISION_REQUESTED", 1);
        gate(backWithParticipant, "MANAGER", 1, MGR_ONE, "PENDING");
        WebAgreement archived = agreement("AWAITING_APPROVALS", 1);
        archived.setDeleted(true);
        gate(archived, "MANAGER", 1, MGR_ONE, "PENDING");
        WebAgreement approvedAlready = agreement("AWAITING_APPROVALS", 2);
        gate(approvedAlready, "MANAGER", 1, MGR_ONE, "APPROVED");
        gate(approvedAlready, "ACCOUNTS", 1, ACC_ONE, "PENDING");
        WebAgreement phaseTwo = agreement("AWAITING_APPROVALS", 2);
        gate(phaseTwo, "MANAGER", 3, MGR_ONE, "PENDING");
        gate(phaseTwo, "ACCOUNTS", 3, ACC_ONE, "PENDING");

        // Newest change first.
        assertEquals(List.of(phaseTwo, unrouted, routedToOne), queueOf(MGR_ONE, null));
        assertEquals(List.of(staleRound, reSentToTwo, unrouted), queueOf(MGR_TWO, null));
        assertEquals(List.of(phaseTwo, approvedAlready), queueOf(ACC_ONE, null));
        assertEquals(List.of(unrouted), queueOf(SYS, "MANAGER"), "the System Admin only sees un-routed gates");

        Map<String, Object> item = service.queue(null, MGR_ONE).get(0);
        assertEquals("MANAGER", item.get("myRole"));
        assertEquals(gatesOf(phaseTwo.getId()), item.get("approvals"), "every gate, oldest first");
        assertEquals("ACCOUNTS", service.queue(null, ACC_ONE).get(0).get("myRole"));

        for (Object a : queueOf(MGR_ONE, null)) assertNoPii((WebAgreement) a);
        verify(entityManager, atLeastOnce()).detach(routedToOne);
    }

    @Test
    void theQueueReadsItsAgreementsRoundsAndGatesInOneQueryEach() {
        // Many pending rows, stale ones included: still four queries, none per row.
        List<WebAgreement> live = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            WebAgreement a = agreement("AWAITING_APPROVALS", 2);
            gate(a, "MANAGER", 1, null, "PENDING");                // left open by an earlier round
            gate(a, "ACCOUNTS", 2, ACC_ONE, "PENDING");
            gate(a, "MANAGER", 2, i % 2 == 0 ? MGR_ONE : null, "PENDING");
            live.add(0, a);                                         // newest change first
        }
        WebAgreement moved = agreement("READY_TO_SIGN", 2);
        gate(moved, "MANAGER", 2, MGR_ONE, "PENDING");

        clearInvocations(repo, gateRepo);
        List<Map<String, Object>> items = service.queue(null, MGR_ONE);

        assertEquals(live, items.stream().map(i -> i.get("application")).toList());
        for (Map<String, Object> item : items) {
            WebAgreement a = (WebAgreement) item.get("application");
            assertEquals(gatesOf(a.getId()), item.get("approvals"), "every gate, oldest first");
        }
        verify(gateRepo, times(1)).findByStatusAndRole("PENDING", "MANAGER");
        verify(repo, times(1)).findAllById(any());
        verify(gateRepo, times(1)).maxRounds(any());
        verify(gateRepo, times(1)).findByAgreementIdIn(any());
        verify(repo, never()).findById(anyLong());
        verify(gateRepo, never()).maxRound(anyLong());
        verify(gateRepo, never()).findByAgreementIdOrderByCreatedAtAsc(anyLong());

        // Nothing of mine pending: the one list query only.
        clearInvocations(repo, gateRepo);
        assertEquals(List.of(), service.queue("ACCOUNTS", SYS));
        verify(gateRepo, times(1)).findByStatusAndRole("PENDING", "ACCOUNTS");
        verify(repo, never()).findAllById(any());
        verify(gateRepo, never()).maxRounds(any());
        verify(gateRepo, never()).findByAgreementIdIn(any());
    }

    // ── Decisions ────────────────────────────────────────────────────

    @Test
    void someoneElsesGateIsNotFoundADecidedGateIsAConflictAndARevisionNeedsANote() {
        WebAgreement a = agreement("AWAITING_APPROVALS", 1);
        WebAgreementApproval g = gate(a, "MANAGER", 1, MGR_ONE, "PENDING");

        ResourceNotFoundException other = assertThrows(ResourceNotFoundException.class,
                () -> service.decide(a.getApplicationId(), true, null, null, MGR_TWO, request));
        assertEquals("Agreement not found.", other.getMessage());
        assertThrows(ResourceNotFoundException.class,
                () -> service.decide(a.getApplicationId(), true, null, null, ACC_ONE, request),
                "Phase 1 has no Accounts gate");
        assertThrows(ResourceNotFoundException.class,
                () -> service.decide("no-such-id", true, null, null, MGR_ONE, request));

        IllegalArgumentException noNote = assertThrows(IllegalArgumentException.class,
                () -> service.decide(a.getApplicationId(), false, "   ", null, MGR_ONE, request));
        assertEquals("A note is required when requesting a revision.", noNote.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> service.decide(a.getApplicationId(), false, null, null, MGR_ONE, request));

        g.setStatus("APPROVED");
        IllegalStateException decided = assertThrows(IllegalStateException.class,
                () -> service.decide(a.getApplicationId(), true, null, null, MGR_ONE, request));
        assertEquals("This gate has already been decided.", decided.getMessage());

        a.setStatus("READY_TO_SIGN");
        IllegalStateException notAwaiting = assertThrows(IllegalStateException.class,
                () -> service.decide(a.getApplicationId(), true, null, null, MGR_ONE, request));
        assertEquals("This agreement is not awaiting approvals (status=READY_TO_SIGN).", notAwaiting.getMessage());

        WebAgreement neverSent = agreement("AWAITING_APPROVALS", 1);
        IllegalStateException noRound = assertThrows(IllegalStateException.class,
                () -> service.decide(neverSent.getApplicationId(), true, null, null, MGR_ONE, request));
        assertEquals("No approval round is open.", noRound.getMessage());

        WebAgreement archived = agreement("AWAITING_APPROVALS", 1);
        archived.setDeleted(true);
        gate(archived, "MANAGER", 1, MGR_ONE, "PENDING");
        assertThrows(ResourceNotFoundException.class,
                () -> service.decide(archived.getApplicationId(), true, null, null, MGR_ONE, request));

        assertTrue(events.isEmpty());
        assertTrue(saves.isEmpty());
    }

    @Test
    void approvingTheLastGateOfTheRoundMakesTheAgreementReadyToSign() throws Exception {
        WebAgreement a = agreement("AWAITING_APPROVALS", 2);
        gate(a, "MANAGER", 1, MGR_TWO, "APPROVED");          // an earlier round never counts
        WebAgreementApproval mgr = gate(a, "MANAGER", 2, MGR_ONE, "PENDING");
        WebAgreementApproval acc = gate(a, "ACCOUNTS", 2, ACC_ONE, "PENDING");

        WebAgreement out = service.decide(a.getApplicationId(), true, null, null, MGR_ONE, request);
        assertEquals("AWAITING_APPROVALS", out.getStatus(), "Accounts is still pending");
        assertEquals("APPROVED", mgr.getStatus());
        assertEquals(MGR_ONE, mgr.getDecidedBy());
        assertEquals("Mona One", mgr.getDecidedByName());
        assertNotNull(mgr.getDecidedAt());
        assertEquals("203.0.113.9", mgr.getDecidedIp());
        assertNull(mgr.getNote());
        assertTrue(saves.isEmpty(), "the agreement is untouched until the round is complete");

        WebAgreementEvent ev = eventsOf(a, WebAgreementEvent.EventType.APPROVAL_APPROVED).get(0);
        assertEquals("ERM", ev.getActorType(), "the console's actor type");
        assertEquals(MGR_ONE, ev.getActorUserId(), "the approver's real users.id");
        JsonNode m = mapper.readTree(ev.getMetadata());
        assertEquals("MANAGER", m.path("role").asText());
        assertEquals(2, m.path("round").asInt());
        assertEquals("Mona One", m.path("approver").asText());
        assertEquals("203.0.113.9", m.path("ip").asText());

        out = service.decide(a.getApplicationId(), true, "Bank details check out.", null, ACC_ONE, request);
        assertEquals("READY_TO_SIGN", out.getStatus());
        assertEquals("APPROVED", acc.getStatus());
        assertEquals("Bank details check out.", acc.getNote(), "an optional note is kept on approve");
        assertEquals(1, saves.size());
        assertEquals("READY_TO_SIGN", saves.get(0)[1]);

        // Phase 1: the Manager alone completes the round.
        WebAgreement p1 = agreement("AWAITING_APPROVALS", 1);
        gate(p1, "MANAGER", 1, MGR_ONE, "PENDING");
        assertEquals("READY_TO_SIGN", service.decide(p1.getApplicationId(), true, null, null, MGR_ONE, request)
                .getStatus());
        assertTrue(eventsOf(a, WebAgreementEvent.EventType.EMAIL_SENT).isEmpty(), "no email");
    }

    @Test
    void aDeclineGoesBackToTheErmAndLeavesTheOtherGatePending() throws Exception {
        WebAgreement a = agreement("AWAITING_APPROVALS", 2);
        WebAgreementApproval mgr = gate(a, "MANAGER", 1, MGR_ONE, "PENDING");
        WebAgreementApproval acc = gate(a, "ACCOUNTS", 1, ACC_ONE, "PENDING");

        WebAgreement out = service.decide(a.getApplicationId(), false, "Bank letter missing", null, ACC_ONE, request);
        assertEquals("APPROVAL_REVISION_REQUESTED", out.getStatus());
        assertEquals("[ACCOUNTS] Bank letter missing", out.getCurrentRevisionRemarks());
        assertEquals("REVISION_REQUESTED", acc.getStatus());
        assertEquals("Bank letter missing", acc.getNote());
        assertEquals(ACC_ONE, acc.getDecidedBy());
        assertEquals("PENDING", mgr.getStatus(), "only the decided gate is written");
        assertNull(mgr.getDecidedBy());
        assertEquals("APPROVAL_REVISION_REQUESTED", saves.get(0)[1]);

        WebAgreementEvent ev = eventsOf(a, WebAgreementEvent.EventType.APPROVAL_REVISION_REQUESTED).get(0);
        assertEquals("ERM", ev.getActorType());
        assertEquals(ACC_ONE, ev.getActorUserId());
        JsonNode m = mapper.readTree(ev.getMetadata());
        assertEquals("ACCOUNTS", m.path("role").asText());
        assertEquals(1, m.path("round").asInt());
        assertEquals("Ash Counts", m.path("approver").asText());
        assertEquals("Bank letter missing", m.path("note").asText());

        // The Manager's gate stays PENDING but leaves the queue, and can't be decided.
        assertTrue(queueOf(MGR_ONE, null).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> service.decide(a.getApplicationId(), true, null, null, MGR_ONE, request));

        // Phase 1 decline: "[MANAGER] note".
        WebAgreement p1 = agreement("AWAITING_APPROVALS", 1);
        gate(p1, "MANAGER", 1, MGR_ONE, "PENDING");
        assertEquals("[MANAGER] Fix the rate card",
                service.decide(p1.getApplicationId(), false, "Fix the rate card", null, MGR_ONE, request)
                        .getCurrentRevisionRemarks());
    }

    @Test
    void theSystemAdminNeedsARoleAndDecidesOnlyUnroutedGates() {
        WebAgreement routed = agreement("AWAITING_APPROVALS", 1);
        gate(routed, "MANAGER", 1, MGR_ONE, "PENDING");
        WebAgreement unrouted = agreement("AWAITING_APPROVALS", 1);
        WebAgreementApproval open = gate(unrouted, "MANAGER", 1, null, "PENDING");
        open.setUnroutedFromUserId(MGR_TWO);   // a purge un-routed it

        assertThrows(IllegalArgumentException.class,
                () -> service.decide(unrouted.getApplicationId(), true, null, null, SYS, request));
        assertThrows(ResourceNotFoundException.class,
                () -> service.decide(routed.getApplicationId(), true, null, "MANAGER", SYS, request));
        assertThrows(ResourceNotFoundException.class,
                () -> service.versionPreviewImages(routed.getApplicationId(), "MANAGER", SYS));
        assertThrows(ResourceNotFoundException.class,
                () -> service.decide(unrouted.getApplicationId(), true, null, "ACCOUNTS", SYS, request),
                "no Accounts gate in Phase 1");

        assertEquals("READY_TO_SIGN",
                service.decide(unrouted.getApplicationId(), true, null, "manager", SYS, request).getStatus());
        assertEquals(SYS, open.getDecidedBy());
        assertEquals("Sys Admin", open.getDecidedByName());

        // Any Manager could have decided the un-routed gate too.
        WebAgreement another = agreement("AWAITING_APPROVALS", 1);
        gate(another, "MANAGER", 1, null, "PENDING");
        assertEquals("READY_TO_SIGN",
                service.decide(another.getApplicationId(), true, null, null, MGR_TWO, request).getStatus());
    }

    // ── Previews ─────────────────────────────────────────────────────

    @Test
    void thePreviewIsTheRoutedVersionOrALiveRenderForTheCurrentGateOnly() {
        WebAgreement a = agreement("AWAITING_APPROVALS", 1);
        gate(a, "MANAGER", 1, MGR_ONE, "PENDING");
        version(a, 1);
        version(a, 2);
        a.setApprovalVersionNumber(1);

        Map<String, Object> p = service.versionPreviewImages(a.getApplicationId(), null, MGR_ONE);
        assertEquals("%PDF version V1 of " + a.getApplicationId(), page(p, 0), "the routed version, not the latest");
        assertEquals(2, p.get("pageCount"));
        assertEquals("mona.one@sageitco.com", p.get("viewerEmail"));
        assertEquals(1, p.get("versionNumber"));
        verify(renderer).renderCleanPageImages(any(), eq("mona.one@sageitco.com"));
        verify(renderer, never()).renderErmPreviewPdf(any());

        // No version routed (verified before versions existed), or one that doesn't exist: a live render.
        a.setApprovalVersionNumber(null);
        p = service.versionPreviewImages(a.getApplicationId(), null, MGR_ONE);
        assertEquals(new String(LIVE_PREVIEW), page(p, 0));
        assertNull(p.get("versionNumber"));
        a.setApprovalVersionNumber(7);
        assertEquals(new String(LIVE_PREVIEW), page(service.versionPreviewImages(a.getApplicationId(), null, MGR_ONE), 0));

        // A version whose file is gone fails like the console, never a silent live render.
        a.setApprovalVersionNumber(2);
        stored.remove(versions.get(1).getS3Key());
        WebAgreementApproverService.DocumentFailed gone = assertThrows(WebAgreementApproverService.DocumentFailed.class,
                () -> service.versionPreviewImages(a.getApplicationId(), null, MGR_ONE));
        assertEquals("Couldn't render the selected version.", gone.getMessage());
        verify(renderer, times(2)).renderErmPreviewPdf(any());

        // A render failure, or a busy render permit, is the console's 500.
        a.setApprovalVersionNumber(null);
        when(renderer.renderErmPreviewPdf(a)).thenThrow(
                new WebAgreementRenderer.RenderException(new IOException("soffice not found")));
        assertEquals("Couldn't render the selected version.", assertThrows(WebAgreementApproverService.DocumentFailed.class,
                () -> service.versionPreviewImages(a.getApplicationId(), null, MGR_ONE)).getMessage());
        WebAgreementApproverController controller = new WebAgreementApproverController(service);
        ResponseEntity<ApiResponse<Map<String, Object>>> res =
                controller.versionPreviewImages(a.getApplicationId(), null, auth(MGR_ONE));
        assertEquals(500, res.getStatusCode().value());
        assertEquals("Couldn't render the selected version.", res.getBody().getMessage());
        reset(renderer);
        when(renderer.renderErmPreviewPdf(any())).thenThrow(
                new StorageUnavailableException(WebAgreementRenderer.BUSY_MESSAGE, null));
        assertEquals(500, controller.versionPreviewImages(a.getApplicationId(), null, auth(MGR_ONE))
                .getStatusCode().value());

        // Only the current round's gate, routed to me or to no one.
        assertThrows(ResourceNotFoundException.class,
                () -> service.versionPreviewImages(a.getApplicationId(), null, MGR_TWO));
        assertThrows(ResourceNotFoundException.class,
                () -> service.versionPreviewImages(a.getApplicationId(), null, ACC_ONE));
        gate(a, "MANAGER", 2, MGR_TWO, "PENDING");
        assertThrows(ResourceNotFoundException.class,
                () -> service.versionPreviewImages(a.getApplicationId(), null, MGR_ONE), "an earlier round");
    }

    @Test
    void theLatestPreviewIsForAnyoneRoutedInAnyRound() {
        WebAgreement a = agreement("READY_TO_SIGN", 1);
        gate(a, "MANAGER", 1, MGR_ONE, "REVISION_REQUESTED");
        gate(a, "MANAGER", 2, MGR_TWO, "APPROVED");
        version(a, 1);
        version(a, 2);
        a.setApprovalVersionNumber(1);

        Map<String, Object> p = service.latestVersionPreviewImages(a.getApplicationId(), null, MGR_ONE);
        assertEquals("%PDF version V2 of " + a.getApplicationId(), page(p, 0), "the highest V");
        assertFalse(p.containsKey("versionNumber"));
        assertEquals("%PDF version V2 of " + a.getApplicationId(),
                page(service.latestVersionPreviewImages(a.getApplicationId(), null, MGR_TWO), 0));
        assertThrows(ResourceNotFoundException.class,
                () -> service.latestVersionPreviewImages(a.getApplicationId(), null, ACC_ONE));
        assertThrows(ResourceNotFoundException.class,
                () -> service.latestVersionPreviewImages(a.getApplicationId(), "MANAGER", SYS));

        versions.clear();
        assertEquals(new String(LIVE_PREVIEW),
                page(service.latestVersionPreviewImages(a.getApplicationId(), null, MGR_ONE), 0));
        when(renderer.renderErmPreviewPdf(a)).thenThrow(
                new WebAgreementRenderer.RenderException(new IOException("soffice not found")));
        assertEquals("Couldn't render the latest version.", assertThrows(WebAgreementApproverService.DocumentFailed.class,
                () -> service.latestVersionPreviewImages(a.getApplicationId(), null, MGR_ONE)).getMessage());
    }

    @Test
    void theSignedPreviewWaitsForTheCountersignAndIsALiveFinalRender() {
        WebAgreement a = agreement("READY_TO_SIGN", 1);
        gate(a, "MANAGER", 1, MGR_ONE, "APPROVED");
        WebAgreementApproverController controller = new WebAgreementApproverController(service);

        ResponseEntity<ApiResponse<Map<String, Object>>> early =
                controller.signedPreviewImages(a.getApplicationId(), null, auth(MGR_ONE));
        assertEquals(409, early.getStatusCode().value());
        assertEquals("The signed agreement is available once the ERM has signed it.", early.getBody().getMessage());

        a.setStatus("COMPLETED");
        ResponseEntity<ApiResponse<Map<String, Object>>> ok =
                controller.signedPreviewImages(a.getApplicationId(), null, auth(MGR_ONE));
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("Signed agreement ready", ok.getBody().getMessage());
        assertEquals(new String(LIVE_FINAL), page(ok.getBody().getData(), 0));
        assertThrows(ResourceNotFoundException.class,
                () -> service.signedPreviewImages(a.getApplicationId(), null, MGR_TWO));

        when(renderer.renderFinalPdf(a)).thenThrow(
                new WebAgreementRenderer.RenderException(new IOException("soffice not found")));
        assertEquals("Couldn't render the signed agreement.",
                controller.signedPreviewImages(a.getApplicationId(), null, auth(MGR_ONE)).getBody().getMessage());
    }

    @Test
    void thePhaseOneCopyIsForAManagerWhoApprovedItInAnyRound() {
        WebAgreement a = agreement("AWAITING_APPROVALS", 2);
        gate(a, "MANAGER", 1, MGR_ONE, "APPROVED");           // Phase 1
        gate(a, "MANAGER", 2, MGR_TWO, "PENDING");            // Phase 2 routed to another Manager
        gate(a, "ACCOUNTS", 2, ACC_ONE, "APPROVED");
        a.setPhase1FinalPdfS3Key("s3:participant-documents/10/web-agreement-final-p1.pdf");
        stored.put(a.getPhase1FinalPdfS3Key(), "%PDF phase 1 executed".getBytes());
        WebAgreementApproverController controller = new WebAgreementApproverController(service);

        ResponseEntity<ApiResponse<Map<String, Object>>> accounts =
                controller.phase1SignedPreviewImages(a.getApplicationId(), null, auth(ACC_ONE));
        assertEquals(403, accounts.getStatusCode().value());
        assertEquals("The Phase 1 signed agreement is available to the Manager only.", accounts.getBody().getMessage());

        Map<String, Object> p = service.phase1SignedPreviewImages(a.getApplicationId(), null, MGR_ONE);
        assertEquals("%PDF phase 1 executed", page(p, 0), "the stored copy, not a live render");
        verify(renderer, never()).renderFinalPdf(any());
        assertThrows(ResourceNotFoundException.class,
                () -> service.phase1SignedPreviewImages(a.getApplicationId(), null, MGR_TWO), "not approved (yet)");

        stored.remove(a.getPhase1FinalPdfS3Key());
        assertEquals("Couldn't render the Phase 1 signed agreement.",
                controller.phase1SignedPreviewImages(a.getApplicationId(), null, auth(MGR_ONE)).getBody().getMessage());

        a.setPhase1FinalPdfS3Key(null);
        ResponseEntity<ApiResponse<Map<String, Object>>> none =
                controller.phase1SignedPreviewImages(a.getApplicationId(), null, auth(MGR_ONE));
        assertEquals(409, none.getStatusCode().value());
        assertEquals("No Phase 1 signed agreement on file for this agreement.", none.getBody().getMessage());
    }

    // ── Downloads ────────────────────────────────────────────────────

    @Test
    void downloadsNeedMyApprovalInAnyRoundAndDefaultToTheFinalPdf() {
        WebAgreement a = agreement("AWAITING_APPROVALS", 2);
        gate(a, "MANAGER", 1, MGR_ONE, "APPROVED");
        gate(a, "MANAGER", 2, MGR_TWO, "PENDING");
        gate(a, "ACCOUNTS", 2, ACC_ONE, "REVISION_REQUESTED");
        version(a, 1);
        version(a, 2);
        a.setApprovalVersionNumber(1);

        // approved = the LATEST version, whichever one I reviewed.
        WebAgreementFileService.Download copy = service.download(a.getApplicationId(), "approved", null, MGR_ONE);
        assertEquals("%PDF version V2 of " + a.getApplicationId(), new String(copy.bytes()));
        assertEquals("approved-copy-" + FILENAME, copy.filename());
        assertEquals("application/pdf", copy.contentType());
        assertThrows(ResourceNotFoundException.class,
                () -> service.download(a.getApplicationId(), "approved", null, MGR_TWO), "routed, not approved");
        assertThrows(ResourceNotFoundException.class,
                () -> service.download(a.getApplicationId(), "approved", null, ACC_ONE), "declined, not approved");
        versions.clear();
        assertEquals(new String(LIVE_PREVIEW),
                new String(service.download(a.getApplicationId(), "approved", null, MGR_ONE).bytes()));

        // No doc = final, which waits for the countersign.
        WebAgreementApproverService.Refused early = assertThrows(WebAgreementApproverService.Refused.class,
                () -> service.download(a.getApplicationId(), null, null, MGR_ONE));
        assertEquals(409, early.getStatus());
        assertEquals("The signed agreement is available once the ERM has signed it.", early.getMessage());
        a.setStatus("COMPLETED");
        a.setS3Key("s3:participant-documents/10/web-agreement-final-p2.pdf");
        stored.put(a.getS3Key(), "%PDF stored final".getBytes());
        WebAgreementFileService.Download fin = service.download(a.getApplicationId(), " ", null, MGR_ONE);
        assertEquals("%PDF stored final", new String(fin.bytes()));
        assertEquals("signed-" + FILENAME, fin.filename());
        assertEquals("%PDF stored final",
                new String(service.download(a.getApplicationId(), "FINAL", null, MGR_ONE).bytes()));
        stored.remove(a.getS3Key());
        assertEquals(new String(LIVE_FINAL),
                new String(service.download(a.getApplicationId(), "final", null, MGR_ONE).bytes()),
                "an unreadable stored PDF falls back to a live final render");

        // phase1: Managers only, and only when a copy is on file.
        WebAgreementApproverService.Refused accounts = assertThrows(WebAgreementApproverService.Refused.class,
                () -> service.download(a.getApplicationId(), "phase1", null, ACC_ONE));
        assertEquals(403, accounts.getStatus());
        assertEquals("The Phase 1 signed agreement is available to the Manager only.", accounts.getMessage());
        assertEquals(409, assertThrows(WebAgreementApproverService.Refused.class,
                () -> service.download(a.getApplicationId(), "phase1", null, MGR_ONE)).getStatus());
        a.setPhase1FinalPdfS3Key("s3:participant-documents/10/web-agreement-final-p1.pdf");
        stored.put(a.getPhase1FinalPdfS3Key(), "%PDF phase 1 executed".getBytes());
        WebAgreementFileService.Download p1 = service.download(a.getApplicationId(), "phase1", null, MGR_ONE);
        assertEquals("%PDF phase 1 executed", new String(p1.bytes()));
        assertEquals("phase1-signed-" + FILENAME, p1.filename());

        WebAgreementApproverService.Refused bad = assertThrows(WebAgreementApproverService.Refused.class,
                () -> service.download(a.getApplicationId(), "certificate", null, MGR_ONE));
        assertEquals(400, bad.getStatus());
        assertEquals("doc must be final, phase1 or approved.", bad.getMessage());

        a.setDeleted(true);
        assertThrows(ResourceNotFoundException.class,
                () -> service.download(a.getApplicationId(), "final", null, MGR_ONE));
    }

    @Test
    void theDownloadRouteAnswersTheConsolesWay() {
        WebAgreement a = agreement("COMPLETED", 1);
        gate(a, "MANAGER", 1, MGR_ONE, "APPROVED");
        a.setS3Key("s3:participant-documents/10/web-agreement-final-p1.pdf");
        stored.put(a.getS3Key(), "%PDF stored final".getBytes());
        WebAgreementApproverController controller = new WebAgreementApproverController(service);

        ResponseEntity<byte[]> ok = controller.downloadPdf(a.getApplicationId(), null, null, auth(MGR_ONE));
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("%PDF stored final", new String(ok.getBody()));
        assertEquals("attachment; filename=\"signed-" + FILENAME + "\"",
                ok.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("private, no-store", ok.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals("application/pdf", ok.getHeaders().getContentType().toString());

        ResponseEntity<byte[]> refused = controller.downloadPdf(a.getApplicationId(), "phase1", null, auth(MGR_ONE));
        assertEquals(409, refused.getStatusCode().value());
        assertEquals("No Phase 1 signed agreement on file for this agreement.",
                refused.getHeaders().getFirst("X-Preview-Error"));
        assertNull(refused.getBody());

        ResponseEntity<byte[]> wrongDoc = controller.downloadPdf(a.getApplicationId(), "x", null, auth(MGR_ONE));
        assertEquals(400, wrongDoc.getStatusCode().value());

        stored.clear();
        when(renderer.renderFinalPdf(a)).thenThrow(
                new WebAgreementRenderer.RenderException(new IOException("soffice not found")));
        ResponseEntity<byte[]> failed = controller.downloadPdf(a.getApplicationId(), "final", null, auth(MGR_ONE));
        assertEquals(500, failed.getStatusCode().value());
        assertEquals("IOException: soffice not found", failed.getHeaders().getFirst("X-Preview-Error"));

        // The gate's "not found" is the normal JSON 404, as in the console.
        assertThrows(ResourceNotFoundException.class,
                () -> controller.downloadPdf(a.getApplicationId(), "final", null, auth(MGR_TWO)));
    }

    // ── All agreements and Approved agreements ───────────────────────

    @Test
    void allAgreementsListsEveryAgreementRoutedToMeWithTheSummaryAndNoPii() {
        WebAgreement first = agreement("APPROVAL_REVISION_REQUESTED", 1);
        gate(first, "MANAGER", 1, MGR_ONE, "REVISION_REQUESTED");
        WebAgreement reRouted = agreement("READY_TO_SIGN", 1);
        gate(reRouted, "MANAGER", 1, MGR_ONE, "REVISION_REQUESTED");
        gate(reRouted, "MANAGER", 2, MGR_TWO, "APPROVED");
        WebAgreement unrouted = agreement("AWAITING_APPROVALS", 1);
        gate(unrouted, "MANAGER", 1, null, "PENDING");
        WebAgreement archived = agreement("COMPLETED", 1);
        archived.setDeleted(true);
        gate(archived, "MANAGER", 1, MGR_ONE, "APPROVED");
        events.add(WebAgreementEvent.builder().agreementId(reRouted.getId())
                .eventType("SENT_FOR_APPROVAL").createdAt(LocalDateTime.of(2026, 10, 1, 8, 0)).build());
        events.add(WebAgreementEvent.builder().agreementId(reRouted.getId())
                .eventType("SENT_FOR_APPROVAL").createdAt(LocalDateTime.of(2026, 10, 3, 8, 0)).build());

        List<WebAgreement> mine = service.applications(null, MGR_ONE);
        assertEquals(List.of(reRouted, first), mine, "any round and status, newest change first");
        assertEquals("Erin Manager", reRouted.getOwnerName());
        assertEquals("APPROVED", reRouted.getManagerStatus(), "the latest round's gate");
        assertNull(reRouted.getAccountsStatus());
        assertEquals("2026-10-01T08:00", reRouted.getSentForApprovalAt(), "the first send");
        mine.forEach(WebAgreementApproverServiceTest::assertNoPii);
        verify(entityManager, atLeastOnce()).detach(first);

        assertEquals(List.of(reRouted), service.applications(null, MGR_TWO));
        assertEquals(List.of(), service.applications(null, ACC_ONE));
        assertEquals(List.of(), service.applications("MANAGER", SYS), "an un-routed gate is no one's");
    }

    @Test
    void approvedAgreementsIsOneRecordPerAgreementFromMyLatestApproval() {
        WebAgreement a = agreement("AWAITING_APPROVALS", 2);
        WebAgreementApproval p1 = gate(a, "MANAGER", 1, MGR_ONE, "APPROVED");
        p1.setPhase(1);
        WebAgreementApproval p2 = gate(a, "MANAGER", 2, MGR_ONE, "APPROVED");
        a.setPhase1FinalPdfS3Key("s3:participant-documents/10/web-agreement-final-p1.pdf");
        WebAgreement orphan = agreement("COMPLETED", 1);
        orphan.setOwnerUserId(GONE_ERM);
        gate(orphan, "MANAGER", 1, MGR_ONE, "APPROVED");
        WebAgreement declined = agreement("APPROVAL_REVISION_REQUESTED", 1);
        gate(declined, "MANAGER", 1, MGR_ONE, "REVISION_REQUESTED");
        WebAgreement archived = agreement("COMPLETED", 1);
        archived.setDeleted(true);
        gate(archived, "MANAGER", 1, MGR_ONE, "APPROVED");

        List<Map<String, Object>> recs = service.approved(null, MGR_ONE);
        assertEquals(List.of(orphan.getApplicationId(), a.getApplicationId()),
                recs.stream().map(r -> r.get("appId")).toList(), "newest approval first, one per agreement");
        Map<String, Object> rec = recs.get(1);
        assertEquals(List.of("appId", "consultantName", "consultantEmail", "ermId", "ermName", "phase",
                "decidedAt", "status", "hasPhase1Signed"), List.copyOf(rec.keySet()));
        assertEquals("Pat Lee", rec.get("consultantName"));
        assertEquals("pat@x.com", rec.get("consultantEmail"));
        assertEquals(ERM, rec.get("ermId"));
        assertEquals("Erin Manager", rec.get("ermName"));
        assertEquals(2, rec.get("phase"), "the phase of my latest approval");
        assertEquals(p2.getDecidedAt().toString(), rec.get("decidedAt"));
        assertNotEquals(p1.getDecidedAt().toString(), rec.get("decidedAt"));
        assertEquals("AWAITING_APPROVALS", rec.get("status"), "the agreement's status now");
        assertEquals(true, rec.get("hasPhase1Signed"));
        assertEquals("(unassigned ERM)", recs.get(0).get("ermName"));
        assertEquals(false, recs.get(0).get("hasPhase1Signed"));

        // Accounts never gets the Phase 1 copy.
        gate(a, "ACCOUNTS", 2, ACC_ONE, "APPROVED");
        assertEquals(false, service.approved(null, ACC_ONE).get(0).get("hasPhase1Signed"));
        assertEquals(List.of(), service.approved(null, MGR_TWO));
    }

    // ── The decision response ────────────────────────────────────────

    @Test
    void theDecisionResponseIsStrippedByTheControllerOnlyAfterTheDecisionCommits() throws Exception {
        Transactional tx = WebAgreementApproverService.class.getMethod("decide", String.class, boolean.class,
                String.class, String.class, Long.class, HttpServletRequest.class).getAnnotation(Transactional.class);
        assertNotNull(tx, "one transaction, as the console's");
        assertFalse(tx.readOnly());

        WebAgreement a = agreement("AWAITING_APPROVALS", 1);
        gate(a, "MANAGER", 1, MGR_ONE, "PENDING");

        // The service itself hands back the agreement unstripped and never detaches it.
        WebAgreement b = agreement("AWAITING_APPROVALS", 1);
        gate(b, "MANAGER", 1, MGR_ONE, "PENDING");
        assertEquals("123456789", service.decide(b.getApplicationId(), true, null, null, MGR_ONE, request)
                .getBgFullSsn());
        verify(entityManager, never()).detach(any());

        // Through Spring's transaction interceptor: the controller strips after the commit.
        CountingManager txManager = new CountingManager();
        ProxyFactory pf = new ProxyFactory(service);
        pf.setProxyTargetClass(true);
        pf.addAdvice(new TransactionInterceptor((TransactionManager) txManager,
                new AnnotationTransactionAttributeSource()));
        WebAgreementApproverController controller =
                new WebAgreementApproverController((WebAgreementApproverService) pf.getProxy());
        EntityManager controllerEm = mock(EntityManager.class);
        int[] commitsAtDetach = {-1};
        doAnswer(inv -> {
            commitsAtDetach[0] = txManager.committed;
            return null;
        }).when(controllerEm).detach(any());
        ReflectionTestUtils.setField(controller, "entityManager", controllerEm);

        saves.clear();
        ResponseEntity<ApiResponse<WebAgreement>> res = controller.approve(a.getApplicationId(), null,
                auth(MGR_ONE), request);
        assertEquals("Approved", res.getBody().getMessage());
        WebAgreement body = res.getBody().getData();
        assertEquals("READY_TO_SIGN", body.getStatus());
        assertNoPii(body);
        verify(controllerEm).detach(a);
        assertEquals(1, commitsAtDetach[0], "detached only after the decision committed");
        assertEquals(1, saves.size());
        assertArrayEquals(new String[] {a.getApplicationId(), "READY_TO_SIGN", "123456789", "000123"}, saves.get(0),
                "the decision is saved with the agreement as it was, PII included");

        // A revision request: same, with its own message.
        WebAgreement c = agreement("AWAITING_APPROVALS", 1);
        gate(c, "MANAGER", 1, MGR_ONE, "PENDING");
        WebAgreementApproverController.DecisionBody revise = new WebAgreementApproverController.DecisionBody();
        revise.note = "Fix the rate card";
        ResponseEntity<ApiResponse<WebAgreement>> rev = controller.requestRevision(c.getApplicationId(), revise,
                auth(MGR_ONE), request);
        assertEquals("Revision requested", rev.getBody().getMessage());
        assertEquals("APPROVAL_REVISION_REQUESTED", rev.getBody().getData().getStatus());
        assertNoPii(rev.getBody().getData());
        assertEquals(2, txManager.committed);
        assertEquals(0, txManager.rolledBack);

        // A refused decision rolls back and strips nothing.
        assertThrows(IllegalStateException.class,
                () -> controller.approve(c.getApplicationId(), null, auth(MGR_ONE), request));
        assertEquals(1, txManager.rolledBack);
        verify(controllerEm, times(2)).detach(any());
    }

    /** Counts commits and rollbacks of Spring's transaction interceptor. */
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
