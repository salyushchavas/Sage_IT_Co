package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionRequestDTO {

    private Long id;
    private Long enrollmentId;
    private String courseTitle;
    private String studentName;
    private String studentEmail;
    private String mentorName;
    private String mentorEmail;
    private String status;
    private String topic;
    private LocalDateTime requestedAt;
    private LocalDateTime scheduledAt;
    private String meetingUrl;
    private String notes;
    private LocalDateTime completedAt;
}
