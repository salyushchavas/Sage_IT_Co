package com.spire.backend.security;

import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A deactivated account loses access at once (not when its token expires),
 * and the role in force is the one in the database, not the one written
 * into the token when it was issued.
 */
class SessionCutoffTest {

    private JwtService jwtService;
    private UserRepository userRepository;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtService = mock(JwtService.class);
        userRepository = mock(UserRepository.class);
        filter = new JwtAuthFilter(jwtService, userRepository);
        when(jwtService.isTokenValid("token")).thenReturn(true);
        when(jwtService.extractUserId("token")).thenReturn(10L);
        when(jwtService.extractRole("token")).thenReturn("SYSTEM_ADMIN");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Authentication runFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static UserRepository.ActiveSignIn signIn(String role, Long validAfter) {
        return new UserRepository.ActiveSignIn() {
            public String getRole() { return role; }
            public Long getSessionsValidAfter() { return validAfter; }
        };
    }

    @Test
    void deactivatedAccountIsNotSignedInEvenWithAValidToken() throws Exception {
        when(userRepository.findActiveSignIn(10L)).thenReturn(Optional.empty());
        assertNull(runFilter());
    }

    @Test
    void aTokenFromBeforeAPasswordChangeNoLongerWorks() throws Exception {
        long change = 1_790_000_000_500L;   // a password change, in milliseconds
        when(userRepository.findActiveSignIn(10L)).thenReturn(Optional.of(signIn("PARTICIPANT", change)));
        when(jwtService.extractIssuedAtMillis("token")).thenReturn(change - 1);
        assertNull(runFilter(), "issued a millisecond before the change, in the same second");
        SecurityContextHolder.clearContext();
        when(jwtService.extractIssuedAtMillis("token")).thenReturn(change);
        assertNotNull(runFilter(), "issued at (or after) the change");
    }

    @Test
    void aRefreshTokenFromBeforeAPasswordChangeIsRefused() {
        AuthService authService = new AuthService(userRepository, mock(RoleRepository.class), new BCryptPasswordEncoder(),
                jwtService, mock(RecordService.class), mock(EmailTemplateService.class), mock(WorkflowService.class),
                mock(ParticipantIdService.class));
        when(jwtService.isRefreshToken("refresh")).thenReturn(true);
        when(jwtService.extractUserId("refresh")).thenReturn(10L);
        when(jwtService.extractIssuedAtMillis("refresh")).thenReturn(1_790_000_000_000L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(User.builder().id(10L)
                .role(Role.builder().name("ERM").build()).isActive(true).sessionsValidAfter(1_790_000_000_500L).build()));
        assertThrows(UnauthorizedException.class, () -> authService.refreshToken("refresh"));
    }

    @Test
    void theRoleInTheDatabaseWinsOverTheRoleInTheToken() throws Exception {
        when(userRepository.findActiveSignIn(10L)).thenReturn(Optional.of(signIn("PARTICIPANT", null)));
        Authentication auth = runFilter();
        assertNotNull(auth);
        assertEquals(10L, auth.getPrincipal());
        assertEquals("ROLE_PARTICIPANT", auth.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void refreshIsRefusedForADeactivatedAccount() {
        AuthService authService = new AuthService(userRepository, mock(RoleRepository.class), new BCryptPasswordEncoder(),
                jwtService, mock(RecordService.class), mock(EmailTemplateService.class), mock(WorkflowService.class),
                mock(ParticipantIdService.class));
        when(jwtService.isRefreshToken("refresh")).thenReturn(true);
        when(jwtService.extractUserId("refresh")).thenReturn(10L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(User.builder().id(10L)
                .role(Role.builder().name("ERM").build()).isActive(false).build()));

        UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> authService.refreshToken("refresh"));
        assertEquals("Invalid or expired refresh token", ex.getMessage());
        verify(jwtService, never()).generateAccessToken(anyLong(), anyString());
    }
}
