package com.spire.backend.service;

import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreementStaffTitle;
import com.spire.backend.repository.EmailLogRepository;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementStaffTitleRepository;
import com.spire.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Staff onboarding (25 Sep): a System Admin adds staff with a company login
 * email, a role and a personal email; a temporary password goes to the
 * personal email (and so does every later email); the person must choose
 * their own password at first sign-in. Participants are invited to enroll.
 *
 * The agreement approvers (MANAGER, ACCOUNTS) follow the console: no email,
 * the temporary password comes back once. ERM, MANAGER and ACCOUNTS need a
 * title, which goes to web_agreement_staff_titles.
 */
class StaffOnboardingTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final Map<Long, User> users = new HashMap<>();
    private UserRepository userRepo;
    private EmailTemplateService emails;
    private StaffOnboardingService service;
    /** web_agreement_staff_titles, by user id. */
    private final Map<Long, WebAgreementStaffTitle> titles = new HashMap<>();
    private WebAgreementStaffTitleService titleService;

    private User person(long id, String role) {
        User u = User.builder().id(id).fullName("Person " + id).email("p" + id + "@x.com")
                .role(Role.builder().name(role).build()).isActive(true).build();
        users.put(id, u);
        return u;
    }

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(users.get((Long) inv.getArgument(0))));
        when(userRepo.existsByEmailIgnoreCase(anyString())).thenAnswer(inv -> users.values().stream()
                .anyMatch(u -> u.getEmail().equalsIgnoreCase(inv.getArgument(0))));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId((long) (100 + users.size()));
            users.put(u.getId(), u);
            return u;
        });
        RoleRepository roles = mock(RoleRepository.class);
        when(roles.findByName(anyString())).thenAnswer(inv -> Optional.of(Role.builder().name(inv.getArgument(0)).build()));
        emails = mock(EmailTemplateService.class);
        when(emails.sendStaffLoginEmail(any(), anyString(), anyString(), anyBoolean())).thenReturn(true);
        WebAgreementStaffTitleRepository titleRepo = mock(WebAgreementStaffTitleRepository.class);
        when(titleRepo.findByUserId(anyLong())).thenAnswer(inv -> Optional.ofNullable(titles.get((Long) inv.getArgument(0))));
        when(titleRepo.save(any(WebAgreementStaffTitle.class))).thenAnswer(inv -> {
            WebAgreementStaffTitle t = inv.getArgument(0);
            titles.put(t.getUserId(), t);
            return t;
        });
        titleService = new WebAgreementStaffTitleService(titleRepo);
        service = new StaffOnboardingService(userRepo, roles, encoder, mock(RecordService.class), emails, titleService);
        person(1, "SYSTEM_ADMIN");
        person(2, "OPERATIONS_ADMIN");
        person(3, "ADMIN");
        person(4, "PARTICIPANT");
        person(5, "FINANCE");
    }

    @Test
    void aSystemAdminAddsAStaffMemberAndTheLoginDetailsGoToTheirOwnEmail() {
        StaffOnboardingService.Result r = service.createStaff(1L, "  Riya   Sharma ", "Riya.Sharma@SageITco.com",
                "riya.personal@gmail.com", "erm", " Engagement Manager ");
        User created = users.get(r.user().getId());
        assertEquals("riya.sharma@sageitco.com", created.getEmail(), "the login email, lower-cased");
        assertEquals("Riya Sharma", created.getFullName());
        assertEquals("ERM", created.getRole().getName());
        assertEquals("riya.personal@gmail.com", created.getPersonalEmail());
        assertTrue(created.getEmailVerified() && created.getMustChangePassword() && created.getIsActive());
        ArgumentCaptor<String> temp = ArgumentCaptor.forClass(String.class);
        verify(emails).sendStaffLoginEmail(eq(created), eq("ERM (relationship manager)"), temp.capture(), eq(false));
        assertTrue(temp.getValue().matches("[A-Za-z2-9]{4}(-[A-Za-z2-9]{4}){3}"), temp.getValue());
        assertTrue(encoder.matches(temp.getValue(), created.getPasswordHash()), "only the hash is stored");
        assertEquals("riya.personal@gmail.com", r.sentTo());
        assertNull(r.temporaryPassword(), "an ERM's password is emailed, never returned");
        assertEquals("Engagement Manager", titleService.titleOf(created.getId()).orElseThrow(), "trimmed, in the titles table");
        assertEquals(1L, titles.get(created.getId()).getUpdatedBy());
    }

    @Test
    void onlyASystemAdminAddsStaffAndOnlyStaffRoles() {
        assertThrows(AccessDeniedException.class, () -> service.createStaff(2L, "A B", "a@sageitco.com", null, "ERM", "Title"), "Operations");
        assertThrows(AccessDeniedException.class, () -> service.createStaff(3L, "A B", "a@sageitco.com", null, "ERM", "Title"), "old LMS admin");
        assertThrows(IllegalArgumentException.class, () -> service.createStaff(1L, "A B", "a@sageitco.com", null, "PARTICIPANT", "Title"));
        assertThrows(IllegalArgumentException.class, () -> service.createStaff(1L, "A B", "a@sageitco.com", null, "ADMIN", "Title"));
        assertThrows(IllegalArgumentException.class, () -> service.createStaff(1L, "A B", "not-an-email", null, "ERM", "Title"));
        assertThrows(IllegalArgumentException.class, () -> service.createStaff(1L, "A B", "a@sageitco.com", "nope", "ERM", "Title"));
        assertThrows(IllegalStateException.class, () -> service.createStaff(1L, "A B", "P5@X.COM", null, "ERM", "Title"), "email taken");
    }

    @Test
    void managersAndAccountsAreCreatedWithNoEmailAndTheirPasswordComesBackOnce() {
        for (String role : new String[]{"MANAGER", "accounts"}) {
            StaffOnboardingService.Result r = service.createStaff(1L, "Mona Gate", role + ".one@sageitco.com",
                    "mona@gmail.com", role, "Approvals Manager");
            User created = users.get(r.user().getId());
            assertEquals(role.toUpperCase(), created.getRole().getName());
            assertFalse(r.emailSent());
            assertNull(r.sentTo());
            assertNotNull(r.temporaryPassword());
            assertTrue(r.temporaryPassword().matches("[A-Za-z2-9]{4}(-[A-Za-z2-9]{4}){3}"));
            assertTrue(encoder.matches(r.temporaryPassword(), created.getPasswordHash()), "only the hash is stored");
            assertTrue(created.getMustChangePassword(), "they choose their own at first sign-in");
            assertEquals("Approvals Manager", titleService.titleOf(created.getId()).orElseThrow());
        }
        verify(emails, never()).sendStaffLoginEmail(any(), anyString(), anyString(), anyBoolean());
        verifyNoInteractions(emails);
    }

    @Test
    void aTitleIsRequiredForErmManagerAndAccountsAndOptionalForOtherStaff() {
        for (String role : new String[]{"ERM", "MANAGER", "ACCOUNTS"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.createStaff(1L, "A B", "t-" + role + "@sageitco.com", null, role, "   "), role);
            assertEquals("Title is required.", e.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> service.createStaff(1L, "A B", "t-" + role + "@sageitco.com", null, role, ""), role);
        }
        // Only the new form offers these two, and it always sends the title.
        for (String role : new String[]{"MANAGER", "ACCOUNTS"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createStaff(1L, "A B", "t-" + role + "@sageitco.com", null, role, null), role);
        }
        assertFalse(users.values().stream().anyMatch(u -> u.getEmail().startsWith("t-")), "nothing was created");
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
                () -> service.createStaff(1L, "A B", "long@sageitco.com", null, "ERM", "x".repeat(256)));
        assertEquals("Title is too long (max 255 characters).", tooLong.getMessage());
        assertNotNull(service.createStaff(1L, "A B", "ok@sageitco.com", null, "ERM", "x".repeat(255)).user());

        StaffOnboardingService.Result coach = service.createStaff(1L, "Coach Carl", "carl@sageitco.com", null, "COACH", null);
        assertTrue(coach.emailSent(), "other staff roles keep the emailed login details");
        assertTrue(titleService.titleOf(coach.user().getId()).isEmpty(), "no title, no row");
        assertFalse(titles.containsKey(coach.user().getId()));
        StaffOnboardingService.Result ops = service.createStaff(1L, "Ops Olga", "olga@sageitco.com", null,
                "OPERATIONS_ADMIN", "Operations Lead");
        assertEquals("Operations Lead", titleService.titleOf(ops.user().getId()).orElseThrow());
    }

    @Test
    void theFormBeforeTheTitleBoxStillAddsAnErmWithoutATitle() {
        // That form sends no title at all; the ERM is added as it was then, with its login email.
        StaffOnboardingService.Result r = service.createStaff(1L, "Old Form", "old.form@sageitco.com", null, "ERM", null);
        assertTrue(r.emailSent());
        assertNull(r.temporaryPassword());
        assertTrue(titleService.titleOf(r.user().getId()).isEmpty(), "no title, no row");
    }

    @Test
    void onlyASystemAdminAddsManagersOrAccounts() {
        assertThrows(AccessDeniedException.class, () -> service.createStaff(2L, "A B", "m@sageitco.com", null, "MANAGER", "T"));
        assertThrows(AccessDeniedException.class, () -> service.createStaff(3L, "A B", "m@sageitco.com", null, "ACCOUNTS", "T"));
        assertThrows(AccessDeniedException.class, () -> service.createStaff(5L, "A B", "m@sageitco.com", null, "MANAGER", "T"));
        assertFalse(users.values().stream().anyMatch(u -> "m@sageitco.com".equals(u.getEmail())));
        assertTrue(titles.isEmpty());
    }

    @Test
    void resettingAManagerOrAccountsPasswordSendsNoEmailAndReturnsTheNewPassword() {
        User mgr = person(6, "MANAGER");
        mgr.setPasswordHash(encoder.encode("old-password"));
        User acc = person(7, "ACCOUNTS");
        acc.setPasswordHash(encoder.encode("old-password"));
        for (User u : new User[]{mgr, acc}) {
            StaffOnboardingService.Result r = service.sendNewLoginDetails(1L, u.getId());
            assertFalse(r.emailSent());
            assertNull(r.sentTo());
            assertNotNull(r.temporaryPassword());
            assertTrue(encoder.matches(r.temporaryPassword(), u.getPasswordHash()));
            assertFalse(encoder.matches("old-password", u.getPasswordHash()), "the old password stops working");
            assertTrue(u.getMustChangePassword());
            assertNotNull(u.getSessionsValidAfter(), "earlier sign-ins end");
        }
        verifyNoInteractions(emails);
        assertThrows(AccessDeniedException.class, () -> service.sendNewLoginDetails(2L, 6L), "System Admin only");
        assertThrows(AccessDeniedException.class, () -> service.sendNewLoginDetails(3L, 6L), "System Admin only");

        // An ERM's reset is still emailed, with no password in the result.
        User erm = person(8, "ERM");
        StaffOnboardingService.Result r = service.sendNewLoginDetails(1L, erm.getId());
        assertTrue(r.emailSent());
        assertNull(r.temporaryPassword());
        verify(emails).sendStaffLoginEmail(eq(erm), eq("ERM (relationship manager)"), anyString(), eq(true));
    }

    @Test
    void newLoginDetailsAreForActiveStaffOnly() {
        User fin = users.get(5L);
        fin.setPasswordHash(encoder.encode("old-password"));
        service.sendNewLoginDetails(1L, 5L);
        assertTrue(fin.getMustChangePassword());
        assertFalse(encoder.matches("old-password", fin.getPasswordHash()), "the old password stops working");
        verify(emails).sendStaffLoginEmail(eq(fin), eq("Finance"), anyString(), eq(true));
        assertThrows(IllegalArgumentException.class, () -> service.sendNewLoginDetails(1L, 4L), "participants use Forgot password");
        assertThrows(IllegalArgumentException.class, () -> service.sendNewLoginDetails(1L, 1L), "not for yourself");
        assertThrows(AccessDeniedException.class, () -> service.sendNewLoginDetails(2L, 5L));
        fin.setIsActive(false);
        assertThrows(IllegalStateException.class, () -> service.sendNewLoginDetails(1L, 5L));
    }

    @Test
    void emailsForAStaffMemberGoToTheirPersonalAddress() {
        UserRepository repo = mock(UserRepository.class);
        User staff = User.builder().id(9L).email("erm1@sageitco.com").personalEmail("me@gmail.com").build();
        when(repo.findByEmail("erm1@sageitco.com")).thenReturn(Optional.of(staff));
        when(repo.findByEmail("pat@x.com")).thenReturn(Optional.of(User.builder().id(10L).email("pat@x.com").build()));
        EmailLogService logs = new EmailLogService(mock(EmailLogRepository.class), repo);
        assertEquals("me@gmail.com", logs.deliveryAddress("erm1@sageitco.com"));
        assertEquals("pat@x.com", logs.deliveryAddress("pat@x.com"), "no personal email: unchanged");
        assertEquals("unknown@x.com", logs.deliveryAddress("unknown@x.com"));
    }

    @Test
    void choosingYourOwnPasswordClearsTheTemporaryOne() {
        UserRepository repo = mock(UserRepository.class);
        User u = User.builder().id(7L).email("erm1@sageitco.com").passwordHash(encoder.encode("Temp-1234-abcd-EFGH"))
                .mustChangePassword(true).build();
        when(repo.findById(7L)).thenReturn(Optional.of(u));
        when(repo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        u.setRole(com.spire.backend.entity.Role.builder().name("ERM").build());
        AuthService auth = new AuthService(repo, mock(RoleRepository.class), encoder, mock(JwtService.class),
                mock(RecordService.class), mock(EmailTemplateService.class), mock(WorkflowService.class),
                mock(ParticipantIdService.class));
        assertThrows(IllegalArgumentException.class, () -> auth.changePassword(7L, "wrong", "NewPassword9"));
        assertThrows(IllegalArgumentException.class, () -> auth.changePassword(7L, "Temp-1234-abcd-EFGH", "short"));
        assertThrows(IllegalArgumentException.class, () -> auth.changePassword(7L, "Temp-1234-abcd-EFGH", "Temp-1234-abcd-EFGH"));
        auth.changePassword(7L, "Temp-1234-abcd-EFGH", "MyOwnPassword9");
        assertFalse(u.getMustChangePassword());
        assertNotNull(u.getSessionsValidAfter(), "other sign-ins end");
        assertTrue(encoder.matches("MyOwnPassword9", u.getPasswordHash()));
    }
}
