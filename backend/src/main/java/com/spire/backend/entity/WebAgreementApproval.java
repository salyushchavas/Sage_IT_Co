package com.spire.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One approver's gate on a {@link WebAgreement}, the website copy of the
 * console's {@link AgreementApproval}. The ERM's "Send for approval"
 * writes one PENDING row per required gate for the agreement's phase:
 * Phase 1 = {MANAGER}; Phase 2 = {MANAGER, ACCOUNTS} (parallel).
 *
 * The approver previews the frozen version and either approves or asks
 * for a revision (the note goes back to the ERM, not the participant).
 * When every row of the open round is APPROVED the agreement becomes
 * READY_TO_SIGN; a revision request makes it APPROVAL_REVISION_REQUESTED.
 * A re-send bumps {@code round} and writes fresh PENDING rows, so every
 * round's decisions stay on record.
 *
 * Role and status are plain strings (pass {@code ApproverRole.X.name()} /
 * {@code Decision.X.name()}), like {@link WebAgreement#getStatus()}: an
 * enum column would become a MySQL enum / Postgres CHECK that
 * ddl-auto=update never alters. User ids are website users.id.
 *
 * Rows are never deleted. When a purge un-routes a PENDING gate (role
 * change or delete of the routed approver), the previous routing is kept
 * in the unrouted* columns; no rule reads them and they never reach a
 * browser.
 */
@Entity
@Table(name = "web_agreement_approvals", indexes = {
        @Index(name = "idx_web_agreement_approval_agreement", columnList = "agreement_id"),
        @Index(name = "idx_web_agreement_approval_round", columnList = "agreement_id, round_no, role"),
        @Index(name = "idx_web_agreement_approval_status", columnList = "status, role"),
        @Index(name = "idx_web_agreement_approval_approver", columnList = "approver_user_id, status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebAgreementApproval {

    /** The two approver gates; each maps 1:1 to the website role of the same name. */
    public enum ApproverRole {
        MANAGER,
        ACCOUNTS
    }

    /** Per-gate decision state. */
    public enum Decision {
        PENDING,
        APPROVED,
        REVISION_REQUESTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@link WebAgreement#getId()} (numeric, not the UUID). */
    @Column(name = "agreement_id", nullable = false)
    private Long agreementId;

    /** MANAGER or ACCOUNTS ({@link ApproverRole}). */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    /** PENDING, APPROVED or REVISION_REQUESTED ({@link Decision}). */
    @Column(name = "status", nullable = false, length = 24)
    @Builder.Default
    private String status = Decision.PENDING.name();

    /** Approver's note (required when requesting a revision; optional on approve). */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** Coaching phase (1/2) this round belongs to. */
    @Column(name = "phase", nullable = false)
    @Builder.Default
    private Integer phase = 1;

    /**
     * Send round (1-based), counted across the whole agreement; bumped on
     * every re-send. Column round_no because ROUND is an SQL function name.
     */
    @Column(name = "round_no", nullable = false)
    @Builder.Default
    private Integer round = 1;

    /** users.id of the approver this gate is routed to; null = any approver of the role. */
    @Column(name = "approver_user_id")
    private Long approverUserId;

    /** Routed approver's name, captured at send (for badges and the board). */
    @Column(name = "approver_name", length = 255)
    private String approverName;

    /** users.id of whoever decided (null while PENDING). */
    @Column(name = "decided_by")
    private Long decidedBy;

    /** Decider's name, captured at decision time. */
    @Column(name = "decided_by_name", length = 255)
    private String decidedByName;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Client IP at decision time ({@code AcknowledgmentService.clientIp}). */
    @Column(name = "decided_ip", length = 64)
    private String decidedIp;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── Routing history (kept instead of blanked; never read by a rule) ──

    /** users.id the gate was routed to before a purge un-routed it. */
    @JsonIgnore
    @Column(name = "unrouted_from_user_id")
    private Long unroutedFromUserId;

    @JsonIgnore
    @Column(name = "unrouted_from_name", length = 255)
    private String unroutedFromName;

    @JsonIgnore
    @Column(name = "unrouted_at")
    private LocalDateTime unroutedAt;
}
