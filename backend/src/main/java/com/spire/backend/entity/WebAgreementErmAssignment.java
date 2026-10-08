package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A System-Admin-set link from a website ERM to one of their approvers,
 * the website copy of the console's {@link AgreementErmAssignment}.
 * Many-to-many: an ERM can have several MANAGER and several ACCOUNTS
 * approvers, and an approver can serve several ERMs. When the ERM sends
 * an agreement for approval, the pickers offer only these links.
 *
 * {@code role} is MANAGER or ACCOUNTS (plain string,
 * {@code WebAgreementApproval.ApproverRole.X.name()}). User ids are
 * website users.id.
 *
 * Rows are never deleted (the console deletes them): a link is active
 * while {@code removedAt} is null; removing it stamps removedAt/removedBy,
 * and adding the same link again re-activates the same row (the unique
 * key covers removed rows too). Every read uses active rows only.
 */
@Entity
@Table(name = "web_agreement_erm_assignments",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_web_agreement_team_link",
                columnNames = {"erm_user_id", "approver_user_id", "role"}),
        indexes = {
                @Index(name = "idx_web_agreement_team_erm", columnList = "erm_user_id, role"),
                @Index(name = "idx_web_agreement_team_approver", columnList = "approver_user_id, role")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebAgreementErmAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** users.id of the ERM whose team this is. */
    @Column(name = "erm_user_id", nullable = false)
    private Long ermUserId;

    /** users.id of the assigned MANAGER / ACCOUNTS approver. */
    @Column(name = "approver_user_id", nullable = false)
    private Long approverUserId;

    /** MANAGER or ACCOUNTS: the approver's role for this link. */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** users.id of the System Admin who added the link. */
    @Column(name = "created_by")
    private Long createdBy;

    /** Set when the link is removed; null = active. */
    @Column(name = "removed_at")
    private LocalDateTime removedAt;

    /** users.id of whoever removed it (a team edit, a role change or a delete). */
    @Column(name = "removed_by")
    private Long removedBy;
}
