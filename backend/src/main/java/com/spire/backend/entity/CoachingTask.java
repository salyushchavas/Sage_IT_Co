package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Phase 5B — a practice task a coach assigns to one of their
 * participants. Status is free-text but the dashboard rotates
 * "OPEN" → "DONE" via the participant-side check-off.
 */
@Entity
@Table(name = "coaching_tasks", indexes = {
        @Index(name = "idx_coach_task_participant", columnList = "participant_user_id"),
        @Index(name = "idx_coach_task_coach", columnList = "coach_user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoachingTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_user_id", nullable = false)
    private Long participantUserId;

    @Column(name = "coach_user_id", nullable = false)
    private Long coachUserId;

    @Column(length = 255, nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** OPEN, DONE, CANCELLED. */
    @Column(length = 20)
    @Builder.Default
    private String status = "OPEN";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
