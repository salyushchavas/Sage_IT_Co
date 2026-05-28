package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * One option (answer choice) on a quiz question. Replaces the old
 * fixed optionA/B/C/D columns with a normalized table that supports
 * 2–6 options per question and multi-correct-answer questions
 * (MULTI_SELECT).
 */
@Entity
@Table(
    name = "quiz_options",
    indexes = @Index(name = "idx_quiz_options_question", columnList = "question_id")
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "option_text", nullable = false, columnDefinition = "TEXT")
    private String optionText;

    @Column(name = "is_correct", nullable = false)
    @Builder.Default
    private Boolean isCorrect = false;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;
}
