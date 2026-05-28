package com.spire.backend.dto;

import com.spire.backend.entity.Enrollment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InstructorStudentDTO {
    private String studentName;
    private String email;
    private String courseTitle;
    private LocalDateTime enrolledAt;

    public static InstructorStudentDTO from(Enrollment enrollment) {
        return InstructorStudentDTO.builder()
                .studentName(enrollment.getUser().getFullName())
                .email(enrollment.getUser().getEmail())
                .courseTitle(enrollment.getCourse().getTitle())
                .enrolledAt(enrollment.getEnrolledAt())
                .build();
    }
}
