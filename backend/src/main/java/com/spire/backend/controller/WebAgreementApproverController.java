package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.service.WebAgreementApproverService;
import com.spire.backend.service.WebAgreementApproverService.DocumentFailed;
import com.spire.backend.service.WebAgreementApproverService.Refused;
import com.spire.backend.service.WebAgreementFileService;
import com.spire.backend.service.WebAgreementRenderer;
import com.spire.backend.service.WebAgreementRules;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The approvers' side of the website agreement (the console's
 * /api/agreement-approver/** for website MANAGER and ACCOUNTS users): the
 * three lists, the read-only previews as page images, the downloads of an
 * agreement they approved, and the two decisions. The System Admin (the
 * console's super-admin) reaches it too and names the gate it acts on with
 * {@code role} (a query parameter, or the body on the decisions). The
 * service checks who may see what; anything else is "not found".
 *
 * Failures are answered the console's way: a preview that can't be made is
 * a 500 with the console's text; a refused or failed download carries its
 * reason on {@code X-Preview-Error} with no body.
 */
@RestController
@RequestMapping("/api/web-agreement-approvals")
@PreAuthorize("hasAnyRole('MANAGER','ACCOUNTS','SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class WebAgreementApproverController {

    private final WebAgreementApproverService approverService;

    /** Detaches the decided agreement before its PII is removed (open-in-view is on). */
    @PersistenceContext
    private EntityManager entityManager;

    // ── Lists ────────────────────────────────────────────────────────

    /** Pending: [{application, approvals, myRole}] awaiting my gate. */
    @GetMapping("/queue")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> queue(
            @RequestParam(value = "role", required = false) String role, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(approverService.queue(role, userId(auth))));
    }

    /** All agreements: every agreement routed to me in my role, with the list summary. */
    @GetMapping("/applications")
    public ResponseEntity<ApiResponse<List<WebAgreement>>> applications(
            @RequestParam(value = "role", required = false) String role, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(approverService.applications(role, userId(auth))));
    }

    /** Approved agreements: one record per agreement I approved in my role. */
    @GetMapping("/approved")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> approved(
            @RequestParam(value = "role", required = false) String role, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(approverService.approved(role, userId(auth))));
    }

    // ── Previews (page images, never a PDF) ──────────────────────────

    /** The version routed for this round: {pages, pageCount, viewerEmail, versionNumber}. */
    @GetMapping("/applications/{appId}/version-preview-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> versionPreviewImages(
            @PathVariable String appId,
            @RequestParam(value = "role", required = false) String role,
            Authentication auth) {
        return images("Version ready",
                () -> approverService.versionPreviewImages(appId, role, userId(auth)));
    }

    /** The latest version, for the All agreements list: {pages, pageCount, viewerEmail}. */
    @GetMapping("/applications/{appId}/latest-version-preview-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> latestVersionPreviewImages(
            @PathVariable String appId,
            @RequestParam(value = "role", required = false) String role,
            Authentication auth) {
        return images("Latest version ready",
                () -> approverService.latestVersionPreviewImages(appId, role, userId(auth)));
    }

    /** The executed agreement, once countersigned. */
    @GetMapping("/applications/{appId}/signed-preview-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> signedPreviewImages(
            @PathVariable String appId,
            @RequestParam(value = "role", required = false) String role,
            Authentication auth) {
        return images("Signed agreement ready",
                () -> approverService.signedPreviewImages(appId, role, userId(auth)));
    }

    /** The stored Phase 1 signed copy (Managers who approved it). */
    @GetMapping("/applications/{appId}/phase1-signed-preview-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> phase1SignedPreviewImages(
            @PathVariable String appId,
            @RequestParam(value = "role", required = false) String role,
            Authentication auth) {
        return images("Phase 1 signed agreement ready",
                () -> approverService.phase1SignedPreviewImages(appId, role, userId(auth)));
    }

    // ── Download (an agreement I approved) ───────────────────────────

    /** doc = final (default), phase1 or approved; always an attachment, never cached. */
    @GetMapping("/applications/{appId}/download-pdf")
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable String appId,
            @RequestParam(value = "doc", required = false) String doc,
            @RequestParam(value = "role", required = false) String role,
            Authentication auth) {
        WebAgreementFileService.Download pdf;
        try {
            pdf = approverService.download(appId, doc, role, userId(auth));
        } catch (Refused e) {
            return ResponseEntity.status(e.getStatus())
                    .header("X-Preview-Error", e.getMessage())
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .build();
        } catch (DocumentFailed e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("X-Preview-Error", WebAgreementRenderer.previewErrorReason(e.getCause()))
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(pdf.bytes());
    }

    // ── Decisions ────────────────────────────────────────────────────

    @PostMapping("/applications/{appId}/approve")
    public ResponseEntity<ApiResponse<WebAgreement>> approve(
            @PathVariable String appId,
            @RequestBody(required = false) DecisionBody body,
            Authentication auth,
            HttpServletRequest request) {
        WebAgreement a = approverService.decide(appId, true,
                body == null ? null : body.note, body == null ? null : body.role, userId(auth), request);
        return ResponseEntity.ok(ApiResponse.success("Approved", stripped(a)));
    }

    @PostMapping("/applications/{appId}/request-revision")
    public ResponseEntity<ApiResponse<WebAgreement>> requestRevision(
            @PathVariable String appId,
            @RequestBody DecisionBody body,
            Authentication auth,
            HttpServletRequest request) {
        WebAgreement a = approverService.decide(appId, false,
                body == null ? null : body.note, body == null ? null : body.role, userId(auth), request);
        return ResponseEntity.ok(ApiResponse.success("Revision requested", stripped(a)));
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * The decided agreement without the SSN, ID and bank numbers (approver
     * JSON never carries them). Runs here, after the decision has committed:
     * the strip detaches the row first, which inside the transaction would
     * throw the unflushed decision away.
     */
    private WebAgreement stripped(WebAgreement a) {
        WebAgreementRules.stripSensitivePii(entityManager, a);
        return a;
    }

    /** A preview, or the console's refusal (its status and text) or 500 with its text. */
    private static ResponseEntity<ApiResponse<Map<String, Object>>> images(
            String message, Supplier<Map<String, Object>> preview) {
        try {
            return ResponseEntity.ok(ApiResponse.success(message, preview.get()));
        } catch (Refused e) {
            return ResponseEntity.status(e.getStatus()).body(ApiResponse.error(e.getMessage()));
        } catch (DocumentFailed e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    private static Long userId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }

    // ── Bodies ───────────────────────────────────────────────────────

    public static class DecisionBody {
        /** Required to request a revision; optional on approve. */
        public String note;
        /** Only the System Admin names the gate it acts on (MANAGER or ACCOUNTS). */
        public String role;
    }
}
