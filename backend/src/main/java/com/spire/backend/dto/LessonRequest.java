package com.spire.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LessonRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 200)
    private String title;

    private String description;
    private String videoUrl;
    private Integer orderIndex;
    private Integer durationMinutes;
    private Boolean isFree;
    /**
     * Optional. When set, attaches the lesson to this module. The
     * content manager always sends it; the older flat-list editor
     * may not, in which case the lesson stays as an "Other Lesson".
     */
    private Long moduleId;
}
