package com.spire.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.User;
import com.spire.backend.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Enforces the Terms-of-Service acceptance gate. Runs after
 * {@link JwtAuthFilter}; if the request is authenticated and the
 * user's {@code agreementAccepted} flag is false, the filter
 * short-circuits with a 403 carrying the {@code AGREEMENT_REQUIRED}
 * marker that the frontend's API helper uses to redirect to
 * /agreement.
 *
 * Path exclusions:
 *   - /api/auth/**       — login / signup / verify / agreement endpoints
 *   - /api/agreement/**  — public terms read
 *   - /api/health        — liveness probe
 *
 * For unauthenticated requests this filter is a no-op — Spring
 * Security's chain still gates them on the auth requirements. We
 * only step in when there's a User to check.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgreementGateFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static boolean isExempt(String path) {
        if (path == null) return true;
        return path.startsWith("/api/auth/")
                || path.startsWith("/api/agreement/")
                || path.equals("/api/health")
                // Profile read is exempt so the AuthProvider on the
                // /agreement-legacy page itself can hydrate the
                // logged-in user. Profile contains only the user's
                // own personal data; safe to expose pre-acceptance.
                || path.equals("/api/users/profile")
                // Phase 1A+: the entire participant lifecycle API
                // surface is exempt. In the new 26-status flow,
                // {@code agreement_accepted} doesn't flip to true
                // until Step 9 (agreement signed), so participants need to
                // call /acknowledgments, /documents/*, and
                // /program-selection while the legacy boolean is
                // still false. Workflow-state gates on each
                // endpoint enforce ordering instead.
                || path.startsWith("/api/participants/");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (isExempt(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            // Unauthenticated request — let the rest of the chain
            // decide whether to allow it. The agreement gate only
            // applies once we know who's calling.
            filterChain.doFilter(request, response);
            return;
        }

        Long userId;
        try {
            userId = Long.parseLong(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            // Principal isn't a numeric user id — anonymous or some
            // other auth shape. Pass through.
            filterChain.doFilter(request, response);
            return;
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            // Token references a user that's been deleted — let
            // existing handlers 401/404; not our problem here.
            filterChain.doFilter(request, response);
            return;
        }

        User user = userOpt.get();
        if (Boolean.TRUE.equals(user.getAgreementAccepted())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Agreement not accepted — short-circuit with the 403 the
        // frontend interceptor expects. We write the body directly
        // (rather than throw) so the response has consistent shape
        // even when this filter runs before any controller advice.
        log.debug("Blocking {} {} — user {} has not accepted the agreement",
                request.getMethod(), path, userId);
        Map<String, String> data = new HashMap<>();
        data.put("error", "AGREEMENT_REQUIRED");
        ApiResponse<Map<String, String>> body = ApiResponse.<Map<String, String>>builder()
                .success(false)
                .message("AGREEMENT_REQUIRED")
                .data(data)
                .build();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}
