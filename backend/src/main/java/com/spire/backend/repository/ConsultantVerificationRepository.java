package com.spire.backend.repository;

import com.spire.backend.entity.ConsultantVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for consultant email-OTP challenges. PK is a String UUID.
 */
public interface ConsultantVerificationRepository
        extends JpaRepository<ConsultantVerification, String> {

    // ── Legacy per-app finders (Phase D rows) ───────────────────────

    /** The current active (unconsumed) challenge for an application, if any. */
    Optional<ConsultantVerification>
            findFirstByApplicationIdAndConsumedAtIsNullOrderByCreatedAtDesc(String applicationId);

    /** Most recent challenge regardless of state (for resend cooldown). */
    Optional<ConsultantVerification>
            findFirstByApplicationIdOrderByCreatedAtDesc(String applicationId);

    /** All unconsumed challenges for an application (to supersede them). */
    List<ConsultantVerification> findByApplicationIdAndConsumedAtIsNull(String applicationId);

    /** Hourly resend cap: how many OTPs were sent for this app recently. */
    long countByApplicationIdAndLastSentAtAfter(String applicationId, LocalDateTime since);

    // ── Portal email finders ────────────────────────────────────────

    /** The current active (unconsumed) challenge for an email, if any. */
    Optional<ConsultantVerification>
            findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc(String email);

    /** Most recent challenge for an email regardless of state (resend cooldown). */
    Optional<ConsultantVerification>
            findFirstByEmailOrderByCreatedAtDesc(String email);

    /** All unconsumed challenges for an email (to supersede them on resend). */
    List<ConsultantVerification> findByEmailAndConsumedAtIsNull(String email);

    /** Hourly resend cap: OTPs sent for this email recently. */
    long countByEmailAndLastSentAtAfter(String email, LocalDateTime since);
}
