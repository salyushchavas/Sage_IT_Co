package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * ERM-to-Consultant engagement application.
 *
 * Public-facing identifier is {@code applicationId} (a UUID); the
 * numeric {@code id} stays internal. Lifecycle:
 *
 *   DRAFT -> SUBMITTED -> [REVISION_REQUESTED -> UPDATED ->]*
 *           VERIFIED -> SIGNED -> COMPLETED
 *
 * Terminal off-ramps: CANCELLED (ERM-initiated) or EXPIRED (cron
 * after {@code expiresAt} crosses).
 *
 * The structured payload (consultant onboarding fields) is stored as
 * a JSON-in-TEXT column rather than native JSONB so the same row
 * shape works on MySQL dev + Postgres prod -- mirrors the convention
 * {@link Acknowledgment}.consentFlags follows. Parse via ObjectMapper
 * in the service layer; do not query inside the JSON from SQL.
 */
@Entity
@Table(name = "consultant_applications", indexes = {
        @Index(name = "idx_consultant_app_id", columnList = "application_id", unique = true),
        @Index(name = "idx_consultant_erm_id", columnList = "erm_user_id"),
        @Index(name = "idx_consultant_email", columnList = "consultant_email"),
        @Index(name = "idx_consultant_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsultantApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUID -- the only identifier exposed to consultants in URLs / emails. */
    @Column(name = "application_id", nullable = false, length = 64, unique = true)
    private String applicationId;

    @Column(name = "erm_user_id", nullable = false)
    private Long ermUserId;

    @Column(name = "consultant_email", nullable = false, length = 255)
    private String consultantEmail;

    @Column(name = "consultant_name", length = 255)
    private String consultantName;

    @Column(name = "consultant_phone", length = 32)
    private String consultantPhone;

    /**
     * Free-form JSON payload of consultant onboarding fields. v1 ships
     * with a placeholder schema -- the ERM types raw JSON in a textarea
     * -- so the column is intentionally opaque to the DB. Structured
     * fields land once the schema is locked.
     */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    /** See class doc for the state machine. */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "revision_notes", columnDefinition = "TEXT")
    private String revisionNotes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    // ── Signing audit trail ──────────────────────────────────────────

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "signature_image", columnDefinition = "TEXT")
    private String signatureImage;

    @Column(name = "signed_legal_name", length = 255)
    private String signedLegalName;

    @Column(name = "signed_ip", length = 64)
    private String signedIp;

    @Column(name = "signed_user_agent", columnDefinition = "TEXT")
    private String signedUserAgent;

    /** Cloudinary URL of the generated signed PDF. */
    @Column(name = "signed_pdf_url", columnDefinition = "TEXT")
    private String signedPdfUrl;

    // ── Consultant OTP state ─────────────────────────────────────────

    @Column(name = "otp_hash", length = 255)
    private String otpHash;

    @Column(name = "otp_expires_at")
    private LocalDateTime otpExpiresAt;

    @Column(name = "otp_failed_attempts", nullable = false)
    @Builder.Default
    private int otpFailedAttempts = 0;

    @Column(name = "otp_locked_until")
    private LocalDateTime otpLockedUntil;

    // ── Status enum (string-keyed; lives here so the service layer
    //    has a single source of truth). ──────────────────────────────

    public enum Status {
        DRAFT,
        SUBMITTED,
        REVISION_REQUESTED,
        UPDATED,
        VERIFIED,
        SIGNED,
        COMPLETED,
        CANCELLED,
        EXPIRED
    }
}
