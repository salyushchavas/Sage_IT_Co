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
            List<byte[]> images = agreementDocumentService
                    .renderWatermarkedPageImages(pdfBytes, viewerEmail);
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
