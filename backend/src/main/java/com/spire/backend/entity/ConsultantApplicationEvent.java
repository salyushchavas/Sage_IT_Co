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
        EXPIRED,
        // Phase C — ERM edits the consultant contact; super-admin
        // archives (soft-deletes) a cancelled application.
        CONSULTANT_CONTACT_UPDATED,
        APPLICATION_ARCHIVED,
        // Build G — consultant uploads their Appendix 5 security cheque.
        CHEQUE_UPLOADED,
        // Build W — consultant uploads their Appendix 1 work-authorization
        // document (image/PDF).
        WORK_AUTH_UPLOADED,
        // Build I — consultant uploads their Phase 2 Employment offer letter.
        OFFER_LETTER_UPLOADED,
        // Build J — Background Check uploads: DL/State-ID doc (required) and
        // SSN doc (optional). Build AK splits the ID doc into a Driver's
        // License doc and a State-ID doc (at least one required).
        DL_DOC_UPLOADED,
        STATE_ID_DOC_UPLOADED,
        SSN_DOC_UPLOADED,
        // 3B — role-based approval workflow. SENT_FOR_APPROVAL when the
        // ERM routes a VERIFIED agreement to the phase's required
        // approvers (also on re-send after a revision); APPROVAL_APPROVED
        // / APPROVAL_REVISION_REQUESTED carry the approver role + note.
        SENT_FOR_APPROVAL,
        APPROVAL_APPROVED,
        APPROVAL_REVISION_REQUESTED,
        // Build M — ERM reopens a Phase-1 COMPLETED agreement for
        // Phase 2 on the same document. Metadata carries the promoted
        // sections + the actor's user id.
        ADVANCED_TO_PHASE_2,
        // Build T — e-sign consent captured at the consent gate before
        // the consultant starts the wizard. Metadata: consent version
        // + IP.
        CONSENT_GIVEN,
        // Build T — ERM releases the consultant-version PDF (with
        // appended Certificate of Completion). Distinct from
        // APPROVED_AND_SIGNED, which is the ERM countersign.
        CONSULTANT_VERSION_APPROVED,
        // Build T — consultant requested a fresh OTP to download
        // their released consultant-version PDF.
        DOWNLOAD_OTP_SENT,
        // Build T — consultant successfully downloaded the released
        // consultant-version PDF (post-OTP verify).
        CONSULTANT_DOWNLOAD,
        // Build AC — super-admin revoked the ERM countersignature on a
        // COMPLETED agreement and reverted it for re-approval + re-signature.
        ERM_SIGNATURE_REVOKED
    }

    public enum ActorType {
        ERM,
        CONSULTANT,
        SYSTEM
    }
}
