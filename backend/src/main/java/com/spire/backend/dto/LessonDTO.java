package com.spire.backend.dto;

import com.spire.backend.entity.Lesson;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LessonDTO {

    private Long id;
    private Long courseId;
    private Long moduleId;
    private String title;
    private String description;
    private String videoUrl;
    private Integer orderIndex;
    private Integer durationMinutes;
    private Boolean isFree;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static LessonDTO from(Lesson lesson, boolean includeVideoUrl) {
        return LessonDTO.builder()
                .id(lesson.getId())
                .courseId(lesson.getCourse().getId())
                .moduleId(lesson.getModule() != null ? lesson.getModule().getId() : null)
                .title(lesson.getTitle())
                .description(lesson.getDescription())
                .videoUrl(includeVideoUrl ? lesson.getVideoUrl() : null)
                .orderIndex(lesson.getOrderIndex())
                .durationMinutes(lesson.getDurationMinutes())
                .isFree(lesson.getIsFree())
                .createdAt(lesson.getCreatedAt())
                .updatedAt(lesson.getUpdatedAt())
                .build();
    }
}
