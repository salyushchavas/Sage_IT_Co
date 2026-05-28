package com.spire.backend.dto;

import com.spire.backend.entity.Announcement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnouncementDTO {

    private Long id;
    private String title;
    private String message;
    private String type;
    private Boolean isActive;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private String createdByName;

    public static AnnouncementDTO from(Announcement a) {
        return AnnouncementDTO.builder()
                .id(a.getId())
                .title(a.getTitle())
                .message(a.getMessage())
                .type(a.getType() != null ? a.getType().name() : "INFO")
                .isActive(Boolean.TRUE.equals(a.getIsActive()))
                .expiresAt(a.getExpiresAt())
                .createdAt(a.getCreatedAt())
                .createdByName(a.getCreatedBy() != null ? a.getCreatedBy().getFullName() : null)
                .build();
    }
}
