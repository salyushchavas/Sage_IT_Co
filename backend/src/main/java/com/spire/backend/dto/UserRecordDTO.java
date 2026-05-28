package com.spire.backend.dto;

import com.spire.backend.entity.UserRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRecordDTO {

    private Long id;
    private Long userId;
    private String recordType;
    private String category;
    private String title;
    private String description;
    private String details;
    private String ipAddress;
    private String deviceType;
    private String browser;
    private String os;
    private String city;
    private LocalDateTime createdAt;

    public static UserRecordDTO from(UserRecord r) {
        return UserRecordDTO.builder()
                .id(r.getId())
                .userId(r.getUserId())
                .recordType(r.getRecordType())
                .category(r.getCategory())
                .title(r.getTitle())
                .description(r.getDescription())
                .details(r.getDetails())
                .ipAddress(r.getIpAddress())
                .deviceType(r.getDeviceType())
                .browser(r.getBrowser())
                .os(r.getOs())
                .city(r.getCity())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
