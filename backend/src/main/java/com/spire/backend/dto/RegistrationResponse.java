package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Returned by POST /api/auth/register. Deliberately does NOT carry
 * a JWT — under the OTP gate the user must verify their email
 * before any access tokens are issued. The frontend reads
 * {@code requiresVerification} and routes to /verify-email.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistrationResponse {
    private Long userId;
    private String email;
    @Builder.Default
    private boolean requiresVerification = true;
}
