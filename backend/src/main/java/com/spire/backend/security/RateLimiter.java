package com.spire.backend.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts attempts per key (a client IP, or an email address) in a sliding
 * time window. In memory: right for the single Railway instance, and the
 * counts start again after a restart, which is acceptable for these limits.
 */
@Component
public class RateLimiter {

    private static final long FORGET_AFTER_MS = Duration.ofHours(2).toMillis();

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** Records an attempt if the key is under its limit; false when it isn't. */
    public boolean tryAcquire(String key, int max, Duration window) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            drop(q, now - window.toMillis());
            if (q.size() >= max) return false;
            q.addLast(now);
            return true;
        }
    }

    /** Whether the key has reached its limit, without recording anything. */
    public boolean isOverLimit(String key, int max, Duration window) {
        Deque<Long> q = hits.get(key);
        if (q == null) return false;
        synchronized (q) {
            drop(q, System.currentTimeMillis() - window.toMillis());
            return q.size() >= max;
        }
    }

    /** Records an attempt with no limit check (e.g. a failed sign-in). */
    public void record(String key) {
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(System.currentTimeMillis());
        }
    }

    /** Forgets a key (e.g. failed sign-ins after a successful one). */
    public void clear(String key) {
        hits.remove(key);
    }

    private static void drop(Deque<Long> q, long cutoff) {
        while (!q.isEmpty() && q.peekFirst() < cutoff) q.pollFirst();
    }

    /** Keys idle for two hours are removed, so memory stays small. */
    @Scheduled(fixedDelay = 600_000)
    void sweep() {
        long cutoff = System.currentTimeMillis() - FORGET_AFTER_MS;
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long last = e.getValue().peekLast();
                return last == null || last < cutoff;
            }
        });
    }
}
