package com.spire.backend.exception;

import com.spire.backend.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // 404 — Resource not found
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // 403 — Email not verified. Returns a structured payload so the
    // frontend can route the user to /verify-email?email=… without
    // having to parse the message string. The shape is
    // { success: false, message: "EMAIL_NOT_VERIFIED",
    //   data: { email: "..." } } which the login page reads.
    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleEmailNotVerified(EmailNotVerifiedException ex) {
        Map<String, String> data = new HashMap<>();
        data.put("email", ex.getEmail());
        ApiResponse<Map<String, String>> body = ApiResponse.<Map<String, String>>builder()
                .success(false)
                .message("EMAIL_NOT_VERIFIED")
                .data(data)
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    // 403 — Agreement not accepted. Frontend's apiFetch interceptor
    // checks for { message: "AGREEMENT_REQUIRED" } and routes the
    // user to /agreement. The error field on the body keeps the
    // shape compatible with code that reads either spelling.
    @ExceptionHandler(AgreementRequiredException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleAgreementRequired(AgreementRequiredException ex) {
        Map<String, String> data = new HashMap<>();
        data.put("error", "AGREEMENT_REQUIRED");
        ApiResponse<Map<String, String>> body = ApiResponse.<Map<String, String>>builder()
                .success(false)
                .message("AGREEMENT_REQUIRED")
                .data(data)
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    // 401 — Authentication failed
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException ex) {
        log.warn("Unauthorized: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // 403 — Access denied (role-based)
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Access denied. You don't have permission for this action."));
    }

    // 400 — Bad request (business logic validation)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // 400 — Validation errors (@Valid)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = ((FieldError) error).getField();
            String message = error.getDefaultMessage();
            errors.put(field, message);
        });
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Map<String, String>>builder()
                        .success(false)
                        .message("Validation failed")
                        .data(errors)
                        .build());
    }

    // 409 — State-precondition failure. Every IllegalStateException
    // throw site in the codebase is a "wrong state for this action"
    // guard (consultant-agreement state machine, payment-plan gates,
    // etc.). 500 was the original mapping but it's semantically a
    // 409 Conflict.
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("State precondition failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // 409 / 400 — Database constraint violation. The mostSpecificCause
    // message is dialect-specific and not user-friendly; we classify
    // it into a few buckets and return a message that hints at WHAT
    // went wrong (duplicate vs. missing field vs. bad reference)
    // instead of a generic "Data conflict". The full cause is logged
    // server-side so the real diagnostic is in Railway logs.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        String causeMsg = ex.getMostSpecificCause().getMessage();
        log.error("Data integrity violation: {}", causeMsg);
        String lower = causeMsg == null ? "" : causeMsg.toLowerCase();

        // Duplicate / unique-constraint violation — return 409.
        if (lower.contains("duplicate") || lower.contains("unique constraint") || lower.contains("violates unique")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("A record with the same identifier already exists."));
        }

        // NOT NULL violation — really a 400 (client sent a row missing
        // a required column). We return 400 so the frontend doesn't
        // misinterpret it as "duplicate exists".
        if (lower.contains("not-null") || lower.contains("not null") || lower.contains("null value")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("A required field was missing. Please complete all required values and try again."));
        }

        // Foreign-key violation — referenced row doesn't exist (or is
        // in use). 409 because the conflict is between this row and
        // another row's existence.
        if (lower.contains("foreign key") || lower.contains("violates foreign")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("This action references a record that no longer exists."));
        }

        // Anything else — keep the message generic enough not to leak
        // schema details, but specific enough that the user can act.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Could not save changes due to a data conflict. Please try again."));
    }

    // 404 — No endpoint matched (prevents "No static resource" 500 errors)
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFoundEndpoint(Exception ex) {
        log.warn("Endpoint not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Endpoint not found: " + ex.getMessage()));
    }

    // 500 — Catch-all (LOGS THE REAL ERROR — critical for debugging)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception on request: {} — {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Internal server error: " + ex.getMessage()));
    }
}
