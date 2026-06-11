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
 *
 * Multi-user phase: this filter now authenticates ALL agreement-console
 * users (the bootstrapped SUPER_ADMIN and every ERM minted through the
 * admin console), not just the single hardcoded operator -- the class
 * name is kept for surgicality. Both roles still receive {@code
 * ROLE_AGREEMENT_ERM} so the existing application endpoints keep working
 * unchanged; the finer role (SUPER_ADMIN | ERM) plus the user id are
 * exposed downstream via request attributes (see {@link AgreementAuthz})
 * for the admin guard and owner stamping.
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
            String role = jwtService.extractAgreementRole(token);

            // 3A — role-derived authorities. Every console user gets the
            // base ROLE_AGREEMENT_USER (identity endpoint). ERM +
            // SUPER_ADMIN keep ROLE_AGREEMENT_ERM (the management +
            // countersign surface). MANAGER / ACCOUNTS get their approver
            // authority; SUPER_ADMIN additionally holds both approver
            // authorities so it can act on any gate.
            java.util.List<SimpleGrantedAuthority> authorities =
                    new java.util.ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_AGREEMENT_USER"));
            if ("SUPER_ADMIN".equals(role) || "ERM".equals(role) || role == null) {
                // Legacy tokens (null role) keep the historical ERM grant.
                authorities.add(new SimpleGrantedAuthority("ROLE_AGREEMENT_ERM"));
            }
            if ("MANAGER".equals(role) || "SUPER_ADMIN".equals(role)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_AGREEMENT_MANAGER"));
            }
            if ("ACCOUNTS".equals(role) || "SUPER_ADMIN".equals(role)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_AGREEMENT_ACCOUNTS"));
            }

            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(email, null, authorities);
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);

            // Expose the authenticated agreement user's identity to
            // controllers (admin guard, owner stamping, /me). Claims may
            // be null on legacy tokens issued before the multi-user
            // phase -- downstream code treats a null role as non-admin.
            request.setAttribute(AgreementAuthz.ATTR_USER_ID, jwtService.extractAgreementUserId(token));
            request.setAttribute(AgreementAuthz.ATTR_ROLE, jwtService.extractAgreementRole(token));
            request.setAttribute(AgreementAuthz.ATTR_EMAIL, email);
            request.setAttribute(AgreementAuthz.ATTR_FULL_NAME, jwtService.extractAgreementFullName(token));
            request.setAttribute(AgreementAuthz.ATTR_TITLE, jwtService.extractAgreementTitle(token));
        } catch (Exception e) {
            log.warn("Agreement-ERM JWT validation failed: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
