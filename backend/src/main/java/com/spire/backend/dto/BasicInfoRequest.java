package com.spire.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of POST /api/participants/profile/basic-info — the four
 * profile fields that used to live on the public enrollment form
 * and now move to the post-signup "Complete Your Profile" flow.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BasicInfoRequest {

    /** Optional — free-form city / region. */
    private String location;

    @NotBlank(message = "Availability is required")
    private String availability;

    @NotBlank(message = "Technology / skillset is required")
    private String selectedTechnology;

    @NotBlank(message = "Target experience level is required")
    private String targetExperienceLevel;
}
