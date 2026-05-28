package com.spire.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 200)
    private String title;

    private String description;
    private LocalDateTime dueDate;

    private String assignmentType;  // "LESSON" or "COURSE" (defaults to COURSE)
    private Long lessonId;          // required when assignmentType = LESSON
}
