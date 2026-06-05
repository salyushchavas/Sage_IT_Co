package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Hardcoded login for the /agreement-erm console.
 *
 * The credentials come from env vars (agreement-erm.email /
 * agreement-erm.password) rather than the users table -- this surface
 * is intentionally separate from Sage's regular ERM dashboard.
 *
 * Success issues an 8h JWT with purpose=agreement_erm; the
 * {@link com.spire.backend.security.AgreementErmAuthFilter} validates
 * it on subsequent /api/agreement-erm/applications/** calls.
 */
@RestController
@RequiredArgsConstructor
public class AgreementErmAuthController {

    private final JwtService jwtService;

    @Value("${agreement-erm.email}")
    private String expectedEmail;

    @Value("${agreement-erm.password}")
    private String expectedPassword;

    @PostMapping("/api/agreement-erm/login")
    public ResponseEntity<ApiResponse<Map<String, String>>> login(
            @RequestBody LoginBody body) {

        if (body == null || body.email == null || body.password == null
                || !expectedEmail.equalsIgnoreCase(body.email.trim())
                || !expectedPassword.equals(body.password)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Invalid email or password."));
        }

        String token = jwtService.generateAgreementErmToken(expectedEmail.toLowerCase());
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("token", token, "email", expectedEmail.toLowerCase())));
    }

    public static class LoginBody {
        public String email;
        public String password;
    }
}
