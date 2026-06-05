package com.spire.backend.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Single-process sliding-window rate limiter for the public consultant
 * OTP endpoints. Two independent buckets:
 *
 *   request-otp:  3 attempts per (applicationId + IP) per 1h
 *   verify-otp:   5 attempts per applicationId per 15m
 *
 * Sage runs a single Railway instance today; if the deploy ever scales
 * horizontally this needs to move to Redis. Documented at the top of
 * the controller so the migration is obvious.
 *
 * Implementation: per-key {@link Deque} of timestamps. Each call
 * prunes timestamps outside the window, refuses if count is at the
 * cap, otherwise pushes the current Instant. ConcurrentHashMap +
 * synchronized-on-deque per key keeps it thread-safe without locking
 * the whole map.
 */
@Component
public class ConsultantOtpRateLimiter {

    private static final int REQUEST_OTP_MAX = 3;
    private static final Duration REQUEST_OTP_WINDOW = Duration.ofHours(1);

    private static final int VERIFY_OTP_MAX = 5;
    private static final Duration VERIFY_OTP_WINDOW = Duration.ofMinutes(15);

    private final ConcurrentMap<String, Deque<Instant>> requestOtpHits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<Instant>> verifyOtpHits = new ConcurrentHashMap<>();

    public boolean allowRequestOtp(String applicationId, String ip) {
        String key = applicationId + "|" + (ip == null ? "?" : ip);
        return tryAcquire(requestOtpHits, key, REQUEST_OTP_MAX, REQUEST_OTP_WINDOW);
    }

    public boolean allowVerifyOtp(String applicationId) {
        return tryAcquire(verifyOtpHits, applicationId, VERIFY_OTP_MAX, VERIFY_OTP_WINDOW);
    }

    /**
     * For observability + tests. Returns the remaining quota in the
     * current window. Cheap (just prunes + counts).
     */
    public int remainingRequestOtp(String applicationId, String ip) {
        String key = applicationId + "|" + (ip == null ? "?" : ip);
        return remaining(requestOtpHits, key, REQUEST_OTP_MAX, REQUEST_OTP_WINDOW);
    }

    public int remainingVerifyOtp(String applicationId) {
        return remaining(verifyOtpHits, applicationId, VERIFY_OTP_MAX, VERIFY_OTP_WINDOW);
    }

    private boolean tryAcquire(ConcurrentMap<String, Deque<Instant>> map, String key,
                               int max, Duration window) {
        Deque<Instant> deque = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque, window);
            if (deque.size() >= max) return false;
            deque.addLast(Instant.now());
            return true;
        }
    }

    private int remaining(ConcurrentMap<String, Deque<Instant>> map, String key,
                          int max, Duration window) {
        Deque<Instant> deque = map.get(key);
        if (deque == null) return max;
        synchronized (deque) {
            prune(deque, window);
            return Math.max(0, max - deque.size());
        }
    }

    private static void prune(Deque<Instant> deque, Duration window) {
        Instant cutoff = Instant.now().minus(window);
        Iterator<Instant> it = deque.iterator();
        while (it.hasNext()) {
            if (it.next().isBefore(cutoff)) it.remove();
            else break;
        }
    }
}
