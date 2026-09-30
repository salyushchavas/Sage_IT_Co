package com.spire.backend.service;

import com.spire.backend.dto.AuthResponse;
import com.spire.backend.entity.ParticipantApplication;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.ParticipantApplicationRepository;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Roadmap step 1 as asked for on 30 Sep: a visitor applies, an ERM
 * confirms, the emailed one-time link registers the account (already
 * verified, with its Participant ID). Nobody gets an account without a
 * confirmed application.
 */
class ParticipantApplicationTest {

    private final Map<Long, User> users = new LinkedHashMap<>();
    private final List<ParticipantApplication> applications = new ArrayList<>();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private EmailTemplateService emails;
    private ParticipantApplicationService service;

    private User person(long id, String role) {
        User u = User.builder().id(id).email("p" + id + "@x.com").fullName("Person " + id)
                .role(Role.builder().name(role).build()).isActive(true).emailVerified(true).build();
        users.put(id, u);
        return u;
    }

    @BeforeEach
    void setUp() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(users.get((Long) inv.getArgument(0))));
        when(userRepo.findAll()).thenAnswer(inv -> new ArrayList<>(users.values()));
        when(userRepo.findAllById(any())).thenAnswer(inv -> {
            List<User> found = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) if (users.containsKey(id)) found.add(users.get(id));
            return found;
        });
        when(userRepo.existsByEmailIgnoreCase(anyString())).thenAnswer(inv -> users.values().stream()
                .anyMatch(u -> u.getEmail().equalsIgnoreCase(inv.getArgument(0))));
        when(userRepo.findByPhoneNormalized(anyString())).thenAnswer(inv -> users.values().stream()
                .filter(u -> inv.getArgument(0).equals(u.getPhoneNormalized())).toList());
        when(userRepo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId((long) (100 + users.size()));
            users.put(u.getId(), u);
            return u;
        });

        ParticipantApplicationRepository appRepo = mock(ParticipantApplicationRepository.class);
        when(appRepo.save(any(ParticipantApplication.class))).thenAnswer(inv -> {
            ParticipantApplication a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId((long) (applications.size() + 1));
                a.setCreatedAt(LocalDateTime.now());
                applications.add(a);
            }
            return a;
        });
        when(appRepo.findById(anyLong())).thenAnswer(inv -> applications.stream()
                .filter(a -> a.getId().equals(inv.getArgument(0))).findFirst());
        when(appRepo.findFirstByEmailAndStatusInOrderByCreatedAtDesc(anyString(), any())).thenAnswer(inv -> applications.stream()
                .filter(a -> a.getEmail().equals(inv.getArgument(0))
                        && ((Collection<?>) inv.getArgument(1)).contains(a.getStatus())).findFirst());
        when(appRepo.findByRegistrationTokenHash(anyString())).thenAnswer(inv -> applications.stream()
                .filter(a -> inv.getArgument(0).equals(a.getRegistrationTokenHash())).findFirst());
        when(appRepo.findByRegistrationTokenHashForUpdate(anyString())).thenAnswer(inv -> applications.stream()
                .filter(a -> inv.getArgument(0).equals(a.getRegistrationTokenHash())).findFirst());
        when(appRepo.findTop500ByStatusOrderByCreatedAtAsc(anyString())).thenAnswer(inv -> applications.stream()
                .filter(a -> a.getStatus().equals(inv.getArgument(0))).toList());
        when(appRepo.countByStatus(anyString())).thenAnswer(inv -> applications.stream()
                .filter(a -> a.getStatus().equals(inv.getArgument(0))).count());

        RoleRepository roles = mock(RoleRepository.class);
        when(roles.findByName(anyString())).thenAnswer(inv -> Optional.of(Role.builder().name(inv.getArgument(0)).build()));
        JwtService jwt = mock(JwtService.class);
        when(jwt.generateAccessToken(anyLong(), anyString())).thenReturn("access");
        when(jwt.generateRefreshToken(anyLong())).thenReturn("refresh");
        emails = mock(EmailTemplateService.class);
        when(emails.sendRegistrationEmail(anyString(), anyString(), any(), anyString(), anyInt(), anyBoolean())).thenReturn(true);
        when(emails.sendParticipantIdEmail(any(), anyString())).thenReturn(true);
        ParticipantIdService ids = mock(ParticipantIdService.class);
        when(ids.issue(any())).thenReturn("SIT-2026-00042");
        RecordService records = mock(RecordService.class);
        AuthService auth = new AuthService(userRepo, roles, encoder, jwt, records, emails,
                mock(WorkflowService.class), ids);
        service = new ParticipantApplicationService(appRepo, userRepo, auth, emails, records,
                new PermissionService(null, null));

        person(1, "ERM");
        person(2, "OPERATIONS_ADMIN");
        person(3, "SYSTEM_ADMIN");
        person(4, "PARTICIPANT");
        person(5, "FINANCE");
        person(6, "COACH");
    }

    private ParticipantApplication applied() {
        return service.apply("  Jo   Doe ", "Jo.Doe@Gmail.com", "(555) 201-3344", "Java Full Stack", "203.0.113.9");
    }

    /** The token in the link of the latest registration email. */
    private String emailedToken(int emailsSoFar) {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(emails, times(emailsSoFar)).sendRegistrationEmail(anyString(), anyString(), any(), link.capture(), anyInt(), anyBoolean());
        String url = link.getValue();
        assertTrue(url.contains("/register?token="), url);
        return url.substring(url.indexOf("token=") + 6);
    }

    // ── Step 1: applying ──────────────────────────────────────────

    @Test
    void applyingCreatesAWaitingApplicationAndTellsTheApplicantAndTheErms() {
        ParticipantApplication a = applied();
        assertEquals("PENDING", a.getStatus());
        assertEquals("jo.doe@gmail.com", a.getEmail(), "lower-cased");
        assertEquals("Jo Doe", a.getFullName());
        assertEquals("+15552013344", a.getPhoneNormalized());
        assertEquals("Java Full Stack", a.getSelectedTechnology());
        assertNull(a.getRegistrationTokenHash(), "no link until an ERM confirms");
        assertTrue(users.values().stream().noneMatch(u -> u.getEmail().equals("jo.doe@gmail.com")), "no account yet");

        verify(emails).sendApplicationReceivedEmail("jo.doe@gmail.com", "Jo Doe", "Java Full Stack");
        verify(emails).sendApplicationToConfirmEmail(eq(users.get(1L)), eq("Jo Doe"), eq("jo.doe@gmail.com"),
                eq("(555) 201-3344"), eq("Java Full Stack"));
        verify(emails, times(1)).sendApplicationToConfirmEmail(any(), any(), any(), any(), any());
        verify(emails, never()).sendRegistrationEmail(anyString(), anyString(), any(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void anApplicationNeedsRealDetails() {
        assertThrows(IllegalArgumentException.class, () -> service.apply("Jo", "jo@x.org", "5552013344", "Java Full Stack", null), "first and last name");
        assertThrows(IllegalArgumentException.class, () -> service.apply("Jo Doe", "not-an-email", "5552013344", "Java Full Stack", null));
        assertThrows(IllegalArgumentException.class, () -> service.apply("Jo Doe", "jo@x.org", "", "Java Full Stack", null));
        assertThrows(IllegalArgumentException.class, () -> service.apply("Jo Doe", "jo@x.org", "12", "Java Full Stack", null));
        assertThrows(IllegalArgumentException.class, () -> service.apply("Jo Doe", "jo@x.org", "5552013344", " ", null), "a course is required");
        assertThrows(IllegalArgumentException.class, () -> service.apply("Jo Doe", "jo@x.org", "5552013344", "<a href=x>", null));
        assertThrows(IllegalArgumentException.class, () -> service.apply("<b>Jo</b> Doe", "jo@x.org", "5552013344", "Java Full Stack", null));
        assertThrows(IllegalStateException.class, () -> service.apply("Jo Doe", "P4@X.com", "5552013344", "Java Full Stack", null), "already has an account");
        assertTrue(applications.isEmpty());
    }

    @Test
    void applyingAgainWhileWaitingUpdatesTheSameApplicationWithoutNewEmails() {
        applied();
        ParticipantApplication again = service.apply("Jo Doe", "jo.doe@gmail.com", "555 201 9999", "Cloud & DevOps", null);
        assertEquals(1, applications.size());
        assertEquals("Cloud & DevOps", again.getSelectedTechnology());
        assertEquals("+15552019999", again.getPhoneNormalized());
        verify(emails, times(1)).sendApplicationReceivedEmail(anyString(), anyString(), anyString());
        verify(emails, times(1)).sendApplicationToConfirmEmail(any(), any(), any(), any(), any());
    }

    // ── Step 2: an ERM confirms ───────────────────────────────────

    @Test
    void confirmingEmailsAOneTimeLinkAndStoresOnlyItsHash() {
        ParticipantApplication a = applied();
        ParticipantApplicationService.Result r = service.confirm(a.getId(), 1L);
        assertTrue(r.emailSent());
        assertEquals("APPROVED", a.getStatus());
        assertEquals(1L, a.getReviewedBy());
        assertNotNull(a.getRegistrationEmailSentAt());
        assertTrue(a.getRegistrationTokenExpiresAt().isAfter(LocalDateTime.now().plusDays(6)));

        String token = emailedToken(1);
        assertTrue(token.length() >= 40);
        assertNotEquals(token, a.getRegistrationTokenHash());
        assertEquals(AuthService.hashOtp(token), a.getRegistrationTokenHash());
        verify(emails).sendRegistrationEmail(eq("jo.doe@gmail.com"), eq("Jo Doe"), eq("Java Full Stack"),
                anyString(), eq(7), eq(false));

        assertThrows(IllegalStateException.class, () -> service.confirm(a.getId(), 1L), "already confirmed");
        assertThrows(IllegalStateException.class, () -> service.apply("Jo Doe", "jo.doe@gmail.com", "5552013344", "Java Full Stack", null),
                "they're told to check their email");
    }

    @Test
    void onlyErmsAndOperationsManageApplications() {
        ParticipantApplication a = applied();
        for (long notAllowed : List.of(4L, 5L, 6L)) {
            assertThrows(AccessDeniedException.class, () -> service.confirm(a.getId(), notAllowed));
            assertThrows(AccessDeniedException.class, () -> service.decline(a.getId(), notAllowed, "no"));
            assertThrows(AccessDeniedException.class, () -> service.list(notAllowed, null));
        }
        assertEquals("PENDING", a.getStatus());
        assertEquals(1, service.list(1L, null).size());
        assertEquals(1, service.list(2L, "PENDING").size());
        assertEquals(1L, service.counts(3L).get("PENDING"));
        assertThrows(IllegalArgumentException.class, () -> service.list(1L, "NONSENSE"));
    }

    @Test
    void theStaffListNeverCarriesTheLinkOrTheIpAddress() {
        ParticipantApplication a = applied();
        service.confirm(a.getId(), 1L);
        ParticipantApplicationService.Row row = service.list(1L, "APPROVED").get(0);
        assertEquals("Person 1", row.reviewedByName());
        assertEquals("jo.doe@gmail.com", row.email());
        String shown = row.toString();
        assertFalse(shown.contains(a.getRegistrationTokenHash()));
        assertFalse(shown.contains("203.0.113.9"));
    }

    @Test
    void aDeclinedApplicationsLinkStopsWorkingAndItCanBeConfirmedLater() {
        ParticipantApplication a = applied();
        service.confirm(a.getId(), 1L);
        String token = emailedToken(1);
        service.decline(a.getId(), 2L, "Duplicate of another application");
        assertEquals("DECLINED", a.getStatus());
        assertEquals("Duplicate of another application", a.getDeclineReason());
        assertThrows(IllegalArgumentException.class, () -> service.registrationDetails(token));
        assertThrows(IllegalArgumentException.class, () -> service.register(token, "password1", null));

        service.confirm(a.getId(), 1L);
        assertEquals("APPROVED", a.getStatus());
        assertNull(a.getDeclineReason());
    }

    @Test
    void sendingTheLinkAgainReplacesTheOldOne() {
        ParticipantApplication a = applied();
        service.confirm(a.getId(), 1L);
        String first = emailedToken(1);
        assertThrows(IllegalStateException.class, () -> service.resend(a.getId(), 1L), "one a minute");

        a.setRegistrationEmailSentAt(LocalDateTime.now().minusMinutes(2));
        assertTrue(service.resend(a.getId(), 1L).emailSent());
        String second = emailedToken(2);
        assertNotEquals(first, second);
        assertThrows(IllegalArgumentException.class, () -> service.registrationDetails(first), "the old link is dead");
        assertEquals("jo.doe@gmail.com", service.registrationDetails(second).get("email"));
    }

    // ── Step 3: registering from the link ─────────────────────────

    @Test
    void registeringCreatesAVerifiedParticipantWithAnIdAndUsesUpTheLink() {
        ParticipantApplication a = applied();
        service.confirm(a.getId(), 1L);
        String token = emailedToken(1);

        Map<String, Object> details = service.registrationDetails(token);
        assertEquals("Jo Doe", details.get("fullName"));
        assertEquals("Java Full Stack", details.get("selectedTechnology"));
        assertEquals(false, details.get("needsPhone"));

        assertThrows(IllegalArgumentException.class, () -> service.register(token, "short", null), "password rule");
        assertEquals("APPROVED", a.getStatus(), "a failed try leaves the link usable");

        AuthResponse auth = service.register(token, "a-good-password", null);
        assertEquals("access", auth.getAccessToken());
        User created = users.get(auth.getUser().getId());
        assertEquals("jo.doe@gmail.com", created.getEmail());
        assertEquals("Jo Doe", created.getFullName());
        assertEquals("PARTICIPANT", created.getRole().getName());
        assertTrue(created.getEmailVerified(), "the link proved the address");
        assertEquals("(555) 201-3344", created.getPhone());
        assertEquals("+15552013344", created.getPhoneNormalized());
        assertEquals("Java Full Stack", created.getSelectedTechnology());
        assertEquals("SIT-2026-00042", created.getParticipantId());
        assertTrue(encoder.matches("a-good-password", created.getPasswordHash()));
        assertNull(created.getVerificationCodeHash(), "no code to enter");
        verify(emails).sendParticipantIdEmail(created, "SIT-2026-00042");

        assertEquals("REGISTERED", a.getStatus());
        assertEquals(created.getId(), a.getUserId());
        assertNull(a.getRegistrationTokenHash());
        assertThrows(IllegalArgumentException.class, () -> service.register(token, "a-good-password", null), "one use");
        assertThrows(IllegalArgumentException.class, () -> service.registrationDetails(token));
        assertThrows(IllegalStateException.class, () -> service.confirm(a.getId(), 1L), "already registered");
        assertThrows(IllegalStateException.class, () -> service.decline(a.getId(), 1L, "x"));
    }

    @Test
    void anExpiredOrMadeUpLinkRegistersNobody() {
        ParticipantApplication a = applied();
        assertThrows(IllegalArgumentException.class, () -> service.register("made-up-token", "a-good-password", null));
        assertThrows(IllegalArgumentException.class, () -> service.register(null, "a-good-password", null));

        service.confirm(a.getId(), 1L);
        String token = emailedToken(1);
        a.setRegistrationTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertThrows(IllegalArgumentException.class, () -> service.registrationDetails(token));
        assertThrows(IllegalArgumentException.class, () -> service.register(token, "a-good-password", null));
        assertEquals(6, users.size(), "no account was created");
    }

    @Test
    void aPhoneTakenSinceApplyingIsRefusedAtRegistration() {
        ParticipantApplication a = applied();
        service.confirm(a.getId(), 1L);
        users.get(4L).setPhoneNormalized("+15552013344");
        assertThrows(IllegalArgumentException.class, () -> service.register(emailedToken(1), "a-good-password", null));
        assertEquals("APPROVED", a.getStatus());
    }

    // ── Operations' invite: confirmed from the start ──────────────

    @Test
    void anInviteIsAnApplicationThatIsAlreadyConfirmed() {
        assertTrue(service.invite(2L, "Sam Lee", "Sam.Lee@Gmail.com"));
        ParticipantApplication a = applications.get(0);
        assertEquals("APPROVED", a.getStatus());
        assertEquals("INVITE", a.getSource());
        verify(emails).sendRegistrationEmail(eq("sam.lee@gmail.com"), eq("Sam Lee"), isNull(), anyString(), eq(7), eq(true));
        verify(emails, never()).sendApplicationToConfirmEmail(any(), any(), any(), any(), any());

        String token = emailedToken(1);
        assertEquals(true, service.registrationDetails(token).get("needsPhone"), "invites have no phone yet");
        assertThrows(IllegalArgumentException.class, () -> service.register(token, "a-good-password", null), "phone needed");
        AuthResponse auth = service.register(token, "a-good-password", "555 777 1212");
        assertEquals("+15557771212", users.get(auth.getUser().getId()).getPhoneNormalized());

        assertThrows(IllegalStateException.class, () -> service.invite(3L, "Someone", "p4@x.com"), "already has an account");
        assertThrows(AccessDeniedException.class, () -> service.invite(1L, "Jo Doe", "new@x.com"), "ERMs confirm; they don't invite");
        assertThrows(AccessDeniedException.class, () -> service.invite(5L, "Jo Doe", "new@x.com"));
    }
}
