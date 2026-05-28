package com.spire.backend.exception;

/**
 * Raised by the {@code AgreementGateFilter} when an authenticated
 * user with {@code agreementAccepted=false} hits a protected
 * endpoint. The {@link com.spire.backend.exception.GlobalExceptionHandler}
 * maps this to a structured 403 the frontend uses to redirect to
 * /agreement.
 */
public class AgreementRequiredException extends RuntimeException {
    public AgreementRequiredException() {
        super("AGREEMENT_REQUIRED");
    }
}
