package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Append-only audit row written by {@code WorkflowService.transition}
 * for every state change in the 26-status participant lifecycle.
 *
 * One row per transition. {@code fromStatus} is null on the very
 * first row (DRAFT_STARTED has no predecessor). {@code triggerEvent}
 * names the high-level cause ("EMAIL_VERIFIED", "ACK_ACCEPTED",
 * "AGREEMENT_COMPLETED", …) so an admin can reconstruct the chain
 * without joining against {@code user_records}.
 */
@Entity
@Table(name = "workflow_states", indexes = {
        @Index(name = "idx_workflow_user_id", columnList = "user_id"),
        @Index(name = "idx_workflow_to_status", columnList = "to_status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "from_status", length = 50)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 50)
    private String toStatus;

    @Column(name = "trigger_event", length = 100)
    private String triggerEvent;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
