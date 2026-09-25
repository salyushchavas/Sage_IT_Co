package com.spire.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.User;
import com.spire.backend.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Staff onboarding: an account still on the temporary password an admin's
 * email gave it can only sign in, read its own profile and change its
 * password. Everything else gets a 403 with PASSWORD_CHANGE_REQUIRED, which
 * the website turns into the "choose your password" page.
 */
@Component
@RequiredArgsConstructor
public class PasswordChangeGateFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    static boolean isExempt(String path, String method) {
        if (path == null) return true;
        return path.startsWith("/api/auth/")
                || path.equals("/api/health")
                || path.equals("/api/brand")
                || (path.equals("/api/users/profile") && "GET".equalsIgnoreCase(method));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (isExempt(request.getRequestURI(), request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = null;
        if (auth != null && auth.getPrincipal() != null) {
            try {
                userId = Long.parseLong(auth.getPrincipal().toString());
            } catch (NumberFormatException ignored) {
                // not a portal user token (e.g. the agreements console)
            }
        }
        Optional<User> user = userId == null ? Optional.empty() : userRepository.findById(userId);
        if (user.isEmpty() || !Boolean.TRUE.equals(user.get().getMustChangePassword())) {
            chain.doFilter(request, response);
            return;
        }
        ApiResponse<Map<String, String>> body = ApiResponse.<Map<String, String>>builder()
                .success(false)
                .message("PASSWORD_CHANGE_REQUIRED")
                .data(Map.of("error", "PASSWORD_CHANGE_REQUIRED"))
                .build();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
