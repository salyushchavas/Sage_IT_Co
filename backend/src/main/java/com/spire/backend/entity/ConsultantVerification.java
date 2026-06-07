package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Email-OTP verification challenge for the consultant portal gate.
 *
 * Portal phase: challenges are keyed by EMAIL (the consultant identity).
 * One ACTIVE (unconsumed) challenge per email -- a new request-otp
 * supersedes any prior unconsumed row (its {@code consumedAt} is stamped
 * so it's no longer active). The OTP is stored HASHED (BCrypt), single-
 * use, 10-minute, max-5-attempts.
 *
 * The legacy {@code applicationId} column is kept (nullable) so any
 * Phase D rows persisted before the portal switch stay readable; new
 * portal rows leave it null and key off {@code email} instead.
 *
 * DDL_MODE=update auto-evolves this table from the entity on boot.
 * DataSeeder adds {@code email} idempotently for already-deployed
 * databases.
 */
@Entity
@Table(name = "consultant_verification", indexes = {
        @Index(name = "idx_consultant_verif_app", columnList = "application_id"),
        @Index(name = "idx_consultant_verif_email", columnList = "email"),
        @Index(name = "idx_consultant_verif_active",
                columnList = "email, consumed_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsultantVerification {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * Legacy per-app key from Phase D. Nullable now: portal-phase rows
     * key off {@link #email} and leave this null.
     */
    @Column(name = "application_id", length = 64)
    private String applicationId;

    /**
     * Normalised consultant email (lowercase trimmed). The portal mints
     * OTPs against this identity, not against a specific application,
     * so a single verification grants access to every agreement
     * addressed to this email.
     */
    @Column(name = "email", length = 255)
    private String email;

    /** BCrypt hash of the 6-digit OTP -- never store the code in clear. */
    @Column(name = "otp_hash", nullable = false, length = 255)
    private String otpHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    /** Set when the OTP is consumed (verified) OR superseded/invalidated. */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "last_sent_at")
    private LocalDateTime lastSentAt;

    @Column(name = "resend_count", nullable = false)
    @Builder.Default
    private int resendCount = 0;

    /** Client IP (X-Forwarded-For first hop) that requested the OTP. */
    @Column(name = "request_ip", length = 64)
    private String requestIp;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
