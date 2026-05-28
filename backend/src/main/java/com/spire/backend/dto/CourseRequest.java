package com.spire.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Input DTO for creating/updating courses.
 * Only safe fields — no id, no instructor, no stats.
 * Instructor is always set from the authenticated user.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CourseRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title must be under 200 characters")
    private String title;

    private String description;

    @Size(max = 500)
    private String shortDescription;

    private String level;           // BEGINNER, INTERMEDIATE, ADVANCED
    private BigDecimal price;
    private Boolean isFree;
    private Double durationHours;
    private String thumbnailUrl;
    private String category;
    private String tags;
    private Boolean isPublished;

    // Step 5: services share the courses table. type=COURSE (default) or SERVICE.
    // trainerId only used when type=SERVICE.
    private String type;
    private Long trainerId;
}
