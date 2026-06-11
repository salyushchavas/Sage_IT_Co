package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Build K — a super-admin-configured link from an ERM to one of their
 * approvers. Many-to-many: an ERM can have several MANAGER and several
 * ACCOUNTS approvers, and a MANAGER/ACCOUNTS user can serve several ERMs.
 *
 * The {@code role} is always MANAGER or ACCOUNTS (it mirrors the assigned
 * approver's own console role). When the ERM sends an agreement for
 * approval, the manager/accounts picker is populated strictly from these
 * rows, and the chosen approver is bound to the approval round.
 *
 * The table is created by Hibernate ddl-auto=update; DataSeeder ensures the
 * unique index idempotently.
 */
@Entity
@Table(name = "agreement_erm_assignments",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_erm_assignment",
                columnNames = {"erm_user_id", "approver_user_id", "role"}),
        indexes = {
                @Index(name = "idx_erm_assignment_erm", columnList = "erm_user_id, role"),
                @Index(name = "idx_erm_assignment_approver", columnList = "approver_user_id, role")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgreementErmAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** AgreementUser id of the ERM who owns this assignment. */
    @Column(name = "erm_user_id", nullable = false, length = 36)
    private String ermUserId;

    /** AgreementUser id of the assigned MANAGER / ACCOUNTS approver. */
    @Column(name = "approver_user_id", nullable = false, length = 36)
    private String approverUserId;

    /** The approver's role for this assignment (MANAGER or ACCOUNTS). */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private AgreementUserRole role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
