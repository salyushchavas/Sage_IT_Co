package com.spire.backend.security;

import com.spire.backend.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        if (jwtService.isTokenValid(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
            // Skip side-feature tokens whose subject is NOT a numeric
            // user id, so this filter's extractUserId (Long.parseLong of
            // the subject) never throws:
            //   - Agreement-ERM tokens carry purpose=agreement_erm
            //     (handled by AgreementErmAuthFilter, which runs first).
            //   - Consultant portal tokens carry type=consultant and an
            //     email subject (validated by the consultant controller
            //     guard). Without this skip, parseLong(email) throws and
            //     the request error-dispatches to /error -> 403.
            String purpose = null;
            String type = null;
            try {
                purpose = jwtService.extractPurpose(token);
                type = jwtService.extractTokenType(token);
            } catch (Exception ignored) {
                // Legacy tokens without the claims -- fall through.
            }
            if ((purpose != null && !purpose.isBlank())
                    || "consultant".equals(type)) {
                filterChain.doFilter(request, response);
                return;
            }

            Long userId;
            try {
                userId = jwtService.extractUserId(token);
            } catch (NumberFormatException e) {
                // Defensive: any other non-numeric-subject token is not a
                // user-auth token -- let downstream guards handle it
                // rather than 500/403-ing the whole request.
                log.warn("JWT subject is not a numeric user id; skipping user auth.");
                filterChain.doFilter(request, response);
                return;
            }
            String tokenRole = jwtService.extractRole(token);

            // Guard: refresh tokens have no role claim → reject as auth token
            if (tokenRole == null || tokenRole.isBlank()) {
                log.warn("JWT has no role claim — possibly a refresh token used as access token. UserId: {}", userId);
                filterChain.doFilter(request, response);
                return;
            }

            // The account must still exist and be active, and its CURRENT
            // role is what counts: a deactivated user's token (valid for up
            // to 15 more minutes) stops working at once, and a demoted user
            // loses the old role on the next request. One indexed lookup.
            // Leaving the context unauthenticated makes the entry point 401.
            var signIn = userRepository.findActiveSignIn(userId).orElse(null);
            String role = signIn == null ? null : signIn.getRole();
            if (role == null || role.isBlank()) {
                log.warn("Rejected JWT for inactive or unknown user {}", userId);
                filterChain.doFilter(request, response);
                return;
            }
            // Issued before a password change, reset or deactivation: ended.
            Long validAfter = signIn.getSessionsValidAfter();
            if (validAfter != null) {
                Long issuedAt = jwtService.extractIssuedAtSeconds(token);
                if (issuedAt == null || issuedAt < validAfter) {
                    log.info("Rejected an ended sign-in for user {}", userId);
                    filterChain.doFilter(request, response);
                    return;
                }
            }

            List<SimpleGrantedAuthority> authorities = authoritiesFor(role);

            log.debug("JWT Auth — userId: {}, role: {}, authorities: {}", userId, role, authorities);

            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * The authorities for a database role. System Admin is the top role and
     * uses the same admin pages as the legacy ADMIN role, so it also gets
     * ROLE_ADMIN; otherwise the LMS admin endpoints (courses, coupons,
     * announcements, sales), which only name ADMIN, refused it.
     */
    static List<SimpleGrantedAuthority> authoritiesFor(String role) {
        if ("SYSTEM_ADMIN".equals(role)) {
            return List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"),
                    new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
