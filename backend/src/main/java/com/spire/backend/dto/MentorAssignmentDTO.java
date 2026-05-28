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
public class MentorAssignmentDTO {

    private Long id;
    private Long enrollmentId;
    private String studentName;
    private String studentEmail;
    private String courseTitle;
    private String mentorName;
    private String mentorEmail;
    private LocalDateTime assignedAt;
    private String status;
}
