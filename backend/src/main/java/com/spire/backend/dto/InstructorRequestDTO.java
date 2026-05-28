package com.spire.backend.dto;

import com.spire.backend.entity.InstructorRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InstructorRequestDTO {

    private Long id;
    private Long userId;
    private String userEmail;
    private String userFullName;
    private String status;
    private LocalDateTime createdAt;

    public static InstructorRequestDTO from(InstructorRequest request) {
        return InstructorRequestDTO.builder()
                .id(request.getId())
                .userId(request.getUser().getId())
                .userEmail(request.getUser().getEmail())
                .userFullName(request.getUser().getFullName())
                .status(request.getStatus().name())
                .createdAt(request.getCreatedAt())
                .build();
    }
}
