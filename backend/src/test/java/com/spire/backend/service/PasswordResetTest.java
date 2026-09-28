package com.spire.backend.service;

import com.spire.backend.dto.LoginRequest;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.exception.TooManyRequestsException;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** QA 2026-09-28: reset links are stored hashed, and a reset lifts the sign-in pause it promises to. */
class PasswordResetTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private UserRepository userRepository;
    private EmailTemplateService emails;
    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        emails = mock(EmailTemplateService.class);
        JwtService jwt = mock(JwtService.class);
        when(jwt.generateAccessToken(anyLong(), anyString())).thenReturn("access");
        when(jwt.generateRefreshToken(anyLong())).thenReturn("refresh");
        authService = new AuthService(userRepository, mock(RoleRepository.class), ENCODER, jwt,
                mock(RecordService.class), emails, mock(WorkflowService.class), mock(ParticipantIdService.class));
        user = User.builder().id(10L).email("pat@x.com").fullName("Pat Q").passwordHash(ENCODER.encode("OldPassword#1"))
                .role(Role.builder().name("PARTICIPANT").build()).isActive(true).emailVerified(true).build();
        when(userRepository.findByEmail("pat@x.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private String requestResetAndCaptureToken() {
        authService.requestPasswordReset("pat@x.com");
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emails).sendPasswordResetEmail(eq(user), token.capture());
        return token.getValue();
    }

    @Test
    void theDatabaseNeverHoldsTheEmailedLink() {
        String emailed = requestResetAndCaptureToken();
        assertNotEquals(emailed, user.getResetToken());
        assertEquals(AuthService.hashOtp(emailed), user.getResetToken());
    }

    @Test
    void theEmailedLinkResetsThePasswordOnce() {
        String emailed = requestResetAndCaptureToken();
        when(userRepository.findByResetToken(AuthService.hashOtp(emailed))).thenReturn(Optional.of(user));
        authService.resetPassword(emailed, "NewPassword#2");
        assertTrue(ENCODER.matches("NewPassword#2", user.getPasswordHash()));
        assertNull(user.getResetToken());
        // The stored hash itself is not a working link.
        assertThrows(IllegalArgumentException.class, () -> authService.resetPassword(AuthService.hashOtp(emailed), "Other#Pass3"));
    }

    @Test
    void aResetLiftsTheSignInPause() {
        for (int i = 0; i < 10; i++) {
            assertThrows(RuntimeException.class, () -> authService.login(new LoginRequest("pat@x.com", "wrong-password")));
        }
        assertThrows(TooManyRequestsException.class, () -> authService.login(new LoginRequest("pat@x.com", "OldPassword#1")));
        String emailed = requestResetAndCaptureToken();
        when(userRepository.findByResetToken(AuthService.hashOtp(emailed))).thenReturn(Optional.of(user));
        authService.resetPassword(emailed, "NewPassword#2");
        assertNotNull(authService.login(new LoginRequest("pat@x.com", "NewPassword#2")));
    }
}
