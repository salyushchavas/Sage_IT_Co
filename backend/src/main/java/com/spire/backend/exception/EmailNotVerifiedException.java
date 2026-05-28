package com.spire.backend.exception;

import lombok.Getter;

/**
 * Thrown by AuthService.login when the user's email hasn't been
 * verified yet. The email address is captured so the controller
 * can return a structured 403 payload that the frontend uses to
 * route the user to /verify-email?email=… without a second lookup.
 *
 * Distinct from {@link UnauthorizedException} (which is for bad
 * credentials) so handlers / clients can branch on the cause.
 */
@Getter
public class EmailNotVerifiedException extends RuntimeException {

    private final String email;

    public EmailNotVerifiedException(String email) {
        super("Email not verified");
        this.email = email;
    }
}
