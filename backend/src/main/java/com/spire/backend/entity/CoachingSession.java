package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Phase 5B — a single coaching session note logged by a coach for one
 * of their assigned participants. Free-form text fields; no rating /
 * outcome enums for v1.
 */
@Entity
@Table(name = "coaching_sessions", indexes = {
        @Index(name = "idx_coach_session_participant", columnList = "participant_user_id"),
        @Index(name = "idx_coach_session_coach", columnList = "coach_user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoachingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_user_id", nullable = false)
    private Long participantUserId;

    @Column(name = "coach_user_id", nullable = false)
    private Long coachUserId;

    @Column(name = "session_date")
    private LocalDate sessionDate;

    @Column(length = 255)
    private String topic;

    /** Free-form session notes. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "next_steps", columnDefinition = "TEXT")
    private String nextSteps;

    /** Minutes the session ran for (optional). */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
