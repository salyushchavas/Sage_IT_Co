package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizSubmitRequest {

    /** Per-question answers. Use a list (not a map) so MULTI_SELECT
     *  can carry multiple option ids per question. */
    private List<QuestionAnswer> answers;
    /** Optional — wall-clock seconds spent on the attempt. Used to
     *  display "completed in X minutes" on the results screen. */
    private Integer timeTakenSeconds;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionAnswer {
        private Long questionId;
        private List<Long> selectedOptionIds;
    }
}
