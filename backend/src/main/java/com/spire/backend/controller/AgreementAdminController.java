package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.AgreementSummaryDto;
import com.spire.backend.dto.AgreementUserDto;
import com.spire.backend.entity.AgreementUser;
import com.spire.backend.entity.AgreementUserRole;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementUserRepository;
import com.spire.backend.security.AgreementAuthz;
import com.spire.backend.service.ConsultantApplicationService;
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
    private final ConsultantApplicationService consultantService;
    private final com.spire.backend.service.AgreementAssignmentService assignmentService;
    private final com.spire.backend.repository.ConsultantApplicationRepository applicationRepository;
    private final com.spire.backend.service.EmailTemplateService emailTemplateService;

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

    /**
     * Phase C feature 3 — every live (non-archived) application, slimmed
     * to identity + status + owner, for the "Agreements by ERM" grouped
     * view. The frontend groups by ownerErmId.
     */
    @GetMapping("/applications")
    public ResponseEntity<ApiResponse<List<AgreementSummaryDto>>> listAgreements(
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);
        List<AgreementSummaryDto> agreements = consultantService.listAllForAdmin().stream()
                .map(AgreementSummaryDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(agreements));
    }

    /**
     * Build L — super-admin soft-deletes any agreement (any status).
     * Hidden from every console afterward (ERM, super-admin,
     * consultant) and 404s on detail / mutation. Row + audit retained
     * so an operator can recover at the DB level. Returns 204.
     */
    @DeleteMapping("/applications/{appId}")
    public ResponseEntity<Void> deleteApplication(
            @PathVariable String appId,
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);
        consultantService.deleteApplication(appId, AgreementAuthz.userId(request), request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Build AA — DESTRUCTIVE operator backfill. Re-renders EVERY executed
     * (COMPLETED) agreement from the current template, overwrites its stored PDF
     * copies, and recomputes {@code documentHash} — propagating a template
     * correction into already-signed records (replacing the as-signed bytes +
     * signing-time hash). Old S3 objects are retained (renders write new
     * timestamped keys). DRY-RUN BY DEFAULT: it only reports the affected count;
     * pass {@code {"dryRun": false}} to actually execute. Super-admin only.
     */
    @PostMapping("/regenerate-completed-agreements")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> regenerateCompletedAgreements(
            @RequestBody(required = false) RegenerateBody body,
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);
        // Default to a dry run; only an explicit {"dryRun": false} executes.
        boolean dryRun = body == null || body.dryRun == null || body.dryRun;
        return ResponseEntity.ok(ApiResponse.success(
                dryRun ? "Dry run — no changes made" : "Regeneration complete",
                consultantService.regenerateCompletedAgreements(dryRun, request)));
    }

    public static class RegenerateBody {
        /** Defaults to true (dry run) when null/absent — must be explicitly false to execute. */
        public Boolean dryRun;
    }

    /**
     * Build AC — DESTRUCTIVE operator backfill. Revokes the ERM
     * countersignature on EVERY executed (COMPLETED) agreement and reverts it to
     * VERIFIED so the full approval + countersign gate re-runs (ERM re-sends →
     * approvers re-approve → ERM re-signs). Consultant-visible: completed
     * agreements revert to "awaiting signature". The consultant signature +
     * approval history are kept; old ERM-signed PDFs are retained in S3.
     * DRY-RUN BY DEFAULT: it only reports the affected count; pass
     * {@code {"dryRun": false}} to actually execute. Super-admin only.
     */
    @PostMapping("/revoke-erm-signatures")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> revokeErmSignatures(
            @RequestBody(required = false) RegenerateBody body,
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);
        boolean dryRun = body == null || body.dryRun == null || body.dryRun;
        return ResponseEntity.ok(ApiResponse.success(
                dryRun ? "Dry run — no changes made" : "ERM signatures revoked",
                consultantService.revokeErmSignaturesOnCompleted(dryRun, request)));
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

        // 3A — the super-admin may mint ERM, MANAGER, or ACCOUNTS users.
        // SUPER_ADMIN is env-bootstrapped only and can't be created here.
        AgreementUserRole role = parseAssignableRole(body == null ? null : body.role);

        AgreementUser user = AgreementUser.builder()
                .email(email.toLowerCase())
                .passwordHash(passwordEncoder.encode(tempPassword))
                .fullName(fullName)
                .title(title)
                .role(role)
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

    /**
     * Update a console user's display details — full name + title (the Name /
     * Title that render in the agreement signature block). Cosmetic identity
     * fields; the login email and role have their own flows. Allowed for every
     * user including the super-admin. Both are trimmed, required, length-bounded.
     */
    @PatchMapping("/users/{id}/details")
    @Transactional
    public ResponseEntity<ApiResponse<AgreementUserDto>> updateDetails(
            @PathVariable String id,
            @RequestBody DetailsBody body,
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);
        String fullName = body == null || body.fullName == null ? "" : body.fullName.trim();
        String title = body == null || body.title == null ? "" : body.title.trim();
        if (fullName.isEmpty()) {
            throw new IllegalArgumentException("Name is required.");
        }
        if (title.isEmpty()) {
            throw new IllegalArgumentException("Title is required.");
        }
        if (fullName.length() > 255) {
            throw new IllegalArgumentException("Name is too long (max 255 characters).");
        }
        if (title.length() > 255) {
            throw new IllegalArgumentException("Title is too long (max 255 characters).");
        }
        AgreementUser user = agreementUserRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AgreementUser", "id", id));
        user.setFullName(fullName);
        user.setTitle(title);
        user = agreementUserRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success("Details updated", AgreementUserDto.from(user)));
    }

    /**
     * Email a console user their sign-in credentials (email + temporary
     * password) from the brand no-reply address. The plaintext password is
     * supplied by the caller (super-admin) because it is only known at create
     * / reset time — it is hashed in storage and can never be re-derived. The
     * send is best-effort inside EmailService (failures are logged, not
     * thrown), so a mail outage won't 500 the console.
     */
    @PostMapping("/users/{id}/send-credentials")
    public ResponseEntity<ApiResponse<Void>> sendCredentials(
            @PathVariable String id,
            @RequestBody SendCredentialsBody body,
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);
        String password = body == null || body.password == null ? "" : body.password;
        if (password.isBlank()) {
            throw new IllegalArgumentException(
                    "A temporary password is required to email credentials.");
        }
        AgreementUser user = agreementUserRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AgreementUser", "id", id));
        emailTemplateService.sendAgreementUserCredentials(
                user.getEmail(), user.getFullName(), password);
        return ResponseEntity.ok(ApiResponse.success(
                "Credentials emailed to " + user.getEmail(), null));
    }

    /**
     * Build K2 — super-admin changes a console user's role (ERM / MANAGER /
     * ACCOUNTS). Re-roling purges the user's assignment links and un-routes
     * any pending approval gates routed to them (so nothing is stranded).
     */
    @PatchMapping("/users/{id}/role")
    @Transactional
    public ResponseEntity<ApiResponse<AgreementUserDto>> changeRole(
            @PathVariable String id,
            @RequestBody RoleBody body,
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);
        AgreementUser user = agreementUserRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AgreementUser", "id", id));
        if (user.getRole() == AgreementUserRole.SUPER_ADMIN) {
            throw new IllegalStateException("The super-admin's role cannot be changed.");
        }
        if (id.equals(AgreementAuthz.userId(request))) {
            throw new IllegalStateException("You cannot change your own role.");
        }
        AgreementUserRole newRole = parseAssignableRole(body == null ? null : body.role);
        if (user.getRole() == newRole) {
            return ResponseEntity.ok(ApiResponse.success(AgreementUserDto.from(user)));
        }
        // The old role's assignment links + routed gates no longer apply.
        assignmentService.purgeUserLinks(id);
        user.setRole(newRole);
        user = agreementUserRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success("Role updated", AgreementUserDto.from(user)));
    }

    /**
     * Build K2 — super-admin deletes a console user (frees the email).
     * Blocked for the super-admin, the caller's own account, and any ERM
     * that still owns live agreements (disable instead, to avoid orphaning
     * them). Purges assignment links + un-routes pending gates first.
     */
    @DeleteMapping("/users/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @PathVariable String id,
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);
        AgreementUser user = agreementUserRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AgreementUser", "id", id));
        if (user.getRole() == AgreementUserRole.SUPER_ADMIN) {
            throw new IllegalStateException("The super-admin account cannot be deleted.");
        }
        if (id.equals(AgreementAuthz.userId(request))) {
            throw new IllegalStateException("You cannot delete your own account.");
        }
        long owned = applicationRepository.countByOwnerErmIdAndDeletedFalse(id);
        if (owned > 0) {
            throw new IllegalStateException(
                    "This user owns " + owned + " agreement(s). Disable the user instead of "
                    + "deleting — deleting would hide those agreements.");
        }
        assignmentService.purgeUserLinks(id);
        agreementUserRepository.delete(user);
        return ResponseEntity.ok(ApiResponse.success("User deleted", null));
    }

    public static class RoleBody {
        public String role;
    }

    public static class DetailsBody {
        public String fullName;
        public String title;
    }

    public static class SendCredentialsBody {
        public String password;
    }

    // ── Build K — per-ERM Manager / Accounts assignments ─────────────

    /** The ERM's currently assigned manager + accounts user-ids. */
    @GetMapping("/users/{ermId}/assignments")
    public ResponseEntity<ApiResponse<AssignmentsDto>> getAssignments(
            @PathVariable String ermId,
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);
        AgreementUser erm = agreementUserRepository.findById(ermId)
                .orElseThrow(() -> new ResourceNotFoundException("AgreementUser", "id", ermId));
        if (erm.getRole() != AgreementUserRole.ERM) {
            throw new IllegalArgumentException("Assignments only apply to ERM users.");
        }
        return ResponseEntity.ok(ApiResponse.success(activeAssignments(ermId)));
    }

    /** Active-only assigned ids, so the admin view matches the ERM's pickers. */
    private AssignmentsDto activeAssignments(String ermId) {
        AssignmentsDto dto = new AssignmentsDto();
        dto.managerIds = assignmentService.assignedApprovers(ermId, AgreementUserRole.MANAGER)
                .stream().map(AgreementUser::getId).toList();
        dto.accountsIds = assignmentService.assignedApprovers(ermId, AgreementUserRole.ACCOUNTS)
                .stream().map(AgreementUser::getId).toList();
        return dto;
    }

    /** Replace the ERM's assigned managers + accounts (super-admin only). */
    @PutMapping("/users/{ermId}/assignments")
    @Transactional
    public ResponseEntity<ApiResponse<AssignmentsDto>> setAssignments(
            @PathVariable String ermId,
            @RequestBody AssignmentsDto body,
            HttpServletRequest request) {
        AgreementAuthz.requireSuperAdmin(request);
        assignmentService.replaceAssignments(ermId, AgreementUserRole.MANAGER,
                body == null ? null : body.managerIds);
        assignmentService.replaceAssignments(ermId, AgreementUserRole.ACCOUNTS,
                body == null ? null : body.accountsIds);
        return ResponseEntity.ok(ApiResponse.success("Assignments saved", activeAssignments(ermId)));
    }

    public static class AssignmentsDto {
        public List<String> managerIds;
        public List<String> accountsIds;
    }

    // ── DTOs ────────────────────────────────────────────────────────

    public static class CreateUserBody {
        public String email;
        public String fullName;
        public String title;
        public String temporaryPassword;
        /** 3A — "ERM" | "MANAGER" | "ACCOUNTS"; defaults to ERM. */
        public String role;
    }

    /**
     * 3A — resolve the requested role for a new console user. Accepts
     * ERM / MANAGER / ACCOUNTS (case-insensitive); defaults to ERM; never
     * allows minting another SUPER_ADMIN through the console.
     */
    private static AgreementUserRole parseAssignableRole(String raw) {
        if (raw == null || raw.isBlank()) return AgreementUserRole.ERM;
        AgreementUserRole role;
        try {
            role = AgreementUserRole.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Role must be ERM, MANAGER, or ACCOUNTS.");
        }
        if (role == AgreementUserRole.SUPER_ADMIN) {
            throw new IllegalArgumentException(
                    "SUPER_ADMIN cannot be created through the console.");
        }
        return role;
    }

    public static class StatusBody {
        public boolean active;
    }

    public static class PasswordBody {
        public String newPassword;
    }
}
