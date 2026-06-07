package com.spire.backend.dto;

import com.spire.backend.entity.AgreementUser;

import java.time.LocalDateTime;

/**
 * Safe projection of {@link AgreementUser} for the admin console.
 * NEVER carries the password hash.
 */
public record AgreementUserDto(
        String id,
        String email,
        String fullName,
        String title,
        String role,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt
) {
    public static AgreementUserDto from(AgreementUser u) {
        return new AgreementUserDto(
                u.getId(),
                u.getEmail(),
                u.getFullName(),
                u.getTitle(),
                u.getRole() == null ? null : u.getRole().name(),
                u.isActive(),
                u.getCreatedAt(),
                u.getLastLoginAt());
    }
}
