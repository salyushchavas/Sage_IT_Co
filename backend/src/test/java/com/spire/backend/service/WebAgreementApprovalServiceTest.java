package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementApproval;
import com.spire.backend.entity.WebAgreementErmAssignment;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.entity.WebAgreementVersion;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementApprovalRepository;
import com.spire.backend.repository.WebAgreementErmAssignmentRepository;
import com.spire.backend.repository.WebAgreementEventRepository;
import com.spire.backend.repository.WebAgreementRepository;
import com.spire.backend.repository.WebAgreementVersionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sending a website agreement for approval (the console's sendForApproval,
 * eligibleApprovers and approvalBoard): only from a verified agreement or
 * after an approver's revision request; the picks come from the OWNING
 * ERM's team with the console's messages; Phase 1 opens one gate and
 * Phase 2 two, in a round counted across the whole agreement; the version
 * defaults to the latest and an unknown one rolls the whole send back; the
 * board is the ERM's own (every one for admins) without sensitive PII; and
 * the list summary reads the latest round and the first send.
 */
class WebAgreementApprovalServiceTest {

    private static final long PAT = 10L, ERM = 20L, OTHER_ERM = 21L, OPS = 30L, SYS = 31L, COACH = 40L,
            MGR_ONE = 50L, MGR_TWO = 51L, MGR_OFF = 52L, MGR_REROLED = 53L, MGR_ELSEWHERE = 54L,
            ACC_ONE = 60L;

    private final List<User> users = new ArrayList<>();
    private final List<WebAgreement> agreements = new ArrayList<>();
    /** web_agreement_approvals. */
    private final List<WebAgreementApproval> gates = new ArrayList<>();
    /** web_agreement_versions. */
    private final List<WebAgreementVersion> versions = new ArrayList<>();
    /** web_agreement_erm_assignments. */
    private final List<WebAgreementErmAssignment> links = new ArrayList<>();
    private final List<WebAgreementEvent> events = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private WebAgreementRepository repo;
    private WebAgreementApprovalRepository gateRepo;
    private WebAgreementVersionRepository versionRepo;
    private WebAgreementEventRepository eventRepo;
    private EntityManager entityManager;
    private WebAgreementApprovalService service;
    private MockHttpServletRequest request;
    private LocalDateTime clock = LocalDateTime.of(2026, 10, 7, 9, 0);

    @BeforeEach
    void setUp() {
        users.add(User.builder().id(PAT).email("pat@x.com").fullName("Pat Lee")
                .participantId("SAGE-2026-00010").role(Role.builder().name("PARTICIPANT").build())
                .isActive(true).build());
        user(ERM, "ERM", "Erin Manager", true);
        user(OTHER_ERM, "ERM", "Omar Other", true);
        user(OPS, "OPERATIONS_ADMIN", "Ops Admin", true);
        user(SYS, "SYSTEM_ADMIN", "Sys Admin", true);
        user(COACH, "COACH", "Coach Carter", true);
        user(MGR_ONE, "MANAGER", "Mona One", true);
        user(MGR_TWO, "MANAGER", "Al Two", true);
        user(MGR_OFF, "MANAGER", "Off Manager", false);
        user(MGR_REROLED, "ERM", "Was Manager", true);
        user(MGR_ELSEWHERE, "MANAGER", "Elsa Where", true);
        user(ACC_ONE, "ACCOUNTS", "Ash Counts", true);

        // ERM's team: two active Managers, one deactivated, one re-roled since; one Accounts.
        link(ERM, MGR_ONE, "MANAGER");
        link(ERM, MGR_TWO, "MANAGER");
        link(ERM, MGR_OFF, "MANAGER");
        link(ERM, MGR_REROLED, "MANAGER");
        link(ERM, ACC_ONE, "ACCOUNTS");
        // Another ERM's Manager, and a removed link (never offered).
        link(OTHER_ERM, MGR_ELSEWHERE, "MANAGER");
        link(ERM, MGR_ELSEWHERE, "MANAGER").setRemovedAt(LocalDateTime.now());

        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> users.stream()
                .filter(u -> u.getId().equals(inv.getArgument(0))).findFirst());
        when(userRepo.findAllById(any())).thenAnswer(inv -> {
            Iterable<?> ids = inv.getArgument(0);
            List<Object> wanted = new ArrayList<>();
            ids.forEach(wanted::add);
            return users.stream().filter(u -> wanted.contains(u.getId())).toList();
        });

