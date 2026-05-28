package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Quiz question. Migrated in-place from a fixed 4-option model
 * (optionA/B/C/D + correctAnswer string) to a normalized
 * {@link QuizOption} relation that supports MULTIPLE_CHOICE,
 * TRUE_FALSE, and MULTI_SELECT question types with 2–6 options.
 *
 * The legacy A/B/C/D + correctAnswer columns are kept (now
 * nullable) so ddl-auto=update doesn't try to drop them on
 * existing DBs. New questions don't populate them; reads ignore
 * them.
 */
@Entity
@Table(name = "questions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", length = 20, nullable = false)
    @Builder.Default
    private QuestionType questionType = QuestionType.MULTIPLE_CHOICE;

    @Column(nullable = false)
    @Builder.Default
    private Integer points = 1;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<QuizOption> options = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // ─── Legacy columns (pre-migration, kept nullable) ───────────────
    // Old quizzes used 4 fixed options (A/B/C/D) plus a single-letter
    // correctAnswer. Nullable so new questions don't have to populate
    // them. Don't touch these from new code — they exist only to keep
    // ddl-auto=update from trying to drop columns on legacy databases.

    @Column(name = "option_a")
    private String optionA;

    @Column(name = "option_b")
    private String optionB;

    @Column(name = "option_c")
    private String optionC;

    @Column(name = "option_d")
    private String optionD;

    @Column(name = "correct_answer", length = 1)
    private String correctAnswer;

    public enum QuestionType {
        MULTIPLE_CHOICE,
        TRUE_FALSE,
        MULTI_SELECT
    }
}
