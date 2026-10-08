package com.spire.backend.service;

import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreementApproval;
import com.spire.backend.entity.WebAgreementErmAssignment;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementApprovalRepository;
import com.spire.backend.repository.WebAgreementErmAssignmentRepository;
import com.spire.backend.repository.WebAgreementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Approver teams per ERM (the console's AgreementAssignmentService): only
 * ERMs have a team; every approver must exist, hold the role and be active
 * (console messages); a replace reaches the console's end state without
 * deleting a row; the pickers see active, still-in-role approvers only; and
 * a purge marks links removed both ways and un-routes PENDING gates,
 * keeping the old routing in the history columns.
 */
class WebAgreementAssignmentServiceTest {

    private static final long ERM = 20L, OTHER_ERM = 21L, OPS = 30L, SYS = 31L, LEGACY_ADMIN = 32L,
            MGR_ZED = 50L, MGR_AMY = 51L, MGR_BO = 52L, ACC = 60L, ACC_TWO = 61L, OFF_MGR = 70L;

    private final List<User> users = new ArrayList<>();
    /** web_agreement_erm_assignments. */
    private final List<WebAgreementErmAssignment> links = new ArrayList<>();
    /** web_agreement_approvals. */
    private final List<WebAgreementApproval> gates = new ArrayList<>();
    private long nextLinkId = 1;

    private WebAgreementErmAssignmentRepository linkRepo;
    private WebAgreementApprovalRepository gateRepo;
    private WebAgreementAssignmentService service;

    @BeforeEach
    void setUp() {
        user(ERM, "ERM", "Erin Manager", true);
        user(OTHER_ERM, "ERM", "Omar Other", true);
        user(OPS, "OPERATIONS_ADMIN", "Ops Admin", true);
        user(SYS, "SYSTEM_ADMIN", "Sys Admin", true);
        user(LEGACY_ADMIN, "ADMIN", "Legacy Admin", true);
        user(MGR_ZED, "MANAGER", "zed Gate", true);
        user(MGR_AMY, "MANAGER", "Amy Gate", true);
        user(MGR_BO, "MANAGER", "Bo Gate", true);
        user(ACC, "ACCOUNTS", "Ash Counts", true);
        user(ACC_TWO, "ACCOUNTS", "Cy Counts", true);
        user(OFF_MGR, "MANAGER", "Off Manager", false);

        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> users.stream()
                .filter(u -> u.getId().equals(inv.getArgument(0))).findFirst());

        linkRepo = mock(WebAgreementErmAssignmentRepository.class);
        when(linkRepo.save(any(WebAgreementErmAssignment.class))).thenAnswer(inv -> {
            WebAgreementErmAssignment a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(nextLinkId++);
                a.setCreatedAt(LocalDateTime.now());
                links.add(a);
            }
            return a;
        });
        when(linkRepo.findByErmUserIdAndRoleAndRemovedAtIsNull(anyLong(), anyString())).thenAnswer(inv -> links.stream()
                .filter(a -> a.getErmUserId().equals(inv.getArgument(0)) && a.getRole().equals(inv.getArgument(1))
                        && a.getRemovedAt() == null).toList());
        when(linkRepo.findByErmUserIdAndApproverUserIdAndRole(anyLong(), anyLong(), anyString())).thenAnswer(inv -> links.stream()
                .filter(a -> a.getErmUserId().equals(inv.getArgument(0)) && a.getApproverUserId().equals(inv.getArgument(1))
                        && a.getRole().equals(inv.getArgument(2))).findFirst());
        when(linkRepo.findByErmUserIdAndRemovedAtIsNull(anyLong())).thenAnswer(inv -> links.stream()
                .filter(a -> a.getErmUserId().equals(inv.getArgument(0)) && a.getRemovedAt() == null).toList());
        when(linkRepo.findByApproverUserIdAndRemovedAtIsNull(anyLong())).thenAnswer(inv -> links.stream()
                .filter(a -> a.getApproverUserId().equals(inv.getArgument(0)) && a.getRemovedAt() == null).toList());

        gateRepo = mock(WebAgreementApprovalRepository.class);
        when(gateRepo.save(any(WebAgreementApproval.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gateRepo.findByApproverUserIdAndStatus(anyLong(), anyString())).thenAnswer(inv -> gates.stream()
                .filter(g -> Objects.equals(g.getApproverUserId(), inv.getArgument(0))
                        && g.getStatus().equals(inv.getArgument(1))).toList());

        WebAgreementAccess access = new WebAgreementAccess(mock(WebAgreementRepository.class), userRepo);
        service = new WebAgreementAssignmentService(linkRepo, gateRepo, userRepo, access);
    }

    private User user(long id, String role, String name, boolean active) {
        User u = User.builder().id(id).fullName(name).email(name.replace(' ', '.').toLowerCase() + "@sageitco.com")
                .role(Role.builder().name(role).build()).isActive(active).build();
        users.add(u);
        return u;
    }

    private User find(long id) {
        return users.stream().filter(u -> u.getId() == id).findFirst().orElseThrow();
    }

    private WebAgreementErmAssignment link(long erm, long approver, String role) {
        return links.stream().filter(a -> a.getErmUserId() == erm && a.getApproverUserId() == approver
                && a.getRole().equals(role)).findFirst().orElseThrow();
    }

    private long activeLinks() {
        return links.stream().filter(a -> a.getRemovedAt() == null).count();
    }

    /** Rule 4: the team table is never deleted from. */
    private void assertNothingDeleted() {
        for (Invocation i : mockingDetails(linkRepo).getInvocations()) {
            assertFalse(i.getMethod().getName().startsWith("delete"), "called " + i.getMethod().getName());
        }
        for (Invocation i : mockingDetails(gateRepo).getInvocations()) {
            assertFalse(i.getMethod().getName().startsWith("delete"), "called " + i.getMethod().getName());
        }
    }

    // ── Who has a team ──────────────────────────────────────────────

    @Test
    void onlyErmsHaveTeamsAndAnUnknownErmIsNotFound() {
        IllegalArgumentException notErm = assertThrows(IllegalArgumentException.class,
                () -> service.replaceAssignments(OPS, "MANAGER", List.of(MGR_AMY), SYS));
        assertEquals("Assignments can only be set for ERM users.", notErm.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.replaceAssignments(MGR_AMY, "MANAGER", List.of(), SYS));
        assertThrows(ResourceNotFoundException.class, () -> service.replaceAssignments(999L, "MANAGER", List.of(MGR_AMY), SYS));

        IllegalArgumentException view = assertThrows(IllegalArgumentException.class, () -> service.team(SYS, SYS));
        assertEquals("Assignments only apply to ERM users.", view.getMessage());
        assertThrows(ResourceNotFoundException.class, () -> service.team(999L, SYS));
        assertTrue(links.isEmpty());
    }

    @Test
    void everyApproverMustExistHoldTheRoleAndBeActive() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> service.replaceAssignments(ERM, "MANAGER", List.of(MGR_AMY, 999L), SYS));
        assertEquals("Unknown user in assignment: 999", unknown.getMessage());

        IllegalArgumentException wrongRole = assertThrows(IllegalArgumentException.class,
                () -> service.replaceAssignments(ERM, "MANAGER", List.of(ACC), SYS));
        assertEquals("User ash.counts@sageitco.com is not a MANAGER.", wrongRole.getMessage());
        IllegalArgumentException wrongRole2 = assertThrows(IllegalArgumentException.class,
                () -> service.replaceAssignments(ERM, "ACCOUNTS", List.of(MGR_AMY), SYS));
        assertEquals("User amy.gate@sageitco.com is not a ACCOUNTS.", wrongRole2.getMessage(), "console wording, as is");

        IllegalArgumentException inactive = assertThrows(IllegalArgumentException.class,
                () -> service.replaceAssignments(ERM, "MANAGER", List.of(OFF_MGR), SYS));
        assertEquals("User off.manager@sageitco.com is not active and cannot be assigned.", inactive.getMessage());

        assertTrue(links.isEmpty(), "a refused list changes nothing");
    }

    // ── Replace ─────────────────────────────────────────────────────

    @Test
    void replaceReachesTheConsoleEndStateWithoutDeletingAndIsIdempotent() {
        service.replaceAssignments(ERM, "MANAGER", List.of(MGR_ZED, MGR_AMY, MGR_AMY), SYS);
        assertEquals(2, links.size(), "duplicates are dropped");
        assertEquals(Set.of(MGR_ZED, MGR_AMY), service.assignedApproverIds(ERM, "MANAGER"));
        assertEquals(SYS, link(ERM, MGR_ZED, "MANAGER").getCreatedBy());

        // Same list again: nothing changes.
        service.replaceAssignments(ERM, "MANAGER", List.of(MGR_AMY, MGR_ZED), SYS);
        assertEquals(2, links.size());
        assertEquals(2, activeLinks());

        // zed goes, Bo comes: zed's row is marked removed, not deleted.
        service.replaceAssignments(ERM, "MANAGER", List.of(MGR_AMY, MGR_BO), SYS);
        WebAgreementErmAssignment zed = link(ERM, MGR_ZED, "MANAGER");
        assertNotNull(zed.getRemovedAt());
        assertEquals(SYS, zed.getRemovedBy());
        assertEquals(3, links.size());
        assertEquals(Set.of(MGR_AMY, MGR_BO), service.assignedApproverIds(ERM, "MANAGER"));

        // zed back: the same row is re-activated, no new row, no duplicate active link.
        long zedId = zed.getId();
        service.replaceAssignments(ERM, "MANAGER", List.of(MGR_ZED, MGR_AMY, MGR_BO), SYS);
        assertEquals(3, links.size());
        assertEquals(zedId, link(ERM, MGR_ZED, "MANAGER").getId());
        assertNull(zed.getRemovedAt());
        assertNull(zed.getRemovedBy());
        assertEquals(3, activeLinks());

        // An empty list removes every link of that role only.
        service.replaceAssignments(ERM, "ACCOUNTS", List.of(ACC), SYS);
        service.replaceAssignments(ERM, "MANAGER", null, SYS);
        assertTrue(service.assignedApproverIds(ERM, "MANAGER").isEmpty());
        assertEquals(Set.of(ACC), service.assignedApproverIds(ERM, "ACCOUNTS"));
        assertEquals(4, links.size(), "every row is kept");
        assertNothingDeleted();
    }

    @Test
    void teamsBelongToOneErmAndAnApproverCanServeSeveral() {
        service.replaceAssignments(ERM, "MANAGER", List.of(MGR_AMY), SYS);
        service.replaceAssignments(OTHER_ERM, "MANAGER", List.of(MGR_AMY, MGR_BO), SYS);
        service.replaceAssignments(ERM, "MANAGER", List.of(), SYS);
        assertTrue(service.assignedApprovers(ERM, "MANAGER").isEmpty());
        assertEquals(List.of(MGR_AMY, MGR_BO),
                service.assignedApprovers(OTHER_ERM, "MANAGER").stream().map(User::getId).toList(),
                "another ERM's team is untouched");
    }

    // ── What the pickers see ────────────────────────────────────────

    @Test
    void assignedApproversAreActiveStillInRoleAndSortedByName() {
        service.replaceAssignments(ERM, "MANAGER", List.of(MGR_ZED, MGR_AMY, MGR_BO), SYS);
        assertEquals(List.of(MGR_AMY, MGR_BO, MGR_ZED),
                service.assignedApprovers(ERM, "MANAGER").stream().map(User::getId).toList(),
                "by full name, ignoring case");

        find(MGR_BO).setIsActive(false);                                    // deactivated
        find(MGR_ZED).setRole(Role.builder().name("ACCOUNTS").build());     // re-roled
        assertEquals(List.of(MGR_AMY), service.assignedApprovers(ERM, "MANAGER").stream().map(User::getId).toList());
        assertEquals(Set.of(MGR_ZED, MGR_AMY, MGR_BO), service.assignedApproverIds(ERM, "MANAGER"),
                "the ids still tell 'no longer active' from 'never assigned'");

        // A removed link is gone from both.
        find(MGR_BO).setIsActive(true);
        service.replaceAssignments(ERM, "MANAGER", List.of(MGR_AMY), SYS);
        assertEquals(Set.of(MGR_AMY), service.assignedApproverIds(ERM, "MANAGER"));
        assertEquals(List.of(MGR_AMY), service.assignedApprovers(ERM, "MANAGER").stream().map(User::getId).toList());
        assertTrue(service.assignedApprovers(ERM, "ACCOUNTS").isEmpty(), "zed was never linked as ACCOUNTS");
        assertThrows(IllegalArgumentException.class, () -> service.assignedApprovers(ERM, "ERM"));
    }

    // ── Purge ───────────────────────────────────────────────────────

    @Test
    void purgeMarksLinksRemovedBothWaysAndUnroutesOnlyPendingGates() {
        service.replaceAssignments(ERM, "MANAGER", List.of(MGR_AMY, MGR_BO), SYS);
        service.replaceAssignments(OTHER_ERM, "MANAGER", List.of(MGR_AMY), SYS);
        service.replaceAssignments(ERM, "ACCOUNTS", List.of(ACC), SYS);
        WebAgreementApproval pending = gate(1L, MGR_AMY, "Amy Gate", "PENDING");
        WebAgreementApproval approved = gate(2L, MGR_AMY, "Amy Gate", "APPROVED");
        WebAgreementApproval declined = gate(3L, MGR_AMY, "Amy Gate", "REVISION_REQUESTED");
        WebAgreementApproval someoneElse = gate(4L, MGR_BO, "Bo Gate", "PENDING");

        // Amy (an approver) is re-roled: her links to both ERMs go.
        service.purgeUserLinks(MGR_AMY, SYS);
        assertNotNull(link(ERM, MGR_AMY, "MANAGER").getRemovedAt());
        assertNotNull(link(OTHER_ERM, MGR_AMY, "MANAGER").getRemovedAt());
        assertEquals(SYS, link(ERM, MGR_AMY, "MANAGER").getRemovedBy());
        assertNull(link(ERM, MGR_BO, "MANAGER").getRemovedAt(), "other approvers keep their links");

        assertNull(pending.getApproverUserId(), "role-wide again");
        assertNull(pending.getApproverName());
        assertEquals(MGR_AMY, pending.getUnroutedFromUserId());
        assertEquals("Amy Gate", pending.getUnroutedFromName());
        assertNotNull(pending.getUnroutedAt());
        assertEquals("PENDING", pending.getStatus());
        for (WebAgreementApproval decided : List.of(approved, declined)) {
            assertEquals(MGR_AMY, decided.getApproverUserId(), "decided gates keep their routing");
            assertNull(decided.getUnroutedFromUserId());
        }
        assertEquals(MGR_BO, someoneElse.getApproverUserId());

        // The ERM is re-roled: every link of their team goes, as the ERM side.
        service.purgeUserLinks(ERM, OPS);
        assertNotNull(link(ERM, MGR_BO, "MANAGER").getRemovedAt());
        assertEquals(OPS, link(ERM, ACC, "ACCOUNTS").getRemovedBy());
        assertEquals(0, activeLinks());
        assertEquals(4, links.size(), "every row is kept");
        assertNothingDeleted();

        // Idempotent.
        LocalDateTime first = pending.getUnroutedAt();
        service.purgeUserLinks(MGR_AMY, SYS);
        assertEquals(first, pending.getUnroutedAt());
        assertEquals(MGR_AMY, pending.getUnroutedFromUserId());
    }

    private WebAgreementApproval gate(long agreementId, long approver, String name, String status) {
        WebAgreementApproval g = WebAgreementApproval.builder().id(agreementId).agreementId(agreementId)
                .role("MANAGER").status(status).approverUserId(approver).approverName(name).build();
        gates.add(g);
        return g;
    }

    // ── The System Admin's endpoints ────────────────────────────────

    @Test
    void theTeamEndpointsAreForTheSystemAdminOnlyAndShowActiveApprovers() {
        for (long caller : new long[]{OPS, LEGACY_ADMIN, ERM, MGR_AMY}) {
            assertThrows(AccessDeniedException.class, () -> service.team(ERM, caller));
            assertThrows(AccessDeniedException.class,
                    () -> service.replaceTeam(ERM, List.of(MGR_AMY), List.of(ACC), caller));
        }
        assertTrue(links.isEmpty());

        WebAgreementAssignmentService.Team saved = service.replaceTeam(ERM, List.of(MGR_ZED, MGR_AMY), List.of(ACC, ACC_TWO), SYS);
        assertEquals(List.of(MGR_AMY, MGR_ZED), saved.managerIds());
        assertEquals(List.of(ACC, ACC_TWO), saved.accountsIds());

        find(ACC_TWO).setIsActive(false);
        WebAgreementAssignmentService.Team seen = service.team(ERM, SYS);
        assertEquals(List.of(MGR_AMY, MGR_ZED), seen.managerIds());
        assertEquals(List.of(ACC), seen.accountsIds(), "active only, so it matches the ERM's pickers");
    }

    @Test
    void theTeamSaveAndThePurgeRunInOneTransaction() throws Exception {
        assertNotNull(WebAgreementAssignmentService.class
                .getMethod("replaceTeam", Long.class, List.class, List.class, Long.class)
                .getAnnotation(Transactional.class));
        assertNotNull(WebAgreementAssignmentService.class
                .getMethod("purgeUserLinks", Long.class, Long.class)
                .getAnnotation(Transactional.class));
        // A bad Accounts id refuses the save after the Managers were applied in the
        // same transaction (rolled back by Spring; here: the refusal surfaces).
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceTeam(ERM, List.of(MGR_AMY), List.of(MGR_BO), SYS));
    }
}
