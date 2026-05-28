package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One row of the admin Enrollments tab — joins user + course +
 * mentor-assignment + progress in a single flat shape.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminEnrollmentRow {
    private Long enrollmentId;
    private Long userId;
    private String studentName;
    private String studentEmail;
    private Long courseId;
    private String courseTitle;
    private String courseType;        // COURSE | SERVICE
    private LocalDateTime enrolledAt;
    private Integer progressPercent;
    private Integer completedLessons;
    private Integer totalLessons;
    private Boolean completed;
    private String mentorName;        // null for services / pending assignments
    private String mentorAssignmentStatus;  // ACTIVE | PENDING_ASSIGNMENT | null
}
