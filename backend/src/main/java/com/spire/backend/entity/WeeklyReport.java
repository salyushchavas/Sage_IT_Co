package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Weekly progress submission a participant files during the active
 * phase of their program. Reviewed by the assigned ERM. {@code
 * reportData} stores the structured answers as JSON so the form
 * schema can evolve without schema migrations.
 */
@Entity
@Table(name = "weekly_reports", indexes = {
        @Index(name = "idx_weekly_user_id", columnList = "user_id"),
        @Index(name = "idx_weekly_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeeklyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "week_start")
    private LocalDate weekStart;

    @Column(name = "week_end")
    private LocalDate weekEnd;

    @Column(name = "submission_due_date")
    private LocalDate submissionDueDate;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "erm_review_date")
    private LocalDateTime ermReviewDate;

    @Column(name = "erm_notes", columnDefinition = "TEXT")
    private String ermNotes;

    @Column(name = "report_data", columnDefinition = "TEXT")
    private String reportData;

    /** PENDING, SUBMITTED, REVIEWED, OVERDUE. */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "PENDING";
}
