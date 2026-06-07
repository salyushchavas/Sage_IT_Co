package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.AgreementUser;
import com.spire.backend.repository.AgreementUserRepository;
import com.spire.backend.security.AgreementAuthz;
import com.spire.backend.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Login + identity for the consultant-agreement console.
 *
 * Multi-user phase: credentials are now validated against the {@code
 * agreement_user} table (BCrypt) rather than env vars. The single
 * SUPER_ADMIN is bootstrapped from env by {@code
 * DataSeeder.ensureSuperAdmin}; ERMs are minted through {@code
 * AgreementAdminController}. Success issues an 8h JWT
 * ({@code purpose=agreement_erm}) enriched with userId / role / name /
 * title, validated by {@link com.spire.backend.security.AgreementErmAuthFilter}.
 *
 * NOTE: the {@code AGREEMENT_ERM_EMAIL} env var is intentionally NOT
 * referenced here anymore -- it still drives the PDF placeholder /
 * notification recipient elsewhere and must stay set.
 */
@RestController
@RequiredArgsConstructor
public class AgreementErmAuthController {

    private final JwtService jwtService;
    private final AgreementUserRepository agreementUserRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/api/agreement-erm/login")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, String>>> login(
            @RequestBody LoginBody body) {

        // Generic failure for every case -- do not leak which condition
        // failed (unknown email vs. bad password vs. disabled account).
        ApiResponse<Map<String, String>> denied =
                ApiResponse.error("Invalid credentials or account disabled.");

        if (body == null || body.email == null || body.password == null
                || body.email.isBlank() || body.password.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(denied);
        }

        Optional<AgreementUser> found =
                agreementUserRepository.findByEmailIgnoreCase(body.email.trim());
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(denied);
        }
        AgreementUser user = found.get();
        if (!user.isActive()
                || !passwordEncoder.matches(body.password, user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(denied);
        }

        user.setLastLoginAt(LocalDateTime.now());
        agreementUserRepository.save(user);

        String token = jwtService.generateAgreementUserToken(
                user.getId(),
                user.getEmail().toLowerCase(),
                user.getRole().name(),
                user.getFullName(),
                user.getTitle());

        Map<String, String> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("email", user.getEmail().toLowerCase());
        data.put("role", user.getRole().name());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * Returns the authenticated agreement user's identity so the
     * frontend can render role-aware UI without decoding the JWT
     * client-side. Covered by the AgreementErmAuthFilter (the filter
     * stamps these request attributes from the validated token).
     */
    @GetMapping("/api/agreement-erm/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(HttpServletRequest request) {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("userId", request.getAttribute(AgreementAuthz.ATTR_USER_ID));
        me.put("email", request.getAttribute(AgreementAuthz.ATTR_EMAIL));
        me.put("fullName", request.getAttribute(AgreementAuthz.ATTR_FULL_NAME));
        me.put("title", request.getAttribute(AgreementAuthz.ATTR_TITLE));
        me.put("role", request.getAttribute(AgreementAuthz.ATTR_ROLE));
        return ResponseEntity.ok(ApiResponse.success(me));
    }

    public static class LoginBody {
        public String email;
        public String password;
    }
}
