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
 * Public-consultant-surface rate limiter. Two buckets:
 *
 *   read  (GET):  20 / IP        / 1 minute
 *   write (POST): 10 / appId     / 1 minute
 *
 * Reads are throttled per source IP so a single bad actor can't sweep
 * UUIDs at speed; writes are throttled per appId so an attacker who
 * knows one UUID can't hammer revision-requested / sign endpoints.
 *
 * Single-process sliding-window. Sage runs one Railway instance today;
 * if the deploy scales horizontally this needs to move to Redis.
 */
@Component
public class ConsultantRateLimiter {

    private static final int READ_MAX = 20;
    private static final Duration READ_WINDOW = Duration.ofMinutes(1);

    private static final int WRITE_MAX = 10;
    private static final Duration WRITE_WINDOW = Duration.ofMinutes(1);

    // OTP endpoints — tighter than general consultant traffic. Keyed by
    // appId|ip so neither a single IP nor a single app can be swept.
    // Combined with the 5-attempt cap, 6-digit brute force is infeasible.
    private static final int OTP_REQUEST_MAX = 5;
    private static final Duration OTP_REQUEST_WINDOW = Duration.ofMinutes(1);
    private static final int OTP_VERIFY_MAX = 10;
    private static final Duration OTP_VERIFY_WINDOW = Duration.ofMinutes(1);

    private final ConcurrentMap<String, Deque<Instant>> readHits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<Instant>> writeHits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<Instant>> otpRequestHits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<Instant>> otpVerifyHits = new ConcurrentHashMap<>();

    public boolean allowRead(String ip) {
        return tryAcquire(readHits, ip == null ? "?" : ip, READ_MAX, READ_WINDOW);
    }

    public boolean allowWrite(String applicationId) {
        return tryAcquire(writeHits, applicationId == null ? "?" : applicationId,
                WRITE_MAX, WRITE_WINDOW);
    }

    public boolean allowOtpRequest(String key) {
        return tryAcquire(otpRequestHits, key == null ? "?" : key,
                OTP_REQUEST_MAX, OTP_REQUEST_WINDOW);
    }

    public boolean allowOtpVerify(String key) {
        return tryAcquire(otpVerifyHits, key == null ? "?" : key,
                OTP_VERIFY_MAX, OTP_VERIFY_WINDOW);
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

    private static void prune(Deque<Instant> deque, Duration window) {
        Instant cutoff = Instant.now().minus(window);
        Iterator<Instant> it = deque.iterator();
        while (it.hasNext()) {
            if (it.next().isBefore(cutoff)) it.remove();
            else break;
        }
    }
}
