package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Generic legal/policy acknowledgment record. The existing
 * {@link AgreementAcceptance} table stays for the ToS-acceptance flow
 * the agreement page already drives; this table captures the broader
 * family of acknowledgments the new participant lifecycle introduces
 * (data-policy, phase-1-completion, payment-plan, …) so each one has
 * an independent, append-only row with its own IP / UA / version
 * stamp.
 *
 * {@code consentFlags} stores the per-form checkbox state as a JSON
 * string (no native JSONB → keeps the column portable across MySQL
 * dev and Postgres prod).
 */
@Entity
@Table(name = "acknowledgments", indexes = {
        @Index(name = "idx_ack_user_id", columnList = "user_id"),
        @Index(name = "idx_ack_type", columnList = "acknowledgment_type")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Acknowledgment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "acknowledgment_type", nullable = false, length = 50)
    private String acknowledgmentType;

    @Column(name = "legal_name", nullable = false, length = 255)
    private String legalName;

    @Column(name = "accepted_text_version", nullable = false, length = 20)
    private String acceptedTextVersion;

    @Column(name = "consent_flags", columnDefinition = "TEXT")
    private String consentFlags;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "device", length = 100)
    private String device;

    /**
     * Base64 PNG/JPG of the participant's digital signature, drawn
     * or uploaded at the time of acceptance. Stored as a data-URL
     * (with the {@code data:image/png;base64,…} prefix) so it can
     * drop straight into an &lt;img src&gt; on the admin display.
     * Nullable for rows from older acknowledgment flows that didn't
     * capture a signature.
     */
    @Column(name = "signature_image", columnDefinition = "TEXT")
    private String signatureImage;

    /** Either {@code "draw"} or {@code "upload"}. Audit value only. */
    @Column(name = "signature_method", length = 20)
    private String signatureMethod;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
