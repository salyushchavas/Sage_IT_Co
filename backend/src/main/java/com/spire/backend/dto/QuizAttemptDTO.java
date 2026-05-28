package com.spire.backend.dto;

import com.spire.backend.entity.QuizAttempt;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizAttemptDTO {

    private Long id;
    private Long quizId;
    private BigDecimal scorePercent;
    private Boolean passed;
    private Integer attemptNumber;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer timeTakenSeconds;

    public static QuizAttemptDTO from(QuizAttempt a) {
        // Fall back to the legacy `percentage` int when the new
        // scorePercent column is null (pre-migration rows).
        BigDecimal score = a.getScorePercent();
        if (score == null && a.getPercentage() != null) {
            score = BigDecimal.valueOf(a.getPercentage());
        }
        LocalDateTime completed = a.getCompletedAt();
        if (completed == null) completed = a.getAttemptedAt();
        return QuizAttemptDTO.builder()
                .id(a.getId())
                .quizId(a.getQuiz() != null ? a.getQuiz().getId() : null)
                .scorePercent(score)
                .passed(a.getPassed())
                .attemptNumber(a.getAttemptNumber())
                .startedAt(a.getStartedAt())
                .completedAt(completed)
                .timeTakenSeconds(a.getTimeTakenSeconds())
                .build();
    }
}
