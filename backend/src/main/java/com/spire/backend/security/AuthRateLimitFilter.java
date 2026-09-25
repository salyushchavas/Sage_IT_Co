package com.spire.backend.security;

import com.spire.backend.service.AcknowledgmentService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Per-address limits on the public sign-in endpoints, so passwords can't
 * be guessed at speed and nobody can use our address to flood a person's
 * inbox with codes or reset links (and use up the daily sending limit).
 * Per-account limits live in AuthService. Over the limit: 429.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private record Rule(String path, int max, Duration window) {}

    /** POST only. Generous enough for an office of people behind one address. */
    private static final List<Rule> RULES = List.of(
            new Rule("/api/auth/login", 60, Duration.ofMinutes(5)),
            new Rule("/api/agreement-erm/login", 20, Duration.ofMinutes(5)),
            new Rule("/api/auth/forgot-password", 10, Duration.ofMinutes(15)),
            new Rule("/api/auth/reset-password", 20, Duration.ofMinutes(15)),
            new Rule("/api/auth/register", 30, Duration.ofHours(1)),
            new Rule("/api/participants/enroll", 30, Duration.ofHours(1)),
            new Rule("/api/auth/resend-code", 20, Duration.ofMinutes(15)),
            new Rule("/api/auth/verify-code", 40, Duration.ofMinutes(15)),
            new Rule("/api/auth/change-password", 20, Duration.ofMinutes(15)),
            new Rule("/api/auth/agreement/accept", 20, Duration.ofHours(1)));

    private final RateLimiter limiter;

    /** Multiplies every limit; only raised for local test runs (all from one address). */
    @org.springframework.beans.factory.annotation.Value("${app.rate-limit.ip-multiplier:1}")
    private int multiplier = 1;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String path = request.getRequestURI();
            for (Rule rule : RULES) {
                if (!rule.path().equals(path)) continue;
                String ip = AcknowledgmentService.clientIp(request);
                if (!limiter.tryAcquire("ip:" + rule.path() + ":" + ip, rule.max() * Math.max(1, multiplier), rule.window())) {
                    log.warn("Rate limit: {} from {}", rule.path(), ip);
                    response.setStatus(429);
                    response.setHeader("Retry-After", String.valueOf(rule.window().toSeconds()));
                    response.setContentType("application/json");
                    response.getWriter().write("{\"success\":false,\"message\":\"Too many attempts. "
                            + "Please wait a few minutes and try again.\",\"data\":null}");
                    return;
                }
                break;
            }
        }
        chain.doFilter(request, response);
    }
}
