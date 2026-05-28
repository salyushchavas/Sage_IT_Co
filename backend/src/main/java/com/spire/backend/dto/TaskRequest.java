package com.spire.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskRequest {
    @NotBlank(message = "Title is required")
    private String title;
    private String description;
    private String instruction;
    private String type;  // PRACTICE, RESEARCH, REFLECTION, CHALLENGE
    private Integer orderIndex;
}
