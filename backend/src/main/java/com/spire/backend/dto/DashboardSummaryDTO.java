package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Supplementary data for the sections below the next-action hero.
 * Kept lean — only what the dashboard actually renders today.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardSummaryDTO {

    private List<CourseProgress> enrolledCourses;
    private List<UpcomingSession> upcomingSessions;
    private List<Activity> recentActivity;
    private Integer streakDays;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CourseProgress {
        private Long id;
        private String title;
        private String type;             // COURSE | SERVICE
        private Integer progressPercent; // 0..100
        private Integer completedLessons;
        private Integer totalLessons;
        private LocalDateTime lastAccessedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpcomingSession {
        private Long sessionId;
        private String courseTitle;
        private String mentorName;
        private LocalDateTime scheduledAt;
        private String meetingUrl;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Activity {
        private String type;        // e.g. LESSON_COMPLETED
        private String description;
        private LocalDateTime timestamp;
    }
}
