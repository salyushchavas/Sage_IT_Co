package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.service.WebAgreementExecutionService;
import com.spire.backend.service.WebAgreementFileService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The end of the website agreement's chain, beside the staff routes under
 * the same path (the console's /api/agreement-erm/** for these four): the
 * signer's prefill, "Approve & sign", the executed PDF and the advance to
 * Phase 2. Same roles as the staff controller; the service lets the owning
 * ERM or an admin act, anyone else gets "not found".
 *
 * {@code /signer-profile} is a literal path, so Spring prefers it over the
 * staff controller's {@code /{appId}}.
 */
@RestController
@RequestMapping("/api/web-agreements")
@PreAuthorize("hasAnyRole('ERM','OPERATIONS_ADMIN','SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class WebAgreementExecutionController {

    private final WebAgreementExecutionService executionService;

    /** The caller's {fullName, title}, to prefill the countersign. */
    @GetMapping("/signer-profile")
    public ResponseEntity<ApiResponse<Map<String, String>>> signerProfile(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(executionService.signerProfile(userId(auth))));
    }

    /** The ERM countersigns (READY_TO_SIGN → COMPLETED) and the final PDF is made. */
    @PostMapping("/{appId}/approve-and-sign")
    public ResponseEntity<ApiResponse<WebAgreement>> approveAndSign(
            @PathVariable String appId,
            @RequestBody ApproveAndSignBody body,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Approved and signed",
                executionService.approveAndSign(appId, body.ermName, body.ermTitle,
                        body.ermSignatureBase64, userId(auth), request)));
    }

    /**
     * The stored executed PDF (inline unless disposition=attachment, never
     * cached). 404 when none is stored; 502 when it can't be read, as the
     * console.
     */
    @GetMapping("/{appId}/download-pdf")
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            Authentication auth) {
        WebAgreementFileService.Download pdf;
        try {
            pdf = executionService.downloadFinal(appId, userId(auth));
        } catch (WebAgreementExecutionService.FinalPdfUnreadable e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        if (pdf == null) {
            return ResponseEntity.notFound().build();
        }
        String mode = "attachment".equalsIgnoreCase(disposition) ? "attachment" : "inline";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, mode + "; filename=\"" + pdf.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(pdf.bytes());
    }

    /** Reopens an executed Phase 1 agreement for Phase 2 (COMPLETED → SUBMITTED). */
    @PostMapping("/{appId}/advance-to-phase-2")
    public ResponseEntity<ApiResponse<WebAgreement>> advanceToPhase2(
            @PathVariable String appId,
            @RequestBody(required = false) WebAgreementExecutionService.Phase2Promotion body,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Advanced to Phase 2",
                executionService.advanceToPhase2(appId, body, userId(auth), request)));
    }

    private static Long userId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }

    // ── Bodies ───────────────────────────────────────────────────────

    public static class ApproveAndSignBody {
        public String ermName;
        public String ermTitle;
        /** data:image/png;base64,... */
        public String ermSignatureBase64;
    }
}
