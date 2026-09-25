package com.spire.backend.service;

import com.spire.backend.entity.EmailLog;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.EmailLogRepository;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
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
 */
class StaffOnboardingTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final Map<Long, User> users = new HashMap<>();
    private UserRepository userRepo;
    private EmailTemplateService emails;
    private StaffOnboardingService service;

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
        when(emails.sendParticipantInviteEmail(anyString(), anyString(), anyString())).thenReturn(true);
        service = new StaffOnboardingService(userRepo, roles, encoder, mock(RecordService.class), emails);
        person(1, "SYSTEM_ADMIN");
        person(2, "OPERATIONS_ADMIN");
        person(3, "ADMIN");
        person(4, "PARTICIPANT");
        person(5, "FINANCE");
    }

    @Test
    void aSystemAdminAddsAStaffMemberAndTheLoginDetailsGoToTheirOwnEmail() {
        StaffOnboardingService.Result r = service.createStaff(1L, "  Riya   Sharma ", "Riya.Sharma@SageITco.com",
                "riya.personal@gmail.com", "erm");
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
    }

    @Test
    void onlyASystemAdminAddsStaffAndOnlyStaffRoles() {
        assertThrows(AccessDeniedException.class, () -> service.createStaff(2L, "A B", "a@sageitco.com", null, "ERM"), "Operations");
        assertThrows(AccessDeniedException.class, () -> service.createStaff(3L, "A B", "a@sageitco.com", null, "ERM"), "old LMS admin");
        assertThrows(IllegalArgumentException.class, () -> service.createStaff(1L, "A B", "a@sageitco.com", null, "PARTICIPANT"));
        assertThrows(IllegalArgumentException.class, () -> service.createStaff(1L, "A B", "a@sageitco.com", null, "ADMIN"));
        assertThrows(IllegalArgumentException.class, () -> service.createStaff(1L, "A B", "not-an-email", null, "ERM"));
        assertThrows(IllegalArgumentException.class, () -> service.createStaff(1L, "A B", "a@sageitco.com", "nope", "ERM"));
        assertThrows(IllegalStateException.class, () -> service.createStaff(1L, "A B", "P5@X.COM", null, "ERM"), "email taken");
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
    void participantsAreInvitedToEnrollThemselves() {
        assertTrue(service.inviteParticipant(2L, "Jo Doe", "Jo.Doe@Gmail.com"));
        verify(emails).sendParticipantInviteEmail(eq("jo.doe@gmail.com"), eq("Jo Doe"),
                argThat(link -> link.endsWith("/enroll?email=jo.doe%40gmail.com&name=Jo+Doe")));
        assertThrows(IllegalStateException.class, () -> service.inviteParticipant(1L, "Someone", "p4@x.com"), "already has an account");
        assertThrows(AccessDeniedException.class, () -> service.inviteParticipant(5L, "Jo Doe", "new@x.com"), "Finance can't invite");
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
