package com.spire.backend.service;

import com.spire.backend.dto.AuthResponse;
import com.spire.backend.dto.ParticipantEnrollRequest;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sign-in safety rules for the 6-digit email code:
 * no session for an already-verified email, a lockout that sticks,
 * codes stored only as hashes, and email lookups that ignore case.
 */
class AuthServiceVerificationTest {

    private static final String CODE = "123456";

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private JwtService jwtService;
    private EmailTemplateService emailTemplateService;
    private ParticipantIdService participantIdService;
    private WorkflowService workflowService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        jwtService = mock(JwtService.class);
        emailTemplateService = mock(EmailTemplateService.class);
        participantIdService = mock(ParticipantIdService.class);
        authService = new AuthService(userRepository, roleRepository, new BCryptPasswordEncoder(),
                jwtService, mock(RecordService.class), emailTemplateService,
                workflowService = mock(WorkflowService.class), participantIdService);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateAccessToken(anyLong(), anyString())).thenReturn("access");
        when(jwtService.generateRefreshToken(anyLong())).thenReturn("refresh");
    }

    private User pendingUser() {
        return User.builder()
                .id(7L)
                .email("jane@x.com")
                .fullName("Jane Doe")
                .role(Role.builder().name("PARTICIPANT").build())
                .isActive(true)
                .emailVerified(false)
                .verificationCodeHash(AuthService.hashOtp(CODE))
                .verificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10))
                .verificationFailedAttempts(0)
                .build();
    }

    @Test
    void alreadyVerifiedEmailGetsNoSession() {
        User staff = pendingUser();
        staff.setEmailVerified(true);
        when(userRepository.findByEmail("jane@x.com")).thenReturn(Optional.of(staff));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> authService.verifyCode("jane@x.com", "000000"));
        assertTrue(ex.getMessage().contains("already verified"));
        verify(jwtService, never()).generateAccessToken(anyLong(), anyString());
        verify(jwtService, never()).generateRefreshToken(anyLong());
    }

    @Test
    void fiveWrongCodesLockTheAccount() {
        User user = pendingUser();
        when(userRepository.findByEmail("jane@x.com")).thenReturn(Optional.of(user));

        for (int i = 1; i <= 4; i++) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> authService.verifyCode("jane@x.com", "000000"));
            assertTrue(ex.getMessage().contains((5 - i) + " attempt"), ex.getMessage());
        }
        IllegalArgumentException locked = assertThrows(IllegalArgumentException.class,
                () -> authService.verifyCode("jane@x.com", "000000"));
        assertTrue(locked.getMessage().startsWith("Too many wrong attempts"));
        assertEquals(5, user.getVerificationFailedAttempts());
        assertNotNull(user.getVerificationLockedUntil());

        // While locked, even the right code is refused.
        assertThrows(IllegalArgumentException.class, () -> authService.verifyCode("jane@x.com", CODE));
        assertFalse(user.getEmailVerified());
    }

    @Test
    void wrongCodeErrorsDoNotRollBackTheCounter() throws Exception {
        for (String method : List.of("verifyCode", "resendVerificationCode")) {
            Transactional tx = Arrays.stream(AuthService.class.getMethods())
                    .filter(m -> m.getName().equals(method))
                    .findFirst().orElseThrow()
                    .getAnnotation(Transactional.class);
            assertNotNull(tx, method + " must be transactional");
            assertTrue(Arrays.asList(tx.noRollbackFor()).contains(IllegalArgumentException.class),
                    method + " must keep the attempt counter when it reports a bad code");
        }
    }

    @Test
    void resendNeverLiftsAnActiveLock() {
        User user = pendingUser();
        LocalDateTime lockedUntil = LocalDateTime.now().plusMinutes(10);
        user.setVerificationFailedAttempts(5);
        user.setVerificationLockedUntil(lockedUntil);
        String hashBefore = user.getVerificationCodeHash();
        when(userRepository.findByEmail("jane@x.com")).thenReturn(Optional.of(user));

        assertThrows(IllegalArgumentException.class, () -> authService.resendVerificationCode("jane@x.com"));
        assertEquals(lockedUntil, user.getVerificationLockedUntil());
        assertEquals(5, user.getVerificationFailedAttempts());
        assertEquals(hashBefore, user.getVerificationCodeHash());
        verify(emailTemplateService, never()).sendVerificationCodeEmail(any(), anyString());
    }

    @Test
    void rightCodeVerifiesWhateverTheCapitalisation() {
        User user = pendingUser();
        when(userRepository.findByEmail("jane@x.com")).thenReturn(Optional.of(user));
        when(participantIdService.issue(any(User.class))).thenReturn("SIT-2026-00001");

        AuthResponse auth = authService.verifyCode("  Jane@X.com ", CODE);

        assertEquals("access", auth.getAccessToken());
        assertTrue(user.getEmailVerified());
        // Checklist 1.1: verifying no longer jumps the participant to step 15.
        verify(workflowService, never()).transition(any(User.class), eq(WorkflowService.Status.DASHBOARD_ENABLED), anyString());
        assertNull(user.getVerificationCodeHash());
        assertEquals(0, user.getVerificationFailedAttempts());
    }

    @Test
    void deactivatedAccountWithRightCodeGetsNoSession() {
        User user = pendingUser();
        user.setIsActive(false);
        when(userRepository.findByEmail("jane@x.com")).thenReturn(Optional.of(user));

        assertThrows(IllegalStateException.class, () -> authService.verifyCode("jane@x.com", CODE));
        assertFalse(user.getEmailVerified());
        verify(jwtService, never()).generateAccessToken(anyLong(), anyString());
    }

    @Test
    void enrollmentStoresLowercaseEmailAndOnlyTheCodeHash() {
        when(roleRepository.findByName("PARTICIPANT"))
                .thenReturn(Optional.of(Role.builder().name("PARTICIPANT").build()));
        when(userRepository.existsByEmailIgnoreCase("new.person@x.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });

        authService.enrollParticipant(ParticipantEnrollRequest.builder()
                .fullName("New Person").email(" New.Person@X.com ").phone("(555) 555-0100").password("password1")
                .build());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository, atLeastOnce()).save(saved.capture());
        User user = saved.getValue();
        ArgumentCaptor<String> emailedCode = ArgumentCaptor.forClass(String.class);
        verify(emailTemplateService).sendVerificationCodeEmail(any(User.class), emailedCode.capture());

        assertEquals("new.person@x.com", user.getEmail());
        assertTrue(user.getVerificationCodeHash().matches("[0-9a-f]{64}"));
        assertNotEquals(emailedCode.getValue(), user.getVerificationCodeHash());
        assertEquals(AuthService.hashOtp(emailedCode.getValue()), user.getVerificationCodeHash());
    }

    @Test
    void duplicateEmailIsCaughtWhateverTheCapitalisation() {
        when(userRepository.existsByEmailIgnoreCase("jane@x.com")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.enrollParticipant(ParticipantEnrollRequest.builder()
                        .fullName("Jane Doe").email("JANE@X.COM").phone("5550100").password("password1")
                        .build()));
        assertTrue(ex.getMessage().contains("already exists"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void lookupFindsOlderMixedCaseRows() {
        User legacy = pendingUser();
        legacy.setEmail("Jane@X.com");
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.findAllByEmailIgnoreCase("jane@x.com")).thenReturn(List.of(legacy));

        assertSame(legacy, authService.findUserByEmail("jane@x.com").orElseThrow());

        // Two rows that differ only by case: refuse to guess.
        when(userRepository.findAllByEmailIgnoreCase("jane@x.com")).thenReturn(List.of(legacy, pendingUser()));
        assertTrue(authService.findUserByEmail("jane@x.com").isEmpty());
    }

    @Test
    void codesNeverReachTheLogs() {
        assertEquals("Your verification code: ******", EmailService.safeSubject("Your verification code: 123456"));
        assertEquals("Welcome to Sage IT Co", EmailService.safeSubject("Welcome to Sage IT Co"));
    }
}
