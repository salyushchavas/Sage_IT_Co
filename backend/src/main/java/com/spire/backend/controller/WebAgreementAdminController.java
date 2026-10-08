package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.service.WebAgreementMaintenanceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The System Admin's agreement tools (the console's super-admin routes,
 * AgreementAdminController:86-140): delete (archive) an agreement, and the
 * two bulk tools over every executed agreement, regenerate the PDFs and
 * revoke the ERM countersignatures. Both bulk tools are a dry run unless the
 * body says {@code {"dryRun": false}}.
 *
 * System Admin only; the service checks the role again by name, because
 * legacy ADMIN also holds ROLE_ADMIN. The approver teams live beside these
 * under the same path ({@link WebAgreementTeamController}).
 */
@RestController
@RequestMapping("/api/web-agreement-admin")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class WebAgreementAdminController {

    private final WebAgreementMaintenanceService maintenanceService;

    /** Soft-deletes the agreement at any status; 204, and 404 when it is unknown or already deleted. */
    @DeleteMapping("/agreements/{appId}")
    public ResponseEntity<Void> deleteAgreement(
            @PathVariable String appId,
            Authentication auth,
            HttpServletRequest request) {
        maintenanceService.archive(appId, userId(auth), request);
        return ResponseEntity.noContent().build();
    }

    /** Re-renders every executed agreement's PDFs (dry run by default). */
    @PostMapping("/regenerate-completed-agreements")
    public ResponseEntity<ApiResponse<Map<String, Object>>> regenerateCompletedAgreements(
            @RequestBody(required = false) RegenerateBody body,
            Authentication auth,
            HttpServletRequest request) {
        boolean dryRun = isDryRun(body);
        return ResponseEntity.ok(ApiResponse.success(
                dryRun ? "Dry run — no changes made" : "Regeneration complete",
                maintenanceService.regenerateCompleted(dryRun, userId(auth), request)));
    }

    /** Revokes every executed agreement's ERM countersignature, back to VERIFIED (dry run by default). */
    @PostMapping("/revoke-erm-signatures")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revokeErmSignatures(
            @RequestBody(required = false) RegenerateBody body,
            Authentication auth,
            HttpServletRequest request) {
        boolean dryRun = isDryRun(body);
        return ResponseEntity.ok(ApiResponse.success(
                dryRun ? "Dry run — no changes made" : "ERM signatures revoked",
                maintenanceService.revokeErmSignatures(dryRun, userId(auth), request)));
    }

    /** Only an explicit {@code {"dryRun": false}} runs for real. */
    private static boolean isDryRun(RegenerateBody body) {
        return body == null || body.dryRun == null || body.dryRun;
    }

    private static Long userId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }

    // ── Bodies ───────────────────────────────────────────────────────

    /** The body of both bulk tools (the console's RegenerateBody). */
    public static class RegenerateBody {
        /** Null or absent is a dry run; must be false to execute. */
        public Boolean dryRun;
    }
}
