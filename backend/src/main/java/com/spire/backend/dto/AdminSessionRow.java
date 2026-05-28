package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One row of the admin Sessions tab — flattened SessionRequest with
 * student, mentor, and course resolved.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminSessionRow {
    private Long sessionId;
    private String studentName;
    private String studentEmail;
    private String mentorName;
    private String courseTitle;
    private String status;             // PENDING | ACCEPTED | COMPLETED | CANCELLED
    private String topic;
    private LocalDateTime requestedAt;
    private LocalDateTime scheduledAt;
    private LocalDateTime completedAt;
    private String meetingUrl;
}
