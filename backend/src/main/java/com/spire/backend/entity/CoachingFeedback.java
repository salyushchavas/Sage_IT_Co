package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Phase 5B — qualitative feedback a coach gives a participant.
 *
 * {@code feedbackType} is one of {@code SESSION}, {@code RESUME},
 * {@code TECHNICAL}, {@code INTERVIEW}, {@code GENERAL}; the
 * dashboard groups feedback by type when displaying it.
 */
@Entity
@Table(name = "coaching_feedback", indexes = {
        @Index(name = "idx_coach_fb_participant", columnList = "participant_user_id"),
        @Index(name = "idx_coach_fb_coach", columnList = "coach_user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoachingFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_user_id", nullable = false)
    private Long participantUserId;

    @Column(name = "coach_user_id", nullable = false)
    private Long coachUserId;

    /** SESSION, RESUME, TECHNICAL, INTERVIEW, GENERAL. */
    @Column(name = "feedback_type", length = 30)
    @Builder.Default
    private String feedbackType = "GENERAL";

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 1-5; optional. */
    @Column
    private Integer rating;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
