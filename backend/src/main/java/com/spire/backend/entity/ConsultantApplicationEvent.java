package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Append-only audit log for {@link ConsultantApplication}. One row per
 * state change or notable side-effect (email sent, OTP requested,
 * PDF generated, etc.). Used by the ERM detail timeline.
 *
 * Metadata payload is JSON-in-TEXT -- field diffs, OTP-failure
 * counts, email recipients, etc. live there. Like the parent row,
 * intentionally opaque to SQL.
 */
@Entity
@Table(name = "consultant_application_events", indexes = {
        @Index(name = "idx_consultant_event_app_id", columnList = "application_id"),
        @Index(name = "idx_consultant_event_created", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsultantApplicationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@link ConsultantApplication#id} (numeric, not the UUID). */
    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    /** See {@link EventType} for the canonical set. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** ERM, CONSULTANT, or SYSTEM. */
    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    /** Null for CONSULTANT (we only have the email, no User row) and SYSTEM. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum EventType {
        CREATED,
        UPDATED,
        // OTP_* values remain in the enum for backward-compat with any
        // historical event rows; the OTP flow itself was removed when
        // the consultant surface switched to application-id-as-credential.
        OTP_SENT,
        OTP_VERIFIED,
        OTP_FAILED,
        ACCESSED,
        DETAILS_VERIFIED,
        // Two-stage fill workflow (Phase 3). CONSULTANT_FILLED is
        // emitted any time the consultant patches non-null values
        // onto an application; APPROVED_AND_SIGNED is emitted when
        // the ERM countersigns and the final PDF lands. Other
        // workflow steps reuse existing values (CREATED for ermCreate,
        // SIGNED for consultantSubmit, REVISION_REQUESTED with
        // actorType=ERM for the ERM-side request-revision, EMAIL_SENT
        // for send-pdf-to-recipient).
        CONSULTANT_FILLED,
        APPROVED_AND_SIGNED,
        REVISION_REQUESTED,
        REVISED,
        SIGNED,
        PDF_GENERATED,
        EMAIL_SENT,
        CANCELLED,
        EXPIRED
    }

    public enum ActorType {
        ERM,
        CONSULTANT,
        SYSTEM
    }
}
