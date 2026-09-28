package com.spire.backend.repository;

import com.spire.backend.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * The user row, locked until the transaction ends. Used at the start of
     * per-user write transitions (employment acceptance, acknowledgment, check
     * upload) so a "check then insert" can't run twice concurrently and create
     * duplicate rows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * Atomically flip email_verified false -> true; returns 1 only for the
     * request that actually flips it. Lets verify-code serialise the
     * Participant-ID mint without a row lock, so two racing verifications
     * can't both mint (and email) a different ID.
     */
    @Modifying(flushAutomatically = true)
    @Query("update User u set u.emailVerified = true where u.id = :id and u.emailVerified = false")
    int markEmailVerifiedIfPending(@Param("id") Long id);

    /**
     * The account's CURRENT role name, only while the account is active.
     * Used by JwtAuthFilter on every request, so a deactivated user's
     * still-valid token stops working at once and a role change takes
     * effect on the next request instead of when the token expires.
     */
    @Query("SELECT r.name FROM User u JOIN u.role r WHERE u.id = :id AND u.isActive = true")
    Optional<String> findActiveRoleName(@Param("id") Long id);

    /** What JwtAuthFilter needs about an active account, in one lookup. */
    interface ActiveSignIn {
        String getRole();
        Long getSessionsValidAfter();
    }

    @Query("SELECT r.name AS role, u.sessionsValidAfter AS sessionsValidAfter "
            + "FROM User u JOIN u.role r WHERE u.id = :id AND u.isActive = true")
    Optional<ActiveSignIn> findActiveSignIn(@Param("id") Long id);

    Optional<User> findByEmail(String email);

    /** Staff onboarding: who a personal (delivery) email belongs to. */
    Optional<User> findFirstByPersonalEmailIgnoreCase(String personalEmail);

    boolean existsByEmail(String email);

    /**
     * Case-insensitive duplicate check for signup. New rows are stored
     * lowercased, but older rows may not be, and Postgres compares
     * case-sensitively.
     */
    boolean existsByEmailIgnoreCase(String email);

    /** Case-insensitive lookup; used only as a fallback for older mixed-case rows. */
    List<User> findAllByEmailIgnoreCase(String email);

    Optional<User> findByVerificationToken(String token);

    Optional<User> findByResetToken(String token);

    /**
     * Finds users for the inactivity nudge: must have at least one
     * enrollment, no recent activity (no record / progress write
     * within the cutoff), and the last nudge was either never sent
     * or was sent before {@code nudgeThrottleCutoff}. Implemented as
     * a JPQL existence subquery so a single round-trip drives the
     * scheduled job.
     */
    @Query("""
            SELECT DISTINCT u FROM User u
            WHERE u.isActive = true
              AND u.emailVerified = true
              AND EXISTS (
                SELECT 1 FROM Enrollment e WHERE e.user = u
              )
              AND NOT EXISTS (
                SELECT 1 FROM Progress p
                WHERE p.user = u
                  AND p.lastAccessed > :inactiveCutoff
              )
              AND (u.lastNudgeSentAt IS NULL OR u.lastNudgeSentAt < :nudgeThrottleCutoff)
            """)
    List<User> findInactiveCandidates(
            @Param("inactiveCutoff") LocalDateTime inactiveCutoff,
            @Param("nudgeThrottleCutoff") LocalDateTime nudgeThrottleCutoff
    );

    List<User> findByCurrentStatus(String currentStatus);

    /** Checklist 1.5: accounts holding this phone number (normalized form). */
    List<User> findByPhoneNormalized(String phoneNormalized);
}
