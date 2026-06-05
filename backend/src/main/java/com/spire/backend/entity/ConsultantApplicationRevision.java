package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Frozen snapshot of a {@link ConsultantApplication}'s payload at a
 * given revision. Lets the ERM detail page show "what changed when"
 * and proves the consultant signed a specific version of the form.
 *
 * v1 is written at CREATED; subsequent versions are written each time
 * the ERM saves an UPDATE (after a REVISION_REQUESTED off-ramp).
 */
@Entity
@Table(name = "consultant_application_revisions", indexes = {
        @Index(name = "idx_consultant_rev_app_id", columnList = "application_id"),
        @Index(name = "idx_consultant_rev_version",
                columnList = "application_id, version_number")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsultantApplicationRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "payload_snapshot", columnDefinition = "TEXT")
    private String payloadSnapshot;

    /** "ERM" or "CONSULTANT". Tracks which side authored this revision. */
    @Column(name = "created_by_role", nullable = false, length = 16)
    private String createdByRole;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
