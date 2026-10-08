package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Append-only audit log for {@link WebAgreement}, the website copy of
 * {@link ConsultantApplicationEvent}. One row per state change or notable
 * side-effect. Unlike the console, every row carries the real users.id of
 * the ERM or participant who acted (null only for SYSTEM).
 *
 * Metadata is JSON-in-TEXT, opaque to SQL.
 */
@Entity
@Table(name = "web_agreement_events", indexes = {
        @Index(name = "idx_web_agreement_event_agreement", columnList = "agreement_id"),
        @Index(name = "idx_web_agreement_event_created", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebAgreementEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@link WebAgreement#getId()} (numeric, not the UUID). */
    @Column(name = "agreement_id", nullable = false)
    private Long agreementId;

    /** See {@link EventType}. Stored as {@code type.name()}. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** ERM, PARTICIPANT or SYSTEM. */
    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    /** users.id of the actor; null for SYSTEM. */
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

    /** The console's event names that apply to the website flow. */
    public enum EventType {
        CREATED,
        ACCESSED,
        CONSENT_GIVEN,
        CONSULTANT_FILLED,
        WORK_AUTH_UPLOADED,
        OFFER_LETTER_UPLOADED,
        DL_DOC_UPLOADED,
        STATE_ID_DOC_UPLOADED,
        SSN_DOC_UPLOADED,
        CHEQUE_UPLOADED,
        // A cheque's number/date edited without a new file. Its own type so
        // the take-back guard sees it (it reads the event log).
        CHEQUE_METADATA_UPDATED,
        SIGNED,
        REVISION_REQUESTED,
        REVISION_REVOKED,
        CANCELLED,
        CONSULTANT_CONTACT_UPDATED,
        EMAIL_SENT,
        // The ERM verified the signed agreement (the console's
        // CONSULTANT_VERSION_APPROVED).
        VERIFIED,
        INVITE_RESENT,
        // Approval chain, countersign, Phase 2 and the System Admin's tools
        // (same names as the console).
        SENT_FOR_APPROVAL,
        APPROVAL_APPROVED,
        APPROVAL_REVISION_REQUESTED,
        APPROVED_AND_SIGNED,
        PDF_GENERATED,
        ADVANCED_TO_PHASE_2,
        ERM_SIGNATURE_REVOKED,
        APPLICATION_ARCHIVED
    }

    public enum ActorType {
        ERM,
        PARTICIPANT,
        SYSTEM
    }
}
