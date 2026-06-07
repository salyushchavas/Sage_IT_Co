package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.AgreementUserDto;
import com.spire.backend.entity.AgreementUser;
import com.spire.backend.entity.AgreementUserRole;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementUserRepository;
import com.spire.backend.security.AgreementAuthz;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;

/**
 * Super-admin-only console for managing agreement-console users.
 *
 * Every handler enforces {@link AgreementAuthz#requireSuperAdmin} first
 * -- the AgreementErmAuthFilter has already authenticated the caller as
 * an agreement-console user (SUPER_ADMIN | ERM) via {@code
 * ROLE_AGREEMENT_ERM}; this controller narrows that to the single
 * super-admin and returns 403 (via GlobalExceptionHandler) otherwise.
 *
 * Users created here are always {@link AgreementUserRole#ERM} -- there
 * is no way to mint another SUPER_ADMIN through the UI.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agreements/admin")
public class AgreementAdminController {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AgreementUserRepository agreementUserRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<AgreementUserDto>>> listUsers(
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);
        List<AgreementUserDto> users = agreementUserRepository.findAll().stream()
                // Super-admin first, then newest accounts on top (nulls last).
                .sorted(Comparator
                        .comparingInt((AgreementUser u) ->
                                u.getRole() == AgreementUserRole.SUPER_ADMIN ? 0 : 1)
                        .thenComparing(AgreementUser::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(AgreementUserDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    @PostMapping("/users")
    @Transactional
    public ResponseEntity<ApiResponse<AgreementUserDto>> createUser(
            @RequestBody CreateUserBody body,
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);

        String email = body == null || body.email == null ? "" : body.email.trim();
        String fullName = body == null || body.fullName == null ? "" : body.fullName.trim();
        String title = body == null || body.title == null ? "" : body.title.trim();
        String tempPassword = body == null ? null : body.temporaryPassword;

        if (email.isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }
        if (fullName.isBlank()) {
            throw new IllegalArgumentException("Full name is required.");
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("Title is required.");
        }
        if (tempPassword == null || tempPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Temporary password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (agreementUserRepository.existsByEmailIgnoreCase(email)) {
            // 409 via GlobalExceptionHandler(IllegalStateException).
            throw new IllegalStateException("Email already in use.");
        }

        AgreementUser user = AgreementUser.builder()
                .email(email.toLowerCase())
                .passwordHash(passwordEncoder.encode(tempPassword))
                .fullName(fullName)
                .title(title)
                .role(AgreementUserRole.ERM)
                .active(true)
                .createdBy(AgreementAuthz.userId(request))
                .build();
        user = agreementUserRepository.save(user);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User created", AgreementUserDto.from(user)));
    }

    @PatchMapping("/users/{id}/status")
    @Transactional
    public ResponseEntity<ApiResponse<AgreementUserDto>> setStatus(
            @PathVariable String id,
            @RequestBody StatusBody body,
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);

        AgreementUser user = agreementUserRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AgreementUser", "id", id));

        boolean active = body != null && body.active;
        // The super-admin cannot lock themselves out of the console.
        if (!active && id.equals(AgreementAuthz.userId(request))) {
            throw new IllegalStateException("Cannot disable your own account.");
        }

        user.setActive(active);
        user = agreementUserRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success(AgreementUserDto.from(user)));
    }

    @PatchMapping("/users/{id}/password")
    @Transactional
    public ResponseEntity<ApiResponse<AgreementUserDto>> resetPassword(
            @PathVariable String id,
            @RequestBody PasswordBody body,
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);

        String newPassword = body == null ? null : body.newPassword;
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "New password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }

        AgreementUser user = agreementUserRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AgreementUser", "id", id));

        // Resets are for ERMs. Block resetting a SUPER_ADMIN through the
        // UI so the super-admin can't lock themselves out here.
        if (user.getRole() == AgreementUserRole.SUPER_ADMIN) {
            throw new IllegalStateException("Cannot reset a super-admin password from the console.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user = agreementUserRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success(AgreementUserDto.from(user)));
    }

    // ── DTOs ────────────────────────────────────────────────────────

    public static class CreateUserBody {
        public String email;
        public String fullName;
        public String title;
        public String temporaryPassword;
    }

    public static class StatusBody {
        public boolean active;
    }

    public static class PasswordBody {
        public String newPassword;
    }
}
