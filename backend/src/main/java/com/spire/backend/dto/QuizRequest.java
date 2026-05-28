package com.spire.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizRequest {

    @NotBlank(message = "Quiz title is required")
    private String title;

    private String description;

    /** Required for create. Optional on update — service ignores. */
    private Long courseId;
    /** Optional — exactly one of moduleId / lessonId may be set
     *  (or both null = course-final assessment). */
    private Long moduleId;
    private Long lessonId;

    private Integer passThreshold;
    private Integer timeLimitMinutes;
    private Integer maxAttempts;
    private Boolean isActive;
    private Integer orderIndex;
}
