package com.spire.backend.service;

import com.spire.backend.config.BrandConfig;
import com.spire.backend.entity.User;
import com.spire.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.UUID;

/**
 * Generates the public-facing participant identifier
 * ({@code SIT-{YYYY}-{NNNNN}}) and stamps it on the {@link User}
 * row. The ID is permanent once issued — never reused, never
 * recycled, never re-emitted for a different user even if the
 * original participant's row is soft-deleted.
 *
 * Strategy:
 *   1. Compute the current highest 5-digit suffix for the year
 *      (single aggregate query — Postgres index does the heavy
 *      lifting via the unique constraint on participant_id).
 *   2. Try suffix+1 first (fast monotonic path).
 *   3. On unique-constraint collision (race with another node /
 *      manual seed row), fall back to a randomised 5-digit suffix
 *      with up to 20 retries.
 *   4. Final fallback after 20 collisions: append a UUID-derived
 *      6-char hex tag. Defensive — not expected to fire.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParticipantIdService {

    private static final int RANDOM_RETRIES = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final BrandConfig brandConfig;

    /** Resolves the brand-configured prefix (defaults to "SIT"). */
    private String prefix() {
        String p = brandConfig.getIdPrefix();
        return (p == null || p.isBlank()) ? "SIT" : p;
    }

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Mints a new participant ID for the user if they don't already
     * have one, persists it on the row, and returns the assigned id.
     * Idempotent — re-calling for an already-IDed user returns the
     * existing value without touching the row.
     */
    @Transactional
    public String issue(User user) {
        if (user.getParticipantId() != null && !user.getParticipantId().isBlank()) {
            return user.getParticipantId();
        }
        String year = String.valueOf(Year.now().getValue());
        String candidate = nextSequential(year);

        String assigned = tryAssign(user, candidate);
        if (assigned != null) return assigned;

        // Race / pre-existing collision — fall back to randomised.
        for (int i = 0; i < RANDOM_RETRIES; i++) {
            candidate = randomised(year);
            String r = tryAssign(user, candidate);
            if (r != null) return r;
        }

        // Pathological: append a UUID tail. Format diverges from
        // SIT-YYYY-NNNNN but stays unique and parseable.
        String tail = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        candidate = prefix() + "-" + year + "-X" + tail;
        log.warn("Participant ID falling back to UUID tail for user {}: {}",
                user.getId(), candidate);
        return tryAssign(user, candidate);
    }

    /** Builds the next monotonic candidate from the existing max. */
    private String nextSequential(String year) {
        String like = prefix() + "-" + year + "-%";
        // Single-row aggregate; the unique index on participant_id
        // means at most one row matches each suffix so the COUNT-style
        // approach is safe and dialect-portable.
        Object raw = entityManager
                .createNativeQuery(
                        "SELECT MAX(participant_id) FROM users " +
                                "WHERE participant_id LIKE :like")
                .setParameter("like", like)
                .getSingleResult();
        int next = 1;
        if (raw instanceof String s && s.length() > (prefix().length() + 1 + year.length() + 1)) {
            String suffix = s.substring(prefix().length() + 1 + year.length() + 1);
            try {
                next = Integer.parseInt(suffix.replaceAll("\\D", "")) + 1;
            } catch (NumberFormatException ignored) {
                next = 1;
            }
        }
        return String.format("%s-%s-%05d", prefix(), year, next);
    }

    /** A random 5-digit suffix — collision-resilient bottom of the funnel. */
    private String randomised(String year) {
        int n = RANDOM.nextInt(100_000); // 0–99999
        return String.format("%s-%s-%05d", prefix(), year, n);
    }

    /**
     * Attempts to claim {@code candidate} on {@code user}. Returns
     * the assigned id on success, null on collision (caller retries).
     * Swallows ConstraintViolationException so the retry loop above
     * can move on cleanly.
     */
    private String tryAssign(User user, String candidate) {
        if (userRepository.findAll().stream()
                .anyMatch(u -> candidate.equals(u.getParticipantId()))) {
            return null;
        }
        try {
            user.setParticipantId(candidate);
            user.setParticipantIdCreatedAt(LocalDateTime.now());
            userRepository.save(user);
            log.info("Issued participant ID {} for user {}", candidate, user.getId());
            return candidate;
        } catch (Exception e) {
            log.debug("Participant ID assign collision for {} on user {}: {}",
                    candidate, user.getId(), e.getMessage());
            // Clear the field on the in-memory entity so a retry
            // doesn't re-attempt with the same value.
            user.setParticipantId(null);
            return null;
        }
    }
}
