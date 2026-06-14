package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.AgreementApproval.ApproverRole;
import com.spire.backend.entity.AgreementUserRole;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.security.AgreementAuthz;
import com.spire.backend.service.AgreementDocumentService;
import com.spire.backend.service.ConsultantApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3B — approver surface for MANAGER / ACCOUNTS console users (SUPER_ADMIN
 * also reaches it, supplying the gate via {@code role}). Read-only
 * preview of the consultant-signed agreement + the two decisions
 * (Approve / Request Revision). The ERM countersign + management actions
 * live on the separate {@code /api/agreement-erm} surface.
 *
 * Route security: {@code /api/agreement-approver/**} requires
 * ROLE_AGREEMENT_MANAGER or ROLE_AGREEMENT_ACCOUNTS (SecurityConfig).
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('AGREEMENT_MANAGER','AGREEMENT_ACCOUNTS')")
public class AgreementApproverController {

    private final ConsultantApplicationService consultantService;
    private final AgreementDocumentService agreementDocumentService;

    /**
     * Resolve the approver gate the caller is acting on. MANAGER /
     * ACCOUNTS use their own role; the SUPER_ADMIN (who holds both
     * authorities) must specify which gate via {@code role}.
     */
    private ApproverRole resolveRole(HttpServletRequest request, String paramRole) {
        AgreementUserRole r = AgreementAuthz.roleEnum(request);
        if (r == AgreementUserRole.MANAGER) return ApproverRole.MANAGER;
        if (r == AgreementUserRole.ACCOUNTS) return ApproverRole.ACCOUNTS;
        if (r == AgreementUserRole.SUPER_ADMIN) {
            if (paramRole == null || paramRole.isBlank()) {
                throw new IllegalArgumentException(
                        "role (MANAGER|ACCOUNTS) is required for the super-admin.");
            }
            try {
                return ApproverRole.valueOf(paramRole.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("role must be MANAGER or ACCOUNTS.");
            }
        }
        throw new AccessDeniedException("Approver role required.");
    }

    /** The agreements awaiting MY gate, each with its current approvals. */
    @GetMapping("/api/agreement-approver/queue")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> queue(
            @RequestParam(value = "role", required = false) String role,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, role);
        List<ConsultantApplication> apps = consultantService.approverQueue(
                gate, AgreementAuthz.userId(request));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConsultantApplication app : apps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("application", app);
            row.put("approvals", consultantService.listApprovals(app.getApplicationId()));
            row.put("myRole", gate.name());
            out.add(row);
        }
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    /**
     * Build L — read-only record of agreements I (this approver) have
     * approved. Manager renders a flat list; Accounts groups by ermName.
     * Each row: appId, consultantName, ermId, ermName, phase, decidedAt,
     * current status.
     */
    @GetMapping("/api/agreement-approver/approved")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> approvedRecords(
            @RequestParam(value = "role", required = false) String role,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, role);
        return ResponseEntity.ok(ApiResponse.success(
                consultantService.approverApprovedRecords(gate, AgreementAuthz.userId(request))));
    }

    /** Read-only detail (+ approval history) for one agreement in my queue. */
    @GetMapping("/api/agreement-approver/applications/{appId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> detail(
            @PathVariable String appId,
            @RequestParam(value = "role", required = false) String role,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, role);
        ConsultantApplication app = consultantService.getForApprover(appId, gate, AgreementAuthz.userId(request));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("application", app);
        view.put("approvals", consultantService.listApprovals(appId));
        view.put("myRole", gate.name());
        return ResponseEntity.ok(ApiResponse.success(view));
    }

    /** Inline preview of the consultant-signed agreement (ERM signature blank). */
    @GetMapping("/api/agreement-approver/applications/{appId}/preview-pdf")
    public ResponseEntity<byte[]> previewPdf(
            @PathVariable String appId,
            @RequestParam(value = "role", required = false) String role,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, role);
        ConsultantApplication app = consultantService.getForApprover(appId, gate, AgreementAuthz.userId(request));
        byte[] bytes;
        try {
            bytes = agreementDocumentService.renderPdfBytes(
                    app, AgreementDocumentService.ermPreviewOverrides());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        String filename = AgreementDocumentService.buildPdfFilename(app);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"preview-" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(bytes);
    }

    /**
     * Build Y — non-copyable inline preview for approvers: the agreement
     * is rendered to PDF then rasterised into PNG page images (see
     * {@link AgreementDocumentService#renderWatermarkedPageImages}), so no
     * saveable PDF reaches the client. The approver page renders these in
     * a select-none, copy/drag/contextmenu-blocked view.
     */
    @GetMapping("/api/agreement-approver/applications/{appId}/preview-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewImages(
            @PathVariable String appId,
            @RequestParam(value = "role", required = false) String role,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, role);
        ConsultantApplication app = consultantService.getForApprover(appId, gate, AgreementAuthz.userId(request));
        String viewerEmail = (String) request.getAttribute(AgreementAuthz.ATTR_EMAIL);
        List<String> pages;
        try {
            byte[] pdfBytes = agreementDocumentService.renderPdfBytes(
                    app, AgreementDocumentService.ermPreviewOverrides());
            // Build L — approver preview stays clean (consultant-only watermark).
            List<byte[]> images = agreementDocumentService
                    .renderWatermarkedPageImages(pdfBytes, viewerEmail, false);
            pages = new ArrayList<>(images.size());
            for (byte[] png : images) {
                pages.add(java.util.Base64.getEncoder().encodeToString(png));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Couldn't render the preview."));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pages", pages);
        payload.put("pageCount", pages.size());
        payload.put("viewerEmail", viewerEmail);
        return ResponseEntity.ok(ApiResponse.success("Preview ready", payload));
    }

    /**
     * Build O — non-copyable inline preview of the FINAL, ERM-signed
     * agreement for an approver's "Approved Documents" record. Unlike
     * {@link #previewImages} (which blanks the ERM signature for the
     * pre-sign review), this renders with {@code null} overrides so every
     * signature — including the ERM countersignature and the appended
     * uploads — is present. Only available once the agreement is COMPLETED
     * (i.e. the ERM has signed); otherwise 409. Access is still gated by
     * {@link ConsultantApplicationService#getForApprover} — the final-round
     * gate row persists at COMPLETED, so the approver retains view rights.
     */
    @GetMapping("/api/agreement-approver/applications/{appId}/signed-preview-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> signedPreviewImages(
            @PathVariable String appId,
            @RequestParam(value = "role", required = false) String role,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, role);
        ConsultantApplication app = consultantService.getForApprover(appId, gate, AgreementAuthz.userId(request));
        if (!ConsultantApplication.Status.COMPLETED.name().equals(app.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("The signed agreement is available once the ERM has signed it."));
        }
        String viewerEmail = (String) request.getAttribute(AgreementAuthz.ATTR_EMAIL);
        List<String> pages;
        try {
            // null overrides → ALL signatures render (consultant + ERM
            // countersignature); appended uploads are already centralized
            // inside renderPdfBytes.
            byte[] pdfBytes = agreementDocumentService.renderPdfBytes(app, null);
            // Approver view stays clean (no consultant watermark).
            List<byte[]> images = agreementDocumentService
                    .renderWatermarkedPageImages(pdfBytes, viewerEmail, false);
            pages = new ArrayList<>(images.size());
            for (byte[] png : images) {
                pages.add(java.util.Base64.getEncoder().encodeToString(png));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Couldn't render the signed agreement."));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pages", pages);
        payload.put("pageCount", pages.size());
        payload.put("viewerEmail", viewerEmail);
        return ResponseEntity.ok(ApiResponse.success("Signed agreement ready", payload));
    }

    /**
     * Build S — the durable PHASE-1 ERM-signed agreement preview. Unlike
     * {@link #signedPreviewImages} (a LIVE render of current entity state — which
     * after the advance to Phase 2 would show the Phase-2 doc with blank Phase-1
     * signatures), this rasterizes the Phase-1 snapshot captured at the Phase-1
     * countersign ({@code phase1FinalPdfS3Key}). Gated on the snapshot EXISTING,
     * not on status, so the Manager previews it independently of Phase 2. MANAGER
     * only — Accounts approves Phase 2 only and never sees the Phase-1 copy.
     */
    @GetMapping("/api/agreement-approver/applications/{appId}/phase1-signed-preview-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> phase1SignedPreviewImages(
            @PathVariable String appId,
            @RequestParam(value = "role", required = false) String role,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, role);
        if (gate != ApproverRole.MANAGER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(
                            "The Phase 1 signed agreement is available to the Manager only."));
        }
        // Build S — admit any Manager who APPROVED this agreement (not just the
        // current-round routed approver), so a Phase-1 Manager keeps access
        // even if Phase 2 is routed to a different Manager.
        ConsultantApplication app = consultantService.getForManagerPhase1Preview(
                appId, AgreementAuthz.userId(request));
        String key = app.getPhase1FinalPdfS3Key();
        if (key == null || key.isBlank()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("No Phase 1 signed agreement on file for this agreement."));
        }
        String viewerEmail = (String) request.getAttribute(AgreementAuthz.ATTR_EMAIL);
        List<String> pages;
        try {
            // Rasterize the STORED Phase-1 snapshot (not a live re-render).
            byte[] pdfBytes = agreementDocumentService.readStoredPdfBytes(key);
            List<byte[]> images = agreementDocumentService
                    .renderWatermarkedPageImages(pdfBytes, viewerEmail, false);
            pages = new ArrayList<>(images.size());
            for (byte[] png : images) {
                pages.add(java.util.Base64.getEncoder().encodeToString(png));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Couldn't render the Phase 1 signed agreement."));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pages", pages);
        payload.put("pageCount", pages.size());
        payload.put("viewerEmail", viewerEmail);
        return ResponseEntity.ok(ApiResponse.success("Phase 1 signed agreement ready", payload));
    }

    /**
     * Build V — the approver reviews the FROZEN consultant VERSION the ERM
     * routed for this approval round ({@code app.approvalVersionNumber}), not a
     * live re-render. The version's immutable S3 snapshot is rasterized to
     * watermark-free PNGs (no downloadable PDF — the review surface stays
     * image-only). When no version was routed (legacy rounds before Build V),
     * it falls back to the live pre-sign render so older agreements still work.
     */
    @GetMapping("/api/agreement-approver/applications/{appId}/version-preview-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> versionPreviewImages(
            @PathVariable String appId,
            @RequestParam(value = "role", required = false) String role,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, role);
        ConsultantApplication app = consultantService.getForApprover(appId, gate, AgreementAuthz.userId(request));
        String viewerEmail = (String) request.getAttribute(AgreementAuthz.ATTR_EMAIL);
        List<String> pages;
        Integer versionNumber = app.getApprovalVersionNumber();
        try {
            byte[] pdfBytes = consultantService.versionSnapshotBytes(app);
            if (pdfBytes == null) {
                // Legacy round — no version routed; fall back to the live pre-sign render.
                pdfBytes = agreementDocumentService.renderPdfBytes(
                        app, AgreementDocumentService.ermPreviewOverrides());
            }
            List<byte[]> images = agreementDocumentService
                    .renderWatermarkedPageImages(pdfBytes, viewerEmail, false);
            pages = new ArrayList<>(images.size());
            for (byte[] png : images) {
                pages.add(java.util.Base64.getEncoder().encodeToString(png));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Couldn't render the selected version."));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pages", pages);
        payload.put("pageCount", pages.size());
        payload.put("viewerEmail", viewerEmail);
        payload.put("versionNumber", versionNumber);
        return ResponseEntity.ok(ApiResponse.success("Version ready", payload));
    }

    @PostMapping("/api/agreement-approver/applications/{appId}/approve")
    public ResponseEntity<ApiResponse<ConsultantApplication>> approve(
            @PathVariable String appId,
            @RequestBody(required = false) DecisionBody body,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, body == null ? null : body.role);
        ConsultantApplication app = consultantService.approverDecision(
                appId, gate, true, body == null ? null : body.note, request);
        return ResponseEntity.ok(ApiResponse.success("Approved", app));
    }

    @PostMapping("/api/agreement-approver/applications/{appId}/request-revision")
    public ResponseEntity<ApiResponse<ConsultantApplication>> requestRevision(
            @PathVariable String appId,
            @RequestBody DecisionBody body,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, body == null ? null : body.role);
        ConsultantApplication app = consultantService.approverDecision(
                appId, gate, false, body == null ? null : body.note, request);
        return ResponseEntity.ok(ApiResponse.success("Revision requested", app));
    }

    public static class DecisionBody {
        /** Required note for request-revision; optional on approve. */
        public String note;
        /** Only the super-admin needs to specify which gate (MANAGER|ACCOUNTS). */
        public String role;
    }
}
