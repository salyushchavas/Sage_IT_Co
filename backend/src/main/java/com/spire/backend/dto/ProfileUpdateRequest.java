package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of PUT /api/participants/profile.
 *
 * Editable fields only — email, role, participantId, and currentStatus
 * are intentionally not on this DTO because participants must never
 * be able to mutate them from the dashboard. Empty / null fields are
 * treated as "no change" on the server.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProfileUpdateRequest {
    private String fullName;
    private String phone;
    private String location;
    private String bio;
    private String availability;
}
