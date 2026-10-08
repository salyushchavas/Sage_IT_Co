package com.spire.backend.service;

import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementApproval;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementApprovalRepository;
import com.spire.backend.repository.WebAgreementEventRepository;
import com.spire.backend.repository.WebAgreementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The shared helpers of the approval chain: who may act (staff, owner or
 * admin, System Admin, approver; deactivated accounts never), owner names
 * in one query, and the list's approval summary (latest round per gate,
 * earliest send).
 */
class WebAgreementAccessTest {

    private static final long ERM = 20L;
    private static final long OTHER_ERM = 21L;
    private static final long OPS = 30L;
    private static final long SYS = 31L;
    private static final long LEGACY_ADMIN = 32L;
    private static final long COACH = 40L;
    private static final long MGR = 50L;
    private static final long ACC = 51L;
    private static final long OFF_MGR = 52L;
    private static final long OFF_ERM = 53L;

    private final List<User> users = new ArrayList<>();
    private WebAgreementRepository agreementRepo;
    private UserRepository userRepo;
    private WebAgreementAccess access;

    @BeforeEach
    void setUp() {
        agreementRepo = mock(WebAgreementRepository.class);
        userRepo = mock(UserRepository.class);
        user(ERM, "ERM", "Erin Manager", true);
        user(OTHER_ERM, "ERM", "Omar Other", true);
        user(OPS, "OPERATIONS_ADMIN", "Ops Admin", true);
        user(SYS, "SYSTEM_ADMIN", "Sys Admin", true);
        user(LEGACY_ADMIN, "ADMIN", "Legacy Admin", true);
        user(COACH, "COACH", "Coach", true);
        user(MGR, "MANAGER", "Mona Gate", true);
        user(ACC, "ACCOUNTS", "Ash Counts", true);
        user(OFF_MGR, "MANAGER", "Off Manager", false);
        user(OFF_ERM, "ERM", "Off Erm", false);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> users.stream()
                .filter(u -> u.getId().equals(inv.getArgument(0))).findFirst());
        when(userRepo.findAllById(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return users.stream().filter(u -> ids.contains(u.getId())).toList();
        });
        access = new WebAgreementAccess(agreementRepo, userRepo);
    }

    private void user(long id, String role, String name, boolean active) {
        users.add(User.builder().id(id).fullName(name).email(name.replace(' ', '.') + "@sageitco.com")
                .role(Role.builder().name(role).build()).isActive(active).build());
    }

    private WebAgreement agreement(String appId, long owner, boolean deleted) {
        WebAgreement a = WebAgreement.builder().id((long) appId.hashCode()).applicationId(appId)
                .ownerUserId(owner).participantUserId(10L).consultantEmail("p@x.com")
                .status(WebAgreement.Status.VERIFIED.name()).deleted(deleted).build();
        when(agreementRepo.findByApplicationId(appId)).thenReturn(Optional.of(a));
        return a;
    }

    // ── Who may act ──────────────────────────────────────────────────

    @Test
    void staffAreErmsAndAdminsOnly() {
        assertEquals(ERM, access.requireStaff(ERM).getId());
        assertEquals(OPS, access.requireStaff(OPS).getId());
        assertEquals(SYS, access.requireStaff(SYS).getId());
        for (Long id : new Long[]{COACH, MGR, ACC, LEGACY_ADMIN, OFF_ERM, 999L, null}) {
            AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> access.requireStaff(id));
            assertEquals("Only an ERM or an Operations admin can work on agreements.", e.getMessage());
        }
    }

    @Test
    void ownerAndAdminsReachAnAgreementOthersGetNotFound() {
        WebAgreement a = agreement("app-1", ERM, false);
        assertSame(a, access.requireAccess("app-1", ERM));
        assertSame(a, access.requireAccess("app-1", OPS));
        assertSame(a, access.requireAccess("app-1", SYS));
        assertThrows(ResourceNotFoundException.class, () -> access.requireAccess("app-1", OTHER_ERM));
        assertThrows(AccessDeniedException.class, () -> access.requireAccess("app-1", MGR));
        when(agreementRepo.findByApplicationId("missing")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> access.requireAccess("missing", SYS));
    }

    @Test
    void archivedAgreementIsNotFoundEvenForAdmins() {
        agreement("gone", ERM, true);
        assertThrows(ResourceNotFoundException.class, () -> access.requireAccess("gone", ERM));
        assertThrows(ResourceNotFoundException.class, () -> access.requireAccess("gone", SYS));
    }

    @Test
    void systemAdminOnlyRefusesOperationsAndLegacyAdmin() {
        assertEquals(SYS, access.requireSystemAdmin(SYS).getId());
        for (Long id : new Long[]{OPS, LEGACY_ADMIN, ERM, MGR, 999L, null}) {
            AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> access.requireSystemAdmin(id));
            assertEquals("Only a System Admin can do this.", e.getMessage());
        }
        users.stream().filter(u -> u.getId() == SYS).findFirst().orElseThrow().setIsActive(false);
        assertThrows(AccessDeniedException.class, () -> access.requireSystemAdmin(SYS));
    }

    @Test
    void approversAreManagerAccountsAndSystemAdmin() {
        assertEquals(MGR, access.requireApprover(MGR).getId());
        assertEquals(ACC, access.requireApprover(ACC).getId());
        assertEquals(SYS, access.requireApprover(SYS).getId());
        for (Long id : new Long[]{ERM, OPS, LEGACY_ADMIN, COACH, OFF_MGR, 999L, null}) {
            AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> access.requireApprover(id));
            assertEquals("Approver role required.", e.getMessage());
        }
    }

    @Test
    void roleHelpers() {
        assertTrue(WebAgreementAccess.isAdmin(users.get(2)));
        assertTrue(WebAgreementAccess.isAdmin(users.get(3)));
        assertFalse(WebAgreementAccess.isAdmin(users.get(0)));
        assertFalse(WebAgreementAccess.isAdmin(users.get(4)));
        assertEquals("", WebAgreementAccess.roleOf(null));
        assertEquals("", WebAgreementAccess.roleOf(User.builder().id(1L).build()));
        User lower = User.builder().id(2L).role(Role.builder().name("manager").build()).build();
        assertEquals("MANAGER", WebAgreementAccess.roleOf(lower));
    }

    @Test
    void ownerNamesInOneQuery() {
        WebAgreement a = WebAgreement.builder().id(1L).ownerUserId(ERM).build();
        WebAgreement b = WebAgreement.builder().id(2L).ownerUserId(OPS).build();
        WebAgreement c = WebAgreement.builder().id(3L).ownerUserId(ERM).build();
        access.populateOwnerNames(List.of(a, b, c));
        assertEquals("Erin Manager", a.getOwnerName());
        assertEquals("Ops Admin", b.getOwnerName());
        assertEquals("Erin Manager", c.getOwnerName());
        verify(userRepo, times(1)).findAllById(any());

        access.populateOwnerNames(List.of());
        access.populateOwnerNames(null);
        verify(userRepo, times(1)).findAllById(any());
    }

    // ── Approval summary on list rows ────────────────────────────────

    private static WebAgreementApproval gate(long agreementId, String role, int round, String status) {
        return WebAgreementApproval.builder().agreementId(agreementId).role(role)
                .round(round).status(status).build();
    }

    private static WebAgreementEvent event(long agreementId, String type, LocalDateTime at) {
        return WebAgreementEvent.builder().agreementId(agreementId).eventType(type)
                .actorType("ERM").createdAt(at).build();
    }

    @Test
    void summaryUsesTheLatestRoundPerGateAndTheEarliestSend() {
        WebAgreementApprovalRepository approvalRepo = mock(WebAgreementApprovalRepository.class);
        WebAgreementEventRepository eventRepo = mock(WebAgreementEventRepository.class);
        WebAgreementApprovalSummary summary = new WebAgreementApprovalSummary(approvalRepo, eventRepo);

        // 1: Phase 1, declined in round 1, approved in round 2 → APPROVED, no Accounts gate.
        // 2: Phase 2 (round 3) after Phase 1 (round 1): Accounts declined, Manager left PENDING.
        // 3: never sent.
        when(approvalRepo.findByAgreementIdIn(any())).thenReturn(List.of(
                gate(1L, "MANAGER", 2, "APPROVED"),
                gate(1L, "MANAGER", 1, "REVISION_REQUESTED"),
                gate(2L, "MANAGER", 1, "APPROVED"),
                gate(2L, "MANAGER", 3, "PENDING"),
                gate(2L, "ACCOUNTS", 3, "REVISION_REQUESTED")));
        LocalDateTime first = LocalDateTime.of(2026, 9, 1, 10, 0, 5);
        when(eventRepo.findByAgreementIdInAndEventType(any(), eq("SENT_FOR_APPROVAL"))).thenReturn(List.of(
                event(1L, "SENT_FOR_APPROVAL", first.plusDays(2)),
                event(1L, "SENT_FOR_APPROVAL", first),
                event(2L, "SENT_FOR_APPROVAL", first.plusDays(5)),
                event(2L, "SENT_FOR_APPROVAL", null)));

        WebAgreement a1 = WebAgreement.builder().id(1L).build();
        WebAgreement a2 = WebAgreement.builder().id(2L).build();
        WebAgreement a3 = WebAgreement.builder().id(3L).build();
        WebAgreement unsaved = WebAgreement.builder().build();
        summary.populate(List.of(a1, a2, a3, unsaved));

        assertEquals("APPROVED", a1.getManagerStatus());
        assertNull(a1.getAccountsStatus());
        assertEquals(first.toString(), a1.getSentForApprovalAt());

        assertEquals("PENDING", a2.getManagerStatus());
        assertEquals("REVISION_REQUESTED", a2.getAccountsStatus());
        assertEquals(first.plusDays(5).toString(), a2.getSentForApprovalAt());

        assertNull(a3.getManagerStatus());
        assertNull(a3.getAccountsStatus());
        assertNull(a3.getSentForApprovalAt());
        assertNull(unsaved.getManagerStatus());

        // Two queries for the whole page, only for saved rows.
        verify(approvalRepo, times(1)).findByAgreementIdIn(argThat(ids -> ids.size() == 3));
        verify(eventRepo, times(1)).findByAgreementIdInAndEventType(any(), any());
    }

    @Test
    void summaryOfAnEmptyPageRunsNoQuery() {
        WebAgreementApprovalRepository approvalRepo = mock(WebAgreementApprovalRepository.class);
        WebAgreementEventRepository eventRepo = mock(WebAgreementEventRepository.class);
        WebAgreementApprovalSummary summary = new WebAgreementApprovalSummary(approvalRepo, eventRepo);
        summary.populate(List.of());
        summary.populate(null);
        summary.populate(List.of(WebAgreement.builder().build()));
        verifyNoInteractions(approvalRepo, eventRepo);
    }
}
