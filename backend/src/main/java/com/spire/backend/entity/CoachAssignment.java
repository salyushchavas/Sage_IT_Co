package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Pairs a participant with one of their coaches. A participant can
 * have multiple active coaches (one per role: COACH, TECHNICAL_ADVISOR,
 * …) so the relationship is many-to-one user-side.
 */
@Entity
@Table(name = "coach_assignments", indexes = {
        @Index(name = "idx_coach_user_id", columnList = "user_id"),
        @Index(name = "idx_coach_coach_user_id", columnList = "coach_user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoachAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "coach_user_id")
    private Long coachUserId;

    /** COACH, TECHNICAL_ADVISOR, ERM. */
    @Column(name = "coach_role", length = 50)
    private String coachRole;

    @CreationTimestamp
    @Column(name = "assigned_date", updatable = false)
    private LocalDateTime assignedDate;

    @Column(name = "first_checkpoint")
    private LocalDate firstCheckpoint;

    /** ACTIVE, ENDED, PAUSED. */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "ACTIVE";
}
