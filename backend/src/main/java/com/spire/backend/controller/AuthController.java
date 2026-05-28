package com.spire.backend.controller;

import com.spire.backend.dto.*;
import com.spire.backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Returns a {@link RegistrationResponse} (no JWT) — the frontend
     * reads {@code requiresVerification} and routes to /verify-email.
     * Tokens are only handed out after the OTP is consumed.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegistrationResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        RegistrationResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Registration successful — check your email for a verification code", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        AuthResponse response = authService.refreshToken(refreshToken);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * OTP verification. Body: {@code { email, code }}. On success,
     * returns the same {@link AuthResponse} login does, so the
     * frontend can store the token and continue straight to
     * /dashboard. On failure (wrong code / expired / locked out),
     * the message string carries enough detail for the verify page
     * to render the right error.
     */
    @PostMapping("/verify-code")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyCode(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String code = body.get("code");
        if (email == null || email.isBlank() || code == null || code.isBlank()) {
            throw new IllegalArgumentException("Email and code are required");
        }
        AuthResponse response = authService.verifyCode(email, code);
        return ResponseEntity.ok(ApiResponse.success("Email verified", response));
    }

    /**
     * Issues a fresh OTP. Subject to a 60-second per-user cooldown
     * enforced server-side; the frontend countdown is just UX.
     * Returns 200 even when the email isn't on file so the response
     * can't be used to enumerate accounts.
     */
    @PostMapping("/resend-code")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resendCode(
            @RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        authService.resendVerificationCode(email);
        return ResponseEntity.ok(ApiResponse.success("Verification code sent", Map.of(
                "cooldownSeconds", 60
        )));
    }

    /**
     * Password reset request — always reports success (regardless of
     * whether the email is on file) so the response can't be used to
     * enumerate accounts. The actual email send only happens for
     * matching addresses inside the service.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Map<String, String>>> forgotPassword(
            @RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        authService.requestPasswordReset(email);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "message", "If that email is registered, a reset link has been sent."
        )));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Map<String, String>>> resetPassword(
            @RequestBody Map<String, String> body) {
        String token = body.get("token");
        String newPassword = body.get("newPassword");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token is required");
        }
        authService.resetPassword(token, newPassword);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Password updated")));
    }
}
