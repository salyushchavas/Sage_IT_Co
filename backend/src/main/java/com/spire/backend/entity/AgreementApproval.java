package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 3B — one approver's gate on a consultant agreement. The ERM "Send for
 * Approval" creates one PENDING row per required approver for the
 * agreement's current coaching {@code phase}:
 *   Phase 1 = {MANAGER}; Phase 2 = {MANAGER, ACCOUNTS} (parallel).
 *
 * Each approver previews the consultant-signed agreement and either
 * APPROVES or REQUESTS_REVISION (a note returns the agreement to the ERM,
 * NOT the consultant). When ALL required rows for the active round are
 * APPROVED the application transitions to READY_TO_SIGN; any
 * REQUESTS_REVISION flips it to APPROVAL_REVISION_REQUESTED.
 *
 * Re-send (ERM addresses the note) bumps {@code round} and writes a fresh
 * PENDING row per required approver, so the full decision history per
 * round is preserved for the Certificate of Completion + audit.
 *
 * The table is created by Hibernate ddl-auto=update; DataSeeder ensures
 * the helpful indexes idempotently.
 */
@Entity
@Table(name = "agreement_approvals", indexes = {
        @Index(name = "idx_agr_approval_app", columnList = "application_id"),
        @Index(name = "idx_agr_approval_active",
                columnList = "application_id, round, role"),
        @Index(name = "idx_agr_approval_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgreementApproval {

    /** The two approver gates. Maps 1:1 to the MANAGER / ACCOUNTS roles. */
    public enum ApproverRole {
        MANAGER,
        ACCOUNTS
    }

    /** Per-approver decision state. */
    public enum Decision {
        PENDING,
        APPROVED,
        REVISION_REQUESTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@link ConsultantApplication#getId()} (numeric, not the UUID). */
    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private ApproverRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Builder.Default
    private Decision status = Decision.PENDING;

    /** Approver's note (required when requesting a revision; optional on approve). */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** Coaching phase (1/2) this approval round belongs to. */
    @Column(name = "phase", nullable = false)
    @Builder.Default
    private Integer phase = 1;

    /** Re-send round (1-based); bumped each time the ERM re-sends after a revision. */
    @Column(name = "round", nullable = false)
    @Builder.Default
    private Integer round = 1;

    /** AgreementUser id of the approver who decided (null while PENDING). */
    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    /** Denormalised approver display name, captured at decision time. */
    @Column(name = "decided_by_name", length = 255)
    private String decidedByName;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Real client IP (X-Forwarded-For first hop) at decision time. */
    @Column(name = "decided_ip", length = 64)
    private String decidedIp;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