        repo = mock(WebAgreementRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByApplicationId(anyString())).thenAnswer(inv -> agreements.stream()
                .filter(a -> a.getApplicationId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByStatusInAndDeletedFalseOrderByUpdatedAtDesc(any())).thenAnswer(inv -> {
            Collection<?> st = inv.getArgument(0);
            return live(a -> st.contains(a.getStatus()));
        });
        when(repo.findByOwnerUserIdAndStatusInAndDeletedFalseOrderByUpdatedAtDesc(anyLong(), any())).thenAnswer(inv -> {
            Collection<?> st = inv.getArgument(1);
            return live(a -> a.getOwnerUserId().equals(inv.getArgument(0)) && st.contains(a.getStatus()));
        });

        gateRepo = mock(WebAgreementApprovalRepository.class);
        when(gateRepo.save(any(WebAgreementApproval.class))).thenAnswer(inv -> {
            WebAgreementApproval g = inv.getArgument(0);
            if (g.getId() == null) {
                g.setId((long) gates.size() + 1);
                g.setCreatedAt(tick());
                gates.add(g);
            }
            return g;
        });
        when(gateRepo.maxRound(anyLong())).thenAnswer(inv -> gates.stream()
                .filter(g -> g.getAgreementId().equals(inv.getArgument(0)))
                .map(WebAgreementApproval::getRound).max(Integer::compare).orElse(null));
        when(gateRepo.findByAgreementIdOrderByCreatedAtAsc(anyLong())).thenAnswer(inv -> gates.stream()
                .filter(g -> g.getAgreementId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparing(WebAgreementApproval::getCreatedAt)).toList());
        when(gateRepo.findByAgreementIdIn(any())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return gates.stream().filter(g -> ids.contains(g.getAgreementId())).toList();
        });

        versionRepo = mock(WebAgreementVersionRepository.class);
        when(versionRepo.findTopByAgreementIdOrderByVersionNumberDesc(anyLong())).thenAnswer(inv -> versions.stream()
                .filter(v -> v.getAgreementId().equals(inv.getArgument(0)))
                .max(Comparator.comparing(WebAgreementVersion::getVersionNumber)));
        when(versionRepo.findByAgreementIdAndVersionNumber(anyLong(), anyInt())).thenAnswer(inv -> versions.stream()
                .filter(v -> v.getAgreementId().equals(inv.getArgument(0))
                        && v.getVersionNumber().equals(inv.getArgument(1))).findFirst());

        WebAgreementErmAssignmentRepository linkRepo = mock(WebAgreementErmAssignmentRepository.class);
        when(linkRepo.findByErmUserIdAndRoleAndRemovedAtIsNull(any(), anyString())).thenAnswer(inv -> links.stream()
                .filter(l -> Objects.equals(l.getErmUserId(), inv.getArgument(0))
                        && l.getRole().equals(inv.getArgument(1)) && l.getRemovedAt() == null).toList());

        eventRepo = mock(WebAgreementEventRepository.class);
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

