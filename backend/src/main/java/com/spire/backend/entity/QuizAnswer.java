package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Records a student's response to a single question within a quiz
 * attempt. Stores the chosen options as a JSON-encoded array of
 * IDs (TEXT — works on both MySQL dev and Postgres prod without
 * a JSON column type) plus the per-question correctness flag so
 * results can be replayed without re-grading against the option
 * table later.
 */
@Entity
@Table(
    name = "quiz_answers",
    indexes = {
        @Index(name = "idx_quiz_answers_attempt", columnList = "attempt_id"),
        @Index(name = "idx_quiz_answers_question", columnList = "question_id")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id", nullable = false)
    private QuizAttempt attempt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    /** JSON array of selected option ids, e.g. "[3,5]". */
    @Column(name = "selected_option_ids", nullable = false, columnDefinition = "TEXT")
    private String selectedOptionIds;

    @Column(name = "is_correct", nullable = false)
    private Boolean isCorrect;
}
