package com.spire.backend.security;

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

/**
 * Authenticates the Bearer token handed out by
 * {@code /api/consultant/applications/{appId}/verify-otp}. Only
 * touches tokens with {@code purpose=consultant} -- regular
 * user-auth tokens are left for the existing {@link JwtAuthFilter}.
 *
 * On success, sets a Spring Security principal whose name is the
 * public {@code applicationId} (UUID) and authority
 * {@code ROLE_CONSULTANT}. Controllers read the principal via
 * {@code Authentication#getName()} to enforce per-application scope:
 *
 *   if (!auth.getName().equals(pathAppId)) -> 403
 *
 * Filter order: this runs BEFORE {@link JwtAuthFilter} so the latter
 * never sees consultant tokens (it has a defensive purpose-check too
 * but this is the canonical path).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsultantJwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

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
        if (!jwtService.isTokenValid(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        String purpose;
        try {
            purpose = jwtService.extractPurpose(token);
        } catch (Exception e) {
            // Not a consultant token -- defer to JwtAuthFilter.
            filterChain.doFilter(request, response);
            return;
        }
        if (!"consultant".equals(purpose)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String applicationId = jwtService.extractConsultantApplicationId(token);
            if (applicationId == null || applicationId.isBlank()) {
                filterChain.doFilter(request, response);
                return;
            }

            List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority("ROLE_CONSULTANT"));

            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(applicationId, null, authorities);
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        } catch (Exception e) {
            log.warn("Consultant JWT validation failed: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
