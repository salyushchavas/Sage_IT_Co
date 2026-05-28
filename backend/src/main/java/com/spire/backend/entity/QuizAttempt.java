package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One quiz attempt by a student. Pre-migration this had a unique
 * constraint on (quiz_id, user_id) which forbade retries — see
 * DataSeeder.dropLegacyQuizAttemptUniqueConstraint() which removes
 * it on existing databases. Hibernate ddl-auto=update doesn't drop
 * constraints automatically.
 *
 * Legacy fields {@code score}, {@code totalQuestions}, {@code percentage}
 * are kept for backwards-compat — old code path doesn't break — but
 * new code uses {@code scorePercent} (decimal), {@code passed}, and
 * the per-attempt {@link QuizAnswer} rows for grading detail.
 */
@Entity
@Table(name = "quiz_attempts", indexes = {
    @Index(name = "idx_quiz_attempts_quiz_user", columnList = "quiz_id, user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** New canonical score field — decimal so we can show 66.67%. */
    @Column(name = "score_percent", precision = 5, scale = 2)
    private BigDecimal scorePercent;

    @Column
    private Boolean passed;

    @Column(name = "attempt_number")
    private Integer attemptNumber;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "time_taken_seconds")
    private Integer timeTakenSeconds;

    // ─── Legacy fields (pre-migration) ───────────────────────────────
    // Old quiz attempts captured score as int + totalQuestions + an
    // int percentage. Kept nullable so legacy-row reads don't break,
    // but new attempts only populate scorePercent / passed / etc.
    @Column
    private Integer score;

    @Column(name = "total_questions")
    private Integer totalQuestions;

    @Column
    private Integer percentage;

    @CreationTimestamp
    @Column(name = "attempted_at", updatable = false)
    private LocalDateTime attemptedAt;
}
