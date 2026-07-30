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
@lombok.extern.slf4j.Slf4j
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
     * Build AI — the approver's "All agreements" status list: every agreement
     * ever routed to them (this gate), across all lifecycle statuses, with the
     * same summary fields the ERM list carries (managerStatus / accountsStatus /
     * sentForApprovalAt / ownerName). Read-only overview; actions stay on the
     * pending queue. Scoped to the approver's own gate rows.
     */
    @GetMapping("/api/agreement-approver/applications")
    public ResponseEntity<ApiResponse<List<ConsultantApplication>>> applications(
            @RequestParam(value = "role", required = false) String role,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, role);
        return ResponseEntity.ok(ApiResponse.success(
                consultantService.approverApplications(gate, AgreementAuthz.userId(request))));
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
            // Same blind-500 fix as the ERM preview: log the cause and echo a
            // short reason on X-Preview-Error.
            log.error("Approver preview render failed for {}", appId, e);
            return ConsultantApplicationController.previewFailure(e);
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

    /**
     * Build AJ — inline preview of the LATEST consultant version for an agreement
     * in the approver's "All agreements" status list. Rasterizes the newest
     * immutable version snapshot (highest V#) to watermark-free PNGs, falling
     * back to a live pre-sign render for legacy agreements with no version. Gated
     * by {@link ConsultantApplicationService#getForApproverAnyRound} — any round
     * the approver was routed, not just the current one — so a Manager can review
     * agreements that have since moved past their gate (ready-to-sign, completed).
     */
    @GetMapping("/api/agreement-approver/applications/{appId}/latest-version-preview-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> latestVersionPreviewImages(
            @PathVariable String appId,
            @RequestParam(value = "role", required = false) String role,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, role);
        ConsultantApplication app = consultantService.getForApproverAnyRound(
                appId, gate, AgreementAuthz.userId(request));
        String viewerEmail = (String) request.getAttribute(AgreementAuthz.ATTR_EMAIL);
        List<String> pages;
        try {
            byte[] pdfBytes = consultantService.latestVersionSnapshotBytes(app);
            if (pdfBytes == null) {
                // No version snapshot yet — live pre-sign render (consultant
                // signatures present, ERM signature blank).
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
                    .body(ApiResponse.error("Couldn't render the latest version."));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pages", pages);
        payload.put("pageCount", pages.size());
        payload.put("viewerEmail", viewerEmail);
        return ResponseEntity.ok(ApiResponse.success("Latest version ready", payload));
    }

    /**
     * Build AK — the approver's own downloadable copy of an agreement they
     * approved. Every other document route on this surface rasterizes to PNG
     * so nothing saveable reaches the browser; this one deliberately does not,
     * because a Manager / Accounts approver needs the PDF for their records
     * once their gate is cleared. Three documents, one per preview in the
     * approved record:
     *
     *   {@code doc=final}    the executed agreement (every signature present),
     *                        COMPLETED only — the stored final PDF when the row
     *                        has one, else the same live render
     *                        {@link #signedPreviewImages} shows;
     *   {@code doc=phase1}   the durable Phase-1 ERM-signed snapshot, MANAGER
     *                        only, mirroring {@link #phase1SignedPreviewImages};
     *   {@code doc=approved} the consultant version this approver signed off on
     *                        (ERM signature still blank) — available as soon as
     *                        the gate is cleared, so a Manager who has just
     *                        approved isn't left waiting on the countersign.
     *                        Mirrors {@link #latestVersionPreviewImages}.
     *
     * All three are gated on having APPROVED this agreement in this role
     * ({@code getForApproverWhoApproved}) — the membership test for the
     * "Approved agreements" record the Download buttons live on, so an
     * approver can always retrieve what they signed off on, and nobody else
     * can (404, no ID probing).
     *
     * A {@code byte[]} response has no room for the JSON envelope, so refusals
     * carry their reason on {@code X-Preview-Error} — the same CORS-exposed
     * diagnostic header the preview routes already use.
     */
    @GetMapping("/api/agreement-approver/applications/{appId}/download-pdf")
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable String appId,
            @RequestParam(value = "doc", required = false) String doc,
            @RequestParam(value = "role", required = false) String role,
            HttpServletRequest request) {
        ApproverRole gate = resolveRole(request, role);
        String userId = AgreementAuthz.userId(request);
        String kind = doc == null || doc.isBlank() ? "final" : doc.trim().toLowerCase();
        if (!"final".equals(kind) && !"phase1".equals(kind) && !"approved".equals(kind)) {
            return downloadRefusal(HttpStatus.BAD_REQUEST,
                    "doc must be final, phase1 or approved.");
        }
        if ("phase1".equals(kind) && gate != ApproverRole.MANAGER) {
            return downloadRefusal(HttpStatus.FORBIDDEN,
                    "The Phase 1 signed agreement is available to the Manager only.");
        }
        ConsultantApplication app = consultantService.getForApproverWhoApproved(
                appId, gate, userId);

        byte[] bytes;
        String prefix;
        try {
            if ("phase1".equals(kind)) {
                String key = app.getPhase1FinalPdfS3Key();
                if (key == null || key.isBlank()) {
                    return downloadRefusal(HttpStatus.CONFLICT,
                            "No Phase 1 signed agreement on file for this agreement.");
                }
                bytes = agreementDocumentService.readStoredPdfBytes(key);
                prefix = "phase1-signed-";
            } else if ("approved".equals(kind)) {
                bytes = consultantService.latestVersionSnapshotBytes(app);
                if (bytes == null) {
                    // Legacy agreement with no version snapshot — live pre-sign
                    // render (consultant signatures present, ERM blank).
                    bytes = agreementDocumentService.renderPdfBytes(
                            app, AgreementDocumentService.ermPreviewOverrides());
                }
                prefix = "approved-copy-";
            } else {
                if (!ConsultantApplication.Status.COMPLETED.name().equals(app.getStatus())) {
                    return downloadRefusal(HttpStatus.CONFLICT,
                            "The signed agreement is available once the ERM has signed it.");
                }
                bytes = storedFinalPdfBytes(app);
                if (bytes == null) {
                    // null overrides → every signature renders, including the
                    // ERM countersignature and the appended uploads.
                    bytes = agreementDocumentService.renderPdfBytes(app, null);
                }
                prefix = "signed-";
            }
        } catch (Exception e) {
            log.error("Approver download ({}) failed for {}", kind, appId, e);
            return ConsultantApplicationController.previewFailure(e);
        }

        // The approver surface is otherwise no-download by design, so leave a
        // trace of who took a copy of what.
        log.info("Approver {} ({}) downloaded '{}' for agreement {}", userId, gate, kind, appId);
        String filename = prefix + AgreementDocumentService.buildPdfFilename(app);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(bytes);
    }

    /**
     * The stored executed PDF when the row carries an S3 key, else null so the
     * caller falls back to a live render (legacy Cloudinary rows, and any row
     * whose object has gone unreadable — the bytes the approver previewed are
     * still reproducible from entity state).
     */
    private byte[] storedFinalPdfBytes(ConsultantApplication app) {
        String key = app.getS3Key();
        if (key == null || key.isBlank()) return null;
        try {
            return agreementDocumentService.readStoredPdfBytes(key);
        } catch (RuntimeException e) {
            log.warn("Stored final PDF unreadable for {} ({}) — falling back to a live render",
                    app.getApplicationId(), e.toString());
            return null;
        }
    }

    /** Refusal with a human reason on the CORS-exposed diagnostic header. */
    private static ResponseEntity<byte[]> downloadRefusal(HttpStatus status, String reason) {
        return ResponseEntity.status(status)
                .header("X-Preview-Error", reason)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .build();
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
