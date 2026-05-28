package com.spire.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of POST /api/participants/enroll. Quick-signup payload — just
 * the four fields needed to create an account and dispatch the OTP.
 * Location, availability, technology, and target experience level
 * used to live here but moved to the progressive
 * {@code /profile/basic-info} endpoint so the public enrollment form
 * fits on one screen with no overwhelm.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParticipantEnrollRequest {

    @NotBlank(message = "Full legal name is required")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Phone is required")
    private String phone;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;
}
