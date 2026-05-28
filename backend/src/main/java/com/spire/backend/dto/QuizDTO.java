package com.spire.backend.dto;

import com.spire.backend.entity.Quiz;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizDTO {

    private Long id;
    private Long courseId;
    private Long moduleId;
    private Long lessonId;
    private String moduleTitle;
    private String lessonTitle;
    private String title;
    private String description;
    private Integer passThreshold;
    private Integer timeLimitMinutes;
    private Integer maxAttempts;
    private Boolean isActive;
    private Integer orderIndex;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Optional — only set when the caller requests the full questions
     *  list (e.g., student taking the quiz, instructor editing). */
    private List<QuizQuestionDTO> questions;
    /** Optional — count of questions on the quiz. Cheap fetch for
     *  list views that don't want the full nested questions payload. */
    private Integer questionCount;

    /** Optional — student-side: number of attempts they've used and
     *  best score so far. Lets the QuizCard show "0/3 attempts" etc. */
    private Integer attemptCount;
    private Integer bestScorePercent;

    public static QuizDTO summary(Quiz q, int questionCount) {
        return baseBuilder(q).questionCount(questionCount).build();
    }

    public static QuizDTO detail(Quiz q, List<QuizQuestionDTO> questions) {
        return baseBuilder(q)
                .questions(questions)
                .questionCount(questions != null ? questions.size() : 0)
                .build();
    }

    public static QuizDTOBuilder baseBuilder(Quiz q) {
        return QuizDTO.builder()
                .id(q.getId())
                .courseId(q.getCourse() != null ? q.getCourse().getId() : null)
                .moduleId(q.getModule() != null ? q.getModule().getId() : null)
                .lessonId(q.getLesson() != null ? q.getLesson().getId() : null)
                .moduleTitle(q.getModule() != null ? q.getModule().getTitle() : null)
                .lessonTitle(q.getLesson() != null ? q.getLesson().getTitle() : null)
                .title(q.getTitle())
                .description(q.getDescription())
                .passThreshold(q.getPassThreshold())
                .timeLimitMinutes(q.getTimeLimitMinutes())
                .maxAttempts(q.getMaxAttempts())
                .isActive(q.getIsActive())
                .orderIndex(q.getOrderIndex())
                .createdAt(q.getCreatedAt())
                .updatedAt(q.getUpdatedAt());
    }
}
