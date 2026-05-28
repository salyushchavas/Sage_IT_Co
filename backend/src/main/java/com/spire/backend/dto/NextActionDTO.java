package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * "One Next Action" — the single most important thing the student
 * should do right now (PRODUCT.md). The `type` field is the
 * discriminator; per-type fields are populated as a flat union.
 *
 * Types (in priority order):
 *   SESSION_SOON     — accepted session within 24h
 *   ASSIGNMENT_DUE   — assignment due within 3 days, not submitted
 *   CONTINUE_COURSE  — in-progress course, resume next lesson
 *   START_COURSE     — enrolled but no lessons completed
 *   BROWSE_COURSES   — no enrollments
 *   ALL_COMPLETE     — every enrolled course is finished
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NextActionDTO {
    private String type;

    // Course context — used by SESSION_SOON, ASSIGNMENT_DUE, CONTINUE_COURSE, START_COURSE
    private Long courseId;
    private String courseTitle;

    // SESSION_SOON
    private String mentorName;
    private LocalDateTime scheduledAt;
    private String meetingUrl;

    // ASSIGNMENT_DUE
    private Long assignmentId;
    private String assignmentTitle;
    private LocalDateTime dueDate;

    // CONTINUE_COURSE
    private Long nextLessonId;
    private String nextLessonTitle;
    private String moduleTitle;
    private Integer progressPercent;

    // START_COURSE — separate field names per task spec, even though the
    // logic is the same as CONTINUE_COURSE's nextLesson.
    private Long firstLessonId;
    private String firstLessonTitle;

    // ALL_COMPLETE
    private Integer completedCount;
    private Integer certificateCount;
}
