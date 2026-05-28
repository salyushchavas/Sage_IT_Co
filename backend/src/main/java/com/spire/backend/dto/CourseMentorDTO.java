package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseMentorDTO {

    private Long id;
    private Long courseId;
    private Long mentorId;
    private String mentorName;
    private String mentorEmail;
    private Integer activeStudentCount;
    private Integer maxStudents;
    private Boolean isActive;
}
