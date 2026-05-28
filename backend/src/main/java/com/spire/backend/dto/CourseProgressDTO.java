package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Per-course progress detail with module-level breakdown.
 * Powers the course-detail "Your Progress" header, module
 * collapsible counts, and per-lesson check / play / empty icons.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseProgressDTO {

    private Long courseId;
    private Long enrollmentId;
    private int totalLessons;
    private int completedLessons;
    private int progressPercent;

    private List<ModuleProgress> modules;
    private List<LessonProgress> orphanLessons;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ModuleProgress {
        private Long moduleId;
        private String moduleTitle;
        private int orderIndex;
        private int totalLessons;
        private int completedLessons;
        private int progressPercent;
        private List<LessonProgress> lessons;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LessonProgress {
        private Long lessonId;
        private String title;
        private int orderIndex;
        private boolean completed;
        private int videoPositionSec; // for resume-where-you-left-off
    }
}
