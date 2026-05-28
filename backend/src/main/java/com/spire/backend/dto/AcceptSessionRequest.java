package com.spire.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class AcceptSessionRequest {

    @NotBlank(message = "scheduledAt is required (ISO datetime, e.g. 2026-04-30T14:00:00)")
    private String scheduledAt;

    @NotBlank(message = "meetingUrl is required")
    private String meetingUrl;
}
