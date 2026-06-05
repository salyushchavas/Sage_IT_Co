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
 * {@code POST /api/agreement-erm/login}. Only touches tokens with
 * {@code purpose=agreement_erm} -- regular user-auth tokens are
 * left for the existing {@link JwtAuthFilter}.
 *
 * On success, sets a Spring Security principal whose name is the
 * operator email (subject claim) and authority {@code
 * ROLE_AGREEMENT_ERM}. Filter order: runs BEFORE {@link
 * JwtAuthFilter} so that filter never sees agreement-erm tokens.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgreementErmAuthFilter extends OncePerRequestFilter {

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
            filterChain.doFilter(request, response);
            return;
        }
        if (!"agreement_erm".equals(purpose)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String email = jwtService.extractSubject(token);
            List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority("ROLE_AGREEMENT_ERM"));

            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(email, null, authorities);
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        } catch (Exception e) {
            log.warn("Agreement-ERM JWT validation failed: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
