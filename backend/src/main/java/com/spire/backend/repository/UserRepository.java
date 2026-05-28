package com.spire.backend.repository;

import com.spire.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

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
}
