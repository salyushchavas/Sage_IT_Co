package com.spire.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuestionRequest {
    @NotBlank(message = "Question text is required")
    private String questionText;

    @NotBlank private String optionA;
    @NotBlank private String optionB;
    private String optionC;
    private String optionD;

    @NotBlank
    @Pattern(regexp = "[A-D]", message = "Correct answer must be A, B, C, or D")
    private String correctAnswer;
}