        WebAgreementAccess access = new WebAgreementAccess(repo, userRepo);
        WebAgreementAssignmentService assignments = new WebAgreementAssignmentService(
                linkRepo, gateRepo, userRepo, access);
        service = new WebAgreementApprovalService(repo, gateRepo, versionRepo, assignments,
                new WebAgreementEventService(eventRepo), access);
        entityManager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9");
    }

    private void user(long id, String role, String name, boolean active) {
        users.add(User.builder().id(id).fullName(name).email(name.replace(' ', '.').toLowerCase() + "@sageitco.com")
                .role(Role.builder().name(role).build()).isActive(active).build());
    }

    private WebAgreementErmAssignment link(long erm, long approver, String role) {
        WebAgreementErmAssignment l = WebAgreementErmAssignment.builder().id((long) links.size() + 1)
                .ermUserId(erm).approverUserId(approver).role(role).build();
        links.add(l);
        return l;
    }

    private LocalDateTime tick() {
        clock = clock.plusMinutes(1);
        return clock;
    }

    private List<WebAgreement> live(Predicate<WebAgreement> filter) {
        return agreements.stream().filter(a -> !Boolean.TRUE.equals(a.getDeleted())).filter(filter).toList();
    }

    /** A verified (released) Phase-1 agreement owned by {@code owner}, with V1. */
    private WebAgreement verified(long owner) {
        WebAgreement a = WebAgreementRulesTest.complete();
        a.setId((long) agreements.size() + 1);
        a.setApplicationId("app-" + a.getId());
        a.setParticipantUserId(PAT);
        a.setOwnerUserId(owner);
        a.setStatus("VERIFIED");
        a.setConsultantCopyReleased(true);
        a.setPhase(1);
        a.setUpdatedAt(tick());
        a.setBgFullSsn("123456789");
        a.setAchAccountNumber("000123");
        a.setAchRoutingNumber("021000021");
        a.setBgDriverLicense("D1234");
        a.setBgStateId("S999");
        a.setBgDateOfBirth(LocalDate.of(1990, 1, 1));
        agreements.add(a);
        version(a, 1);
        return a;
    }

    private void version(WebAgreement a, int n) {
        versions.add(WebAgreementVersion.builder().id((long) versions.size() + 1).agreementId(a.getId())
                .versionNumber(n).s3Key("stored-v" + n).phase(a.getPhase()).build());
    }

    private List<WebAgreementApproval> gatesOf(WebAgreement a) {
        return gates.stream().filter(g -> g.getAgreementId().equals(a.getId())).toList();
    }

    private List<WebAgreementEvent> eventsOf(WebAgreement a, WebAgreementEvent.EventType type) {
        return events.stream()
                .filter(e -> e.getAgreementId().equals(a.getId()) && e.getEventType().equals(type.name()))
                .toList();
    }

    private WebAgreement send(WebAgreement a, Long mgr, Long acc, Integer version) {
        return service.sendForApproval(a.getApplicationId(), mgr, acc, version, ERM, request);
    }

    // ── When a send is allowed ───────────────────────────────────────

    @Test
    void sendIsOnlyFromAVerifiedAgreementOrAfterAnApproversRevisionRequest() {
        WebAgreement a = verified(ERM);
        for (String st : List.of("SUBMITTED", "REVISION_REQUESTED", "AWAITING_APPROVALS", "READY_TO_SIGN",
                "COMPLETED", "CANCELLED")) {
            a.setStatus(st);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> send(a, MGR_ONE, null, null), st);
            assertEquals("Send for Approval is only available from VERIFIED (after the agreement is verified) "
                    + "or APPROVAL_REVISION_REQUESTED (status=" + st + ").", e.getMessage());
        }

        a.setStatus("VERIFIED");
        a.setConsultantCopyReleased(false);
        IllegalStateException notVerified = assertThrows(IllegalStateException.class,
                () -> send(a, MGR_ONE, null, null));
        assertEquals("Verify the agreement before sending it for approval.", notVerified.getMessage());
        assertTrue(gates.isEmpty());
        assertTrue(events.isEmpty());

        a.setConsultantCopyReleased(true);
        assertEquals("AWAITING_APPROVALS", send(a, MGR_ONE, null, null).getStatus());

        // The re-send needs no release flag: it comes from the approvers' desk.
        a.setStatus("APPROVAL_REVISION_REQUESTED");
        a.setConsultantCopyReleased(false);
        assertEquals("AWAITING_APPROVALS", send(a, MGR_TWO, null, null).getStatus());
    }

    @Test
    void anotherErmGetsNotFoundAndAnAdminSendsFromTheOwnersTeam() {
        WebAgreement a = verified(ERM);
        assertThrows(ResourceNotFoundException.class,
                () -> service.sendForApproval(a.getApplicationId(), MGR_ELSEWHERE, null, null, OTHER_ERM, request));
        assertThrows(AccessDeniedException.class,
                () -> service.sendForApproval(a.getApplicationId(), MGR_ONE, null, null, COACH, request));
        assertThrows(AccessDeniedException.class,
                () -> service.sendForApproval(a.getApplicationId(), MGR_ONE, null, null, MGR_ONE, request));

        // An admin routes from the OWNER's team, never their own.
        WebAgreement out = service.sendForApproval(a.getApplicationId(), MGR_ONE, null, null, OPS, request);
        assertEquals("AWAITING_APPROVALS", out.getStatus());
        assertEquals(OPS, eventsOf(a, WebAgreementEvent.EventType.SENT_FOR_APPROVAL).get(0).getActorUserId());
    }

    // ── Picks ────────────────────────────────────────────────────────

    @Test
    void everyPickErrorUsesTheConsolesWords() {
        WebAgreement a = verified(ERM);
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> send(a, null, null, null));
        assertEquals("Select a manager to send for approval.", none.getMessage());

        IllegalArgumentException off = assertThrows(IllegalArgumentException.class,
                () -> send(a, MGR_OFF, null, null));
        assertEquals("The selected manager is no longer active. Ask an admin to reactivate or assign another.",
                off.getMessage());
        IllegalArgumentException reroled = assertThrows(IllegalArgumentException.class,
                () -> send(a, MGR_REROLED, null, null));
        assertEquals(off.getMessage(), reroled.getMessage(), "re-roled since: same as deactivated");

        IllegalArgumentException elsewhere = assertThrows(IllegalArgumentException.class,
                () -> send(a, MGR_ELSEWHERE, null, null));
        assertEquals("The selected manager is not assigned to this ERM.", elsewhere.getMessage(),
                "another ERM's Manager, and a removed link, are not on the team");
        assertThrows(IllegalArgumentException.class, () -> send(a, 999L, null, null));

        // Phase 2 needs an Accounts pick too.
        a.setPhase(2);
        IllegalArgumentException noAccounts = assertThrows(IllegalArgumentException.class,
                () -> send(a, MGR_ONE, null, null));
        assertEquals("Select a accounts to send for approval.", noAccounts.getMessage());
        IllegalArgumentException mgrAsAccounts = assertThrows(IllegalArgumentException.class,
                () -> send(a, MGR_ONE, MGR_TWO, null));
        assertEquals("The selected accounts is not assigned to this ERM.", mgrAsAccounts.getMessage());

        assertTrue(gates.isEmpty(), "nothing is written while a pick is wrong");
        assertEquals("VERIFIED", a.getStatus());
    }

    @Test
    void anEmptyTeamIsRefusedWithNoManagerAssigned() {
        WebAgreement a = verified(ERM);
        links.removeIf(l -> l.getRole().equals("MANAGER") && l.getErmUserId() == ERM);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> send(a, MGR_ONE, null, null));
        assertEquals("No manager assigned — ask an admin to assign one.", e.getMessage());

        // Only deactivated / re-roled approvers left counts as no team at all.
        link(ERM, MGR_OFF, "MANAGER");
        assertThrows(IllegalStateException.class, () -> send(a, MGR_OFF, null, null));

        WebAgreement b = verified(ERM);
        b.setPhase(2);
        link(ERM, MGR_ONE, "MANAGER");
        links.removeIf(l -> l.getRole().equals("ACCOUNTS"));
        IllegalStateException noAccounts = assertThrows(IllegalStateException.class,
                () -> send(b, MGR_ONE, ACC_ONE, null));
        assertEquals("No accounts assigned — ask an admin to assign one.", noAccounts.getMessage());
        assertTrue(gates.isEmpty());
    }

    @Test
    void anAgreementOwnedByAnAdminCanNeverBeSent() {
        // Only ERM users have a team, so an admin's own agreement has no Manager (L-B1).
        WebAgreement a = verified(OPS);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.sendForApproval(a.getApplicationId(), MGR_ONE, null, null, OPS, request));
        assertEquals("No manager assigned — ask an admin to assign one.", e.getMessage());
    }

    // ── Gates, rounds, version, event ────────────────────────────────

    @Test
    void phaseOneOpensOneManagerGate() throws Exception {
        WebAgreement a = verified(ERM);
        WebAgreement out = send(a, MGR_ONE, null, null);

        assertEquals("AWAITING_APPROVALS", out.getStatus());
        assertEquals(1, out.getApprovalVersionNumber());
        verify(repo).save(a);
        List<WebAgreementApproval> rows = gatesOf(a);
        assertEquals(1, rows.size());
        WebAgreementApproval g = rows.get(0);
        assertEquals("MANAGER", g.getRole());
        assertEquals("PENDING", g.getStatus());
        assertEquals(1, g.getPhase());
        assertEquals(1, g.getRound());
        assertEquals(MGR_ONE, g.getApproverUserId());
        assertEquals("Mona One", g.getApproverName());
        assertNull(g.getDecidedBy());

        WebAgreementEvent sent = eventsOf(a, WebAgreementEvent.EventType.SENT_FOR_APPROVAL).get(0);
        assertEquals("ERM", sent.getActorType());
        assertEquals(ERM, sent.getActorUserId(), "the real users.id");
        JsonNode m = mapper.readTree(sent.getMetadata());
        assertEquals(1, m.path("phase").asInt());
        assertEquals(1, m.path("round").asInt());
        assertEquals("[\"MANAGER\"]", m.path("approvers").toString());
        assertEquals("[\"Mona One\"]", m.path("routedTo").toString());
        assertFalse(m.path("resend").asBoolean());
        assertEquals("1", m.path("version").asText());
        assertTrue(eventsOf(a, WebAgreementEvent.EventType.EMAIL_SENT).isEmpty(), "no email is sent");
    }

    @Test
    void phaseTwoOpensAManagerAndAnAccountsGateInTheSameRound() throws Exception {
        WebAgreement a = verified(ERM);
        a.setPhase(2);
        send(a, MGR_TWO, ACC_ONE, null);

        List<WebAgreementApproval> rows = gatesOf(a);
        assertEquals(List.of("MANAGER", "ACCOUNTS"), rows.stream().map(WebAgreementApproval::getRole).toList());
        assertTrue(rows.stream().allMatch(g -> g.getPhase() == 2 && g.getRound() == 1
                && g.getStatus().equals("PENDING")));
        assertEquals(List.of(MGR_TWO, ACC_ONE), rows.stream().map(WebAgreementApproval::getApproverUserId).toList());
        JsonNode m = mapper.readTree(eventsOf(a, WebAgreementEvent.EventType.SENT_FOR_APPROVAL).get(0).getMetadata());
        assertEquals("[\"MANAGER\",\"ACCOUNTS\"]", m.path("approvers").toString());
        assertEquals("[\"Al Two\",\"Ash Counts\"]", m.path("routedTo").toString());
    }

    @Test
    void roundsCountAcrossTheWholeAgreementAndAReSendOpensFreshGates() throws Exception {
        WebAgreement a = verified(ERM);
        send(a, MGR_ONE, null, null);
        WebAgreementApproval first = gatesOf(a).get(0);
        first.setStatus("REVISION_REQUESTED");
        a.setStatus("APPROVAL_REVISION_REQUESTED");

        send(a, MGR_TWO, null, null);
        assertEquals(2, gatesOf(a).size());
        assertEquals("REVISION_REQUESTED", first.getStatus(), "earlier rounds stay on record");
        WebAgreementApproval second = gatesOf(a).get(1);
        assertEquals(2, second.getRound());
        assertEquals("PENDING", second.getStatus());
        JsonNode m = mapper.readTree(eventsOf(a, WebAgreementEvent.EventType.SENT_FOR_APPROVAL).get(1).getMetadata());
        assertTrue(m.path("resend").asBoolean());
        assertEquals(2, m.path("round").asInt());

        // Phase 2 continues the count: its first round is 3, both gates fresh.
        a.setPhase(2);
        a.setStatus("VERIFIED");
        a.setConsultantCopyReleased(true);
        send(a, MGR_ONE, ACC_ONE, null);
        assertEquals(List.of(3, 3), gatesOf(a).subList(2, 4).stream().map(WebAgreementApproval::getRound).toList());

        // Another agreement's rounds never count.
        WebAgreement b = verified(ERM);
        send(b, MGR_ONE, null, null);
        assertEquals(1, gatesOf(b).get(0).getRound());
    }

    @Test
    void theVersionDefaultsToTheLatestOrIsTheOneChosen() throws Exception {
        WebAgreement a = verified(ERM);
        version(a, 2);
        version(a, 3);
        assertEquals(3, send(a, MGR_ONE, null, null).getApprovalVersionNumber());

        a.setStatus("APPROVAL_REVISION_REQUESTED");
        assertEquals(1, send(a, MGR_ONE, null, 1).getApprovalVersionNumber(),
                "an older version can be routed (the phase is not checked)");
        JsonNode m = mapper.readTree(eventsOf(a, WebAgreementEvent.EventType.SENT_FOR_APPROVAL).get(1).getMetadata());
        assertEquals("1", m.path("version").asText());
    }

    @Test
    void anAgreementVerifiedBeforeVersionsExistedIsSentWithNoVersion() throws Exception {
        WebAgreement a = verified(ERM);
        versions.clear();
        WebAgreement out = send(a, MGR_ONE, null, null);
        assertNull(out.getApprovalVersionNumber(), "the approvers fall back to a live render");
        JsonNode m = mapper.readTree(eventsOf(a, WebAgreementEvent.EventType.SENT_FOR_APPROVAL).get(0).getMetadata());
        assertEquals("null", m.path("version").asText(), "String.valueOf(null), as the console writes it");
    }

    @Test
    void anUnknownVersionIsRefusedAfterTheGatesAreWrittenSoTheWholeSendRollsBack() throws Exception {
        WebAgreement a = verified(ERM);

        // One transaction, as the console's (CAS:2907).
        Method m = WebAgreementApprovalService.class.getMethod("sendForApproval",
                String.class, Long.class, Long.class, Integer.class, Long.class,
                jakarta.servlet.http.HttpServletRequest.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertNotNull(tx);
        assertFalse(tx.readOnly());

        // Through Spring's own transaction interceptor over a manager that
        // undoes the gate rows on rollback, like the database would.
        RollbackManager txManager = new RollbackManager();
        ProxyFactory pf = new ProxyFactory(service);
        pf.setProxyTargetClass(true);
        pf.addAdvice(new TransactionInterceptor((TransactionManager) txManager,
                new AnnotationTransactionAttributeSource()));
        WebAgreementApprovalService proxied = (WebAgreementApprovalService) pf.getProxy();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> proxied.sendForApproval(a.getApplicationId(), MGR_ONE, null, 99, ERM, request));
        assertEquals("Selected version V99 was not found for this agreement.", e.getMessage());

        // The gate WAS written before the check (the console's order) ...
        InOrder order = inOrder(gateRepo, versionRepo);
        order.verify(gateRepo).save(any(WebAgreementApproval.class));
        order.verify(versionRepo).findByAgreementIdAndVersionNumber(a.getId(), 99);
        // ... and the rollback leaves no PENDING row and no status change.
        assertEquals(1, txManager.rolledBack);
        assertEquals(0, txManager.committed);
        assertTrue(gatesOf(a).isEmpty(), "no stray open round");
        assertEquals("VERIFIED", a.getStatus());
        assertNull(a.getApprovalVersionNumber());
        verify(repo, never()).save(any());
        assertTrue(eventsOf(a, WebAgreementEvent.EventType.SENT_FOR_APPROVAL).isEmpty());

        // A good send through the same proxy commits.
        proxied.sendForApproval(a.getApplicationId(), MGR_ONE, null, 1, ERM, request);
        assertEquals(1, txManager.committed);
        assertEquals(1, gatesOf(a).size());
    }

    /** A transaction manager that drops gate rows written inside a rolled-back transaction. */
    private final class RollbackManager implements PlatformTransactionManager {
        int committed;
        int rolledBack;
        private List<WebAgreementApproval> before;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            before = new ArrayList<>(gates);
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack++;
            gates.clear();
            gates.addAll(before);
        }
    }

    // ── Eligible approvers ───────────────────────────────────────────

    @Test
    void thePickersOfferTheOwnersActiveTeamAndAccountsFromPhaseTwo() {
        WebAgreement a = verified(ERM);
        Map<String, Object> p1 = service.eligibleApprovers(a.getApplicationId(), ERM);
        assertEquals(1, p1.get("phase"));
        assertEquals(List.of(
                Map.of("id", MGR_TWO, "name", "Al Two", "email", "al.two@sageitco.com"),
                Map.of("id", MGR_ONE, "name", "Mona One", "email", "mona.one@sageitco.com")),
                p1.get("managers"), "active, still a Manager, sorted by name; removed links never");
        assertEquals(List.of(), p1.get("accounts"), "Phase 1 has no Accounts gate");

        a.setPhase(2);
        Map<String, Object> p2 = service.eligibleApprovers(a.getApplicationId(), OPS);
        assertEquals(2, p2.get("phase"));
        assertEquals(List.of(Map.of("id", ACC_ONE, "name", "Ash Counts", "email", "ash.counts@sageitco.com")),
                p2.get("accounts"));
        assertEquals(2, ((List<?>) p2.get("managers")).size(), "the owner's team, not the admin's");

        assertThrows(ResourceNotFoundException.class, () -> service.eligibleApprovers(a.getApplicationId(), OTHER_ERM));
        assertThrows(AccessDeniedException.class, () -> service.eligibleApprovers(a.getApplicationId(), COACH));

        WebAgreement opsOwned = verified(OPS);
        assertEquals(List.of(), service.eligibleApprovers(opsOwned.getApplicationId(), OPS).get("managers"));
    }

    // ── Board and list summary ───────────────────────────────────────

    @Test
    void theBoardIsTheErmsOwnForAnErmAndEveryOneForAdmins() {
        WebAgreement mine = verified(ERM);
        send(mine, MGR_ONE, null, null);
        WebAgreement ready = verified(ERM);
        ready.setStatus("READY_TO_SIGN");
        WebAgreement declined = verified(OTHER_ERM);
        declined.setStatus("APPROVAL_REVISION_REQUESTED");
        WebAgreement notInGate = verified(ERM);
        notInGate.setStatus("COMPLETED");
        WebAgreement archived = verified(ERM);
        archived.setStatus("AWAITING_APPROVALS");
        archived.setDeleted(true);

        List<Map<String, Object>> ermBoard = service.approvalBoard(ERM);
        assertEquals(List.of(mine, ready), ermBoard.stream().map(r -> r.get("application")).toList());
        verify(repo).findByOwnerUserIdAndStatusInAndDeletedFalseOrderByUpdatedAtDesc(eq(ERM),
                eq(List.of("AWAITING_APPROVALS", "APPROVAL_REVISION_REQUESTED", "READY_TO_SIGN")));
        assertEquals(gatesOf(mine), ermBoard.get(0).get("approvals"));
        assertEquals(List.of(), ermBoard.get(1).get("approvals"));

        for (long admin : new long[] {OPS, SYS}) {
            List<Object> all = service.approvalBoard(admin).stream().map(r -> r.get("application")).toList();
            assertEquals(3, all.size());
            assertTrue(all.containsAll(List.of(mine, ready, declined)));
        }
        assertEquals("Erin Manager", mine.getOwnerName());
        assertEquals("Omar Other", declined.getOwnerName());
        for (WebAgreement row : List.of(mine, ready, declined)) {
            assertNull(row.getBgFullSsn());
            assertNull(row.getAchAccountNumber());
            assertNull(row.getAchRoutingNumber());
            assertNull(row.getBgDriverLicense());
            assertNull(row.getBgStateId());
            assertNull(row.getBgDateOfBirth());
        }
        verify(entityManager, atLeastOnce()).detach(mine);

        assertTrue(service.approvalBoard(OTHER_ERM).stream().noneMatch(r -> r.get("application") == mine));
        assertThrows(AccessDeniedException.class, () -> service.approvalBoard(COACH));
        assertThrows(AccessDeniedException.class, () -> service.approvalBoard(MGR_ONE));
    }

    @Test
    void theBoardReadsEveryAgreementsGatesInOneQuery() {
        List<WebAgreement> sent = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            WebAgreement a = verified(ERM);
            send(a, MGR_ONE, null, null);
            sent.add(a);
        }
        clearInvocations(gateRepo);

        List<Map<String, Object>> board = service.approvalBoard(SYS);

        assertEquals(5, board.size());
        for (Map<String, Object> row : board) {
            WebAgreement a = (WebAgreement) row.get("application");
            assertEquals(gatesOf(a), row.get("approvals"), "every gate, oldest first");
        }
        verify(gateRepo, times(1)).findByAgreementIdIn(any());
        verify(gateRepo, never()).findByAgreementIdOrderByCreatedAtAsc(anyLong());
    }

    @Test
    void theListSummaryReadsTheLatestRoundAndTheFirstSend() {
        WebAgreement a = verified(ERM);
        send(a, MGR_ONE, null, null);
        LocalDateTime firstSent = eventsOf(a, WebAgreementEvent.EventType.SENT_FOR_APPROVAL).get(0).getCreatedAt();
        gatesOf(a).get(0).setStatus("REVISION_REQUESTED");
        a.setStatus("APPROVAL_REVISION_REQUESTED");
        send(a, MGR_TWO, null, null);
        gatesOf(a).get(1).setStatus("APPROVED");

        WebAgreement never = verified(ERM);
        WebAgreementApprovalSummary summary = new WebAgreementApprovalSummary(gateRepo, eventRepo);
        summary.populate(List.of(a, never));

        assertEquals("APPROVED", a.getManagerStatus(), "round 2 wins over round 1's decline");
        assertNull(a.getAccountsStatus(), "Phase 1 has no Accounts gate");
        assertEquals(firstSent.toString(), a.getSentForApprovalAt(), "the first send, not the re-send");
        assertNull(never.getManagerStatus());
        assertNull(never.getSentForApprovalAt());
        assertEquals(gatesOf(a), service.listApprovals(a.getId()), "oldest first");
    }

    @Test
    void requiredRolesPerPhase() {
        assertEquals(List.of("MANAGER"), WebAgreementApprovalService.requiredRoles(1));
        assertEquals(List.of("MANAGER", "ACCOUNTS"), WebAgreementApprovalService.requiredRoles(2));
        assertEquals(List.of("MANAGER"), WebAgreementApprovalService.requiredRoles(0));
    }
}
