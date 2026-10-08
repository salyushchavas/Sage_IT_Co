package com.spire.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Keeps an agreements-console sign-in on the console's own addresses. The
 * console's filter (AgreementErmAuthFilter, not changed here) accepts its
 * token on every address, with the console email as the principal and
 * only ROLE_AGREEMENT_* authorities. Website endpoints that only ask for a
 * sign-in read the principal as a website user id, so a console account
 * whose email was a number acted as that website user.
 *
 * Spring Boot registers this filter after Spring Security's chain (which
 * sits at order -100), so the sign-in is already resolved here. A request
 * carrying a console authority on any address outside {@link #CONSOLE_ROOTS}
 * gets a 401 and never reaches a website controller. The console's
 * addresses, website sign-ins and anonymous requests pass untouched.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class ConsoleTokenBoundaryFilter extends OncePerRequestFilter {

    /**
     * The console's addresses: every mapping of its controllers
     * (AgreementErmAuthController, ConsultantApplicationController,
     * AgreementApproverController, AgreementAdminController) and every
     * address its pages call with the console token. No website controller
     * is mapped under them (ConsoleTokenBoundaryFilterTest checks both).
     */
    static final List<String> CONSOLE_ROOTS = List.of(
            "/api/agreement-erm",
            "/api/agreement-approver",
            "/api/agreements/admin",
            "/api/consultant");

    /** What AgreementErmAuthFilter grants; no website role starts with it. */
    static final String CONSOLE_AUTHORITY_PREFIX = "ROLE_AGREEMENT_";

    private final ObjectMapper objectMapper = new ObjectMapper();

    static boolean isConsolePath(String path) {
        if (path == null) return false;
        for (String root : CONSOLE_ROOTS) {
            if (path.equals(root) || path.startsWith(root + "/")) return true;
        }
        return false;
    }

    static boolean isConsoleSignIn(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (a != null && a.getAuthority() != null && a.getAuthority().startsWith(CONSOLE_AUTHORITY_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!isConsoleSignIn(SecurityContextHolder.getContext().getAuthentication()) || isConsolePath(path)) {
            chain.doFilter(request, response);
            return;
        }
        log.info("Refused a console sign-in on the website address {} {}", request.getMethod(), path);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error("Sign in to the website to use this.")));
    }
}
