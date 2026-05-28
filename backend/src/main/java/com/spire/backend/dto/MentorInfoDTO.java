package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MentorInfoDTO {

    private Long enrollmentId;
    private Long assignmentId;
    private String mentorName;
    private String mentorEmail;
    private String status;
}
