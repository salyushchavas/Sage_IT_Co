package com.spire.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dedicated DTO for profile updates.
 * Only allows fullName, avatarUrl, bio, phone, location — NO role,
 * id, or email. Prevents mass assignment / privilege escalation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {

    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    private String fullName;

    @Size(max = 500, message = "Avatar URL too long")
    private String avatarUrl;

    @Size(max = 500, message = "Bio must be 500 characters or less")
    private String bio;

    @Size(max = 20, message = "Phone too long")
    private String phone;

    @Size(max = 255, message = "Location too long")
    private String location;
}
