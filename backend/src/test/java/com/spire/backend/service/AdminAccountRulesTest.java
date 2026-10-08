package com.spire.backend.service;

import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreementStaffTitle;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The account rules the approval chain adds to Admin → Users, copied from
 * the console's user management (AgreementAdminController): a role change
 * purges the user's team links and routed gates and ends the sessions of
 * the console's roles; deactivate never purges; only a System Admin manages
 * MANAGER / ACCOUNTS accounts (legacy ADMIN keeps ERMs, L-A7); a user who
 * owns agreements can't be deleted; and the Details card's checks.
 */
class AdminAccountRulesTest {

    private static final long SYS = 1L, OTHER_SYS = 2L, OPS = 3L, LEGACY_ADMIN = 4L, ERM = 10L, MGR = 11L, ACC = 12L,
            STUDENT = 13L, PARTICIPANT = 14L, COACH = 15L;

    private final Map<Long, User> users = new HashMap<>();
    private final Map<Long, WebAgreementStaffTitle> titles = new HashMap<>();
    private UserRepository userRepo;
    private WebAgreementRepository agreementRepo;
    private WebAgreementAssignmentService teams;
    private WebAgreementStaffTitleService titleService;
    private AdminService admin;

    private void user(long id, String role, String name) {
        users.put(id, User.builder().id(id).fullName(name).email(name.replace(' ', '.').toLowerCase() + "@sageitco.com")
                .role(Role.builder().name(role).build()).isActive(true).build());
    }

