package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizSubmitResult {

    private Long attemptId;
    private BigDecimal scorePercent;
    private Boolean passed;
    private Integer passThreshold;
    private Integer attemptNumber;
    private Integer attemptsRemaining;
    private Integer totalQuestions;
    private Integer correctCount;
    private Integer timeTakenSeconds;
    /** Per-question breakdown — caller learns correctness, the correct
     *  option ids, and the explanation in one round trip. */
    private List<QuestionResult> results;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QuestionResult {
        private Long questionId;
        private Boolean correct;
        private List<Long> selectedOptionIds;
        private List<Long> correctOptionIds;
        private String explanation;
    }
}
