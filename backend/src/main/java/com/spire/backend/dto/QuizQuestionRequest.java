package com.spire.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizQuestionRequest {

    @NotBlank
    private String questionText;

    /** MULTIPLE_CHOICE | TRUE_FALSE | MULTI_SELECT. Defaults to
     *  MULTIPLE_CHOICE when null. */
    private String questionType;

    private Integer points;
    private String explanation;

    /** Required. Validation rules:
     *  - MULTIPLE_CHOICE: 2–6 options, exactly 1 correct
     *  - TRUE_FALSE:     exactly 2 options
     *  - MULTI_SELECT:   2–6 options, ≥1 correct
     */
    private List<OptionRequest> options;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionRequest {
        private String optionText;
        private Boolean isCorrect;
    }
}