    @BeforeEach
    void setUp() {
        user(SYS, "SYSTEM_ADMIN", "Sys Admin");
        user(OTHER_SYS, "SYSTEM_ADMIN", "Second Admin");
        user(OPS, "OPERATIONS_ADMIN", "Ops Admin");
        user(LEGACY_ADMIN, "ADMIN", "Legacy Admin");
        user(ERM, "ERM", "Erin Manager");
        user(MGR, "MANAGER", "Mona Gate");
        user(ACC, "ACCOUNTS", "Ash Counts");
        user(STUDENT, "STUDENT", "Stu Dent");
        user(PARTICIPANT, "PARTICIPANT", "Pat Participant");
        user(COACH, "COACH", "Coach Carl");

        userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(users.get((Long) inv.getArgument(0))));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepo.existsByEmailIgnoreCase(anyString())).thenAnswer(inv -> users.values().stream()
                .anyMatch(u -> u.getEmail().equalsIgnoreCase(inv.getArgument(0))));
        RoleRepository roles = mock(RoleRepository.class);
        when(roles.findByName(anyString())).thenAnswer(inv -> Optional.of(Role.builder().name(inv.getArgument(0)).build()));

        WebAgreementStaffTitleRepository titleRepo = mock(WebAgreementStaffTitleRepository.class);
        when(titleRepo.findByUserId(anyLong())).thenAnswer(inv -> Optional.ofNullable(titles.get((Long) inv.getArgument(0))));
        when(titleRepo.save(any(WebAgreementStaffTitle.class))).thenAnswer(inv -> {
            WebAgreementStaffTitle t = inv.getArgument(0);
            titles.put(t.getUserId(), t);
            return t;
        });
        titleService = new WebAgreementStaffTitleService(titleRepo);
        agreementRepo = mock(WebAgreementRepository.class);
        teams = mock(WebAgreementAssignmentService.class);

        admin = new AdminService(userRepo, roles, mock(CourseRepository.class), mock(EnrollmentRepository.class),
                mock(LessonRepository.class), mock(ProgressRepository.class), mock(CertificateRepository.class),
                mock(SessionRequestRepository.class), mock(MentorAssignmentRepository.class), mock(RecordService.class),
                mock(AdminRevenueService.class), mock(PaymentLedgerRepository.class), agreementRepo, teams, titleService);
    }

    private User u(long id) {
        return users.get(id);
    }

    // ── Role change ─────────────────────────────────────────────────

    @Test
    void aRoleChangePurgesAndEndsSessionsForTheConsoleRoles() {
        admin.updateUserRole(MGR, "ERM", SYS);
        verify(teams).purgeUserLinks(MGR, SYS);
        assertNotNull(u(MGR).getSessionsValidAfter(), "MANAGER → ERM ends sessions");
        assertEquals("ERM", u(MGR).getRole().getName());

        admin.updateUserRole(ERM, "COACH", SYS);
        verify(teams).purgeUserLinks(ERM, SYS);
        assertNotNull(u(ERM).getSessionsValidAfter(), "from ERM");

        admin.updateUserRole(PARTICIPANT, "ACCOUNTS", SYS);
        assertNotNull(u(PARTICIPANT).getSessionsValidAfter(), "to ACCOUNTS");

        admin.updateUserRole(OPS, "SYSTEM_ADMIN", SYS);
        assertNotNull(u(OPS).getSessionsValidAfter(), "OPERATIONS_ADMIN → SYSTEM_ADMIN");
    }

    @Test
    void theSameRoleChangesNothing() {
        admin.updateUserRole(MGR, "manager", SYS);
        verify(teams, never()).purgeUserLinks(anyLong(), anyLong());
        assertNull(u(MGR).getSessionsValidAfter());
    }

    @Test
    void otherWebsiteRoleChangesKeepTheirSessions() {
        admin.updateUserRole(STUDENT, "PARTICIPANT", SYS);
        assertNull(u(STUDENT).getSessionsValidAfter(), "STUDENT → PARTICIPANT does not end sessions");
        verify(teams).purgeUserLinks(STUDENT, SYS);   // the role changed (no links: nothing to do)

        admin.updateUserRole(COACH, "TECHNICAL_ADVISOR", SYS);
        assertNull(u(COACH).getSessionsValidAfter());
    }

    @Test
    void onlyASystemAdminGivesOrRemovesTheApproverRoles() {
        assertThrows(AccessDeniedException.class, () -> admin.updateUserRole(PARTICIPANT, "MANAGER", LEGACY_ADMIN));
        assertThrows(AccessDeniedException.class, () -> admin.updateUserRole(MGR, "PARTICIPANT", LEGACY_ADMIN));
        assertThrows(AccessDeniedException.class, () -> admin.updateUserRole(ACC, "STUDENT", LEGACY_ADMIN));
        assertThrows(AccessDeniedException.class, () -> admin.updateUserRole(PARTICIPANT, "ACCOUNTS", OPS));
        verify(teams, never()).purgeUserLinks(anyLong(), anyLong());
        assertEquals("MANAGER", u(MGR).getRole().getName());
        assertNull(u(MGR).getSessionsValidAfter());
        assertThrows(IllegalArgumentException.class, () -> admin.updateUserRole(SYS, "MANAGER", SYS), "not your own");
    }

    // ── Deactivate, reactivate, delete ──────────────────────────────

    @Test
    void deactivatingAnApproverDoesNotPurge() {
        admin.updateUserStatus(MGR, SYS, false);
        assertFalse(u(MGR).getIsActive());
        assertNotNull(u(MGR).getSessionsValidAfter(), "deactivation still ends sessions");
        admin.updateUserStatus(MGR, SYS, true);
        assertTrue(u(MGR).getIsActive());
        verify(teams, never()).purgeUserLinks(anyLong(), anyLong());
    }

    @Test
    void legacyAdminCannotManageApproversButStillManagesErms() {
        for (long target : new long[]{MGR, ACC}) {
            AccessDeniedException off = assertThrows(AccessDeniedException.class,
                    () -> admin.updateUserStatus(target, LEGACY_ADMIN, false));
            assertEquals("Only a System Admin can change staff accounts.", off.getMessage());
            assertThrows(AccessDeniedException.class, () -> admin.updateUserStatus(target, LEGACY_ADMIN, true));
            assertThrows(AccessDeniedException.class, () -> admin.softDeleteUser(target, LEGACY_ADMIN));
            assertThrows(AccessDeniedException.class, () -> admin.updateUserStatus(target, OPS, false));
            assertThrows(AccessDeniedException.class, () -> admin.softDeleteUser(target, OPS));
            assertTrue(u(target).getIsActive());
        }
        verify(teams, never()).purgeUserLinks(anyLong(), anyLong());

        // L-A7: the website's accepted rule for ERMs stays.
        assertFalse(admin.updateUserStatus(ERM, LEGACY_ADMIN, false).getIsActive());
        assertTrue(admin.updateUserStatus(ERM, LEGACY_ADMIN, true).getIsActive());
        admin.softDeleteUser(ERM, LEGACY_ADMIN);
        assertEquals("deleted_" + ERM + "@removed.com", u(ERM).getEmail());

        // A System Admin manages them all.
        assertFalse(admin.updateUserStatus(ACC, SYS, false).getIsActive());
        admin.softDeleteUser(MGR, SYS);
        assertFalse(u(MGR).getIsActive());
    }

    @Test
    void aUserWhoOwnsAgreementsCannotBeDeleted() {
        when(agreementRepo.countByOwnerUserIdAndDeletedFalse(ERM)).thenReturn(2L);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> admin.softDeleteUser(ERM, SYS));
        assertEquals("This user owns 2 agreement(s). Disable the user instead of deleting — deleting would hide "
                + "those agreements.", e.getMessage());
        assertEquals("erin.manager@sageitco.com", u(ERM).getEmail(), "nothing scrubbed");
        assertTrue(u(ERM).getIsActive());
        verify(teams, never()).purgeUserLinks(anyLong(), anyLong());
        verify(userRepo, never()).save(any(User.class));

        // Disabling them is still allowed.
        assertFalse(admin.updateUserStatus(ERM, SYS, false).getIsActive());
    }

    @Test
    void deletingPurgesTheLinksBeforeTheScrub() {
        when(agreementRepo.countByOwnerUserIdAndDeletedFalse(ACC)).thenReturn(0L);
        admin.softDeleteUser(ACC, SYS);
        InOrder order = inOrder(teams, userRepo);
        order.verify(teams).purgeUserLinks(ACC, SYS);
        order.verify(userRepo).save(u(ACC));
        assertEquals("deleted_" + ACC + "@removed.com", u(ACC).getEmail());
        assertFalse(u(ACC).getIsActive());
    }

    // ── Details card ────────────────────────────────────────────────

    @Test
    void detailsAreForASystemAdminOnly() {
        for (long caller : new long[]{OPS, LEGACY_ADMIN, ERM, MGR}) {
            AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> admin.getStaffDetails(ERM, caller));
            assertEquals("Only a System Admin can do this.", e.getMessage());
            assertThrows(AccessDeniedException.class,
                    () -> admin.updateStaffDetails(ERM, "New Name", "New Title", null, caller));
        }
        u(OTHER_SYS).setIsActive(false);
        assertThrows(AccessDeniedException.class, () -> admin.getStaffDetails(ERM, OTHER_SYS), "deactivated");
        assertEquals("Erin Manager", u(ERM).getFullName());
        assertTrue(titles.isEmpty());
    }

    @Test
    void detailsSaveNameTitleAndEmail() {
        assertEquals(Map.of("fullName", "Erin Manager", "email", "erin.manager@sageitco.com", "title", ""),
                admin.getStaffDetails(ERM, SYS), "no title yet");

        Map<String, String> saved = admin.updateStaffDetails(ERM, "  Erin Q Manager ", " Engagement Manager ",
                " Erin.Q@SageITco.com ", SYS);
        assertEquals("Erin Q Manager", u(ERM).getFullName(), "trimmed");
        assertEquals("erin.q@sageitco.com", u(ERM).getEmail(), "trimmed and lower-cased");
        assertEquals("Engagement Manager", titleService.titleOf(ERM).orElseThrow(), "in the titles table");
        assertEquals(SYS, titles.get(ERM).getUpdatedBy());
        assertEquals(Map.of("fullName", "Erin Q Manager", "email", "erin.q@sageitco.com", "title", "Engagement Manager"), saved);
        assertNull(u(ERM).getSessionsValidAfter(), "sessions already issued stay valid");

        // Blank or unchanged email: no change (the user's own, any case).
        admin.updateStaffDetails(ERM, "Erin Q Manager", "Lead", "", SYS);
        admin.updateStaffDetails(ERM, "Erin Q Manager", "Lead", null, SYS);
        admin.updateStaffDetails(ERM, "Erin Q Manager", "Lead", "ERIN.Q@sageitco.com", SYS);
        assertEquals("erin.q@sageitco.com", u(ERM).getEmail());
        assertEquals("Lead", titleService.titleOf(ERM).orElseThrow());
        assertEquals(1, titles.size(), "one row per user");

        for (long target : new long[]{MGR, ACC, OPS}) {
            admin.updateStaffDetails(target, "Some Name", "Some Title", null, SYS);
            assertEquals("Some Title", titleService.titleOf(target).orElseThrow());
        }

        // Like the console, the name is only checked for blank and length.
        admin.updateStaffDetails(MGR, "M", "Some Title", null, SYS);
        assertEquals("M", u(MGR).getFullName());
    }

    @Test
    void detailsCheckEveryField() {
        assertEquals("Name is required.", assertThrows(IllegalArgumentException.class,
                () -> admin.updateStaffDetails(ERM, "   ", "Title", null, SYS)).getMessage());
        assertEquals("Name is required.", assertThrows(IllegalArgumentException.class,
                () -> admin.updateStaffDetails(ERM, null, "Title", null, SYS)).getMessage());
        assertEquals("Title is required.", assertThrows(IllegalArgumentException.class,
                () -> admin.updateStaffDetails(ERM, "Erin Manager", " ", null, SYS)).getMessage());
        assertEquals("Name is too long (max 100 characters).", assertThrows(IllegalArgumentException.class,
                () -> admin.updateStaffDetails(ERM, "E".repeat(101), "Title", null, SYS)).getMessage());
        assertEquals("Title is too long (max 255 characters).", assertThrows(IllegalArgumentException.class,
                () -> admin.updateStaffDetails(ERM, "Erin Manager", "T".repeat(256), null, SYS)).getMessage());
        for (String bad : new String[]{"not-an-email", "a@b.c", "a b@sageitco.com", "a@@b.com", "x".repeat(250) + "@b.com"}) {
            assertEquals("Enter a valid email address.", assertThrows(IllegalArgumentException.class,
                    () -> admin.updateStaffDetails(ERM, "Erin Manager", "Title", bad, SYS)).getMessage(), bad);
        }
        IllegalStateException taken = assertThrows(IllegalStateException.class,
                () -> admin.updateStaffDetails(ERM, "Erin Manager", "Title", "Mona.Gate@sageitco.com", SYS));
        assertEquals("Email already in use.", taken.getMessage());
        assertThrows(IllegalStateException.class,
                () -> admin.updateStaffDetails(OTHER_SYS, "Second Admin", "Title", "new.admin@sageitco.com", SYS),
                "a System Admin's login email can't be changed");
        assertEquals("erin.manager@sageitco.com", u(ERM).getEmail());
        assertEquals("second.admin@sageitco.com", u(OTHER_SYS).getEmail());
        assertTrue(titles.isEmpty(), "a refused save stores nothing");

        // A System Admin's name and title can still be edited.
        admin.updateStaffDetails(OTHER_SYS, "Second Admin", "Administrator", "second.admin@sageitco.com", SYS);
        assertEquals("Administrator", titleService.titleOf(OTHER_SYS).orElseThrow());
    }

    @Test
    void detailsAreForTheConsoleRolesOnly() {
        assertThrows(ResourceNotFoundException.class, () -> admin.getStaffDetails(999L, SYS));
        assertThrows(ResourceNotFoundException.class, () -> admin.updateStaffDetails(999L, "A B", "T", null, SYS));
        for (long target : new long[]{PARTICIPANT, STUDENT, COACH, LEGACY_ADMIN}) {
            assertThrows(IllegalArgumentException.class, () -> admin.getStaffDetails(target, SYS));
            assertThrows(IllegalArgumentException.class, () -> admin.updateStaffDetails(target, "A B", "T", null, SYS));
        }
        assertTrue(titles.isEmpty());
    }
}
