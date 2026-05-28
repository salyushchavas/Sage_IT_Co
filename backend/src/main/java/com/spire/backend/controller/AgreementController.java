package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.AgreementAcceptanceRepository;
import com.spire.backend.service.AgreementService;
import com.spire.backend.service.TermsContentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.Map;

/**
 * Endpoints for the email-reply Terms-of-Service flow + the public
 * terms read.
 *
 *   POST /api/auth/agreement/accept         — fire "Reply YES" email (auth'd)
 *   POST /api/auth/agreement/resend         — resend current step (auth'd)
 *   GET  /api/auth/agreement/check-status   — frontend polling target (auth'd)
 *   GET  /api/auth/agreement/status         — alias for legacy callers (auth'd)
 *   POST /api/auth/agreement/verify-code    — confirm OTP (auth'd)
 *   POST /api/auth/agreement/process-reply  — Vercel cron callback (cron secret)
 *   GET  /api/agreement/terms               — current terms text (public)
 *
 * Mounted under /api/auth/* so they're exempt from the agreement
 * gate (otherwise users could never reach the flow that lets them
 * satisfy it).
 *
 * Terms content lives in a single JSON file
 * ({@code resources/terms/v1.0.json}) loaded by
 * {@link TermsContentService}. Both this endpoint and the personalized
 * signed-agreement PDF generator render from that one file, so the
 * website terms and the PDF are always identical.
 */
@RestController
@RequiredArgsConstructor
public class AgreementController {

    private final AgreementService agreementService;
    private final TermsContentService termsContentService;
    private final AgreementAcceptanceRepository agreementRepository;

    @Value("${agreement.cron.secret:}")
    private String cronSecret;

    // ─── Auth'd endpoints ───────────────────────────────────────────

    @PostMapping("/api/auth/agreement/accept")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> acceptAgreement(
            @RequestBody Map<String, Object> body,
            Authentication auth,
            HttpServletRequest request) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        String legalName = (String) body.get("legalName");
        boolean termsAccepted = Boolean.TRUE.equals(body.get("termsAccepted"));
        boolean contentPolicyAccepted = Boolean.TRUE.equals(body.get("contentPolicyAccepted"));
        String signatureImage = (String) body.get("signatureImage");
        String signatureMethod = (String) body.get("signatureMethod");

        Map<String, Object> data = agreementService.requestAcceptance(
                userId, legalName, termsAccepted, contentPolicyAccepted,
                signatureImage, signatureMethod,
                clientIp(request), request.getHeader("User-Agent"));
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/api/auth/agreement/verify-code")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyAgreement(
            @RequestBody Map<String, String> body,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Code is required");
        }
        agreementService.verifyAcceptanceCode(userId, code);
        return ResponseEntity.ok(ApiResponse.success(
                "Agreement accepted",
                Map.of("status", "VERIFIED", "accepted", true)));
    }

    /**
     * Backward-compatible alias for the original verify endpoint.
     * The post-reply flow uses /verify-code; older clients that
     * still post to /verify continue to work.
     */
    @PostMapping("/api/auth/agreement/verify")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyAgreementAlias(
            @RequestBody Map<String, String> body,
            Authentication auth) {
        return verifyAgreement(body, auth);
    }

    @PostMapping("/api/auth/agreement/resend")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resendAgreementCode(
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        agreementService.resend(userId);
        return ResponseEntity.ok(ApiResponse.success(
                "Email resent",
                Map.of("cooldownSeconds", 60)));
    }

    @GetMapping({"/api/auth/agreement/status", "/api/auth/agreement/check-status"})
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAgreementStatus(
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(agreementService.getStatus(userId)));
    }

    // ─── Cron callback (no auth — gated by shared secret) ───────────

    /**
     * Called by the Vercel IMAP cron after it detects a YES reply.
     * Authenticated via a shared secret rather than a JWT because
     * the caller is a serverless function, not a user. If the
     * secret is unset on the backend, we refuse — empty-string
     * matches would let any anonymous caller in.
     */
    @PostMapping("/api/auth/agreement/process-reply")
    public ResponseEntity<ApiResponse<Map<String, Object>>> processReply(
            @RequestHeader(value = "X-Cron-Secret", required = false) String headerSecret,
            @RequestBody Map<String, Object> body) {
        if (cronSecret == null || cronSecret.isBlank()
                || !cronSecret.equals(headerSecret)) {
            throw new UnauthorizedException("Invalid cron secret");
        }
        Object userIdRaw = body.get("userId");
        Object fromEmailRaw = body.get("fromEmail");
        Object replyRaw = body.get("replyContent");
        if (userIdRaw == null || fromEmailRaw == null) {
            throw new IllegalArgumentException("userId and fromEmail are required");
        }
        Long userId = userIdRaw instanceof Number
                ? ((Number) userIdRaw).longValue()
                : Long.parseLong(userIdRaw.toString());
        boolean processed = agreementService.processReply(
                userId, fromEmailRaw.toString(),
                replyRaw == null ? "YES" : replyRaw.toString());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "processed", processed
        )));
    }

    /**
     * Sender-based lookup for the IMAP cron. Given a from-address,
     * tells the cron whether that mailbox owner has a pending
     * (WAITING_REPLY, not expired) agreement row, and if so the
     * matching user id.
     *
     * Replaces the original {@code [AGREE-{userId}-{ts}]} subject
     * tracking marker — Gmail / Outlook frequently mangle subjects
     * (re-encoding, line wrapping, prefix changes), so matching by
     * sender is the more reliable path.
     *
     * Cron-secret gated identically to {@link #processReply}.
     */
    @PostMapping("/api/auth/agreement/check-pending-user")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkPendingUser(
            @RequestHeader(value = "X-Cron-Secret", required = false) String headerSecret,
            @RequestBody Map<String, Object> body) {
        if (cronSecret == null || cronSecret.isBlank()
                || !cronSecret.equals(headerSecret)) {
            throw new UnauthorizedException("Invalid cron secret");
        }
        Object emailRaw = body.get("email");
        if (emailRaw == null || emailRaw.toString().isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        Map<String, Object> data = agreementService.checkPendingByEmail(emailRaw.toString());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    // ─── Public: current terms text ─────────────────────────────────

    /**
     * Public terms read. Renders directly from the v1.0.json file
     * via {@link TermsContentService} — same source as the signed PDF.
     */
    @GetMapping("/api/agreement/terms")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTerms() {
        TermsContentService.TermsDocument doc =
                termsContentService.getTerms(AgreementService.CURRENT_VERSION);
        return ResponseEntity.ok(ApiResponse.success(termsContentService.toApiResponse(doc)));
    }

    // ─── Signed-agreement PDF download ──────────────────────────────

    /**
     * Streams the personalized signed-agreement PDF generated when
     * the user completed OTP verification. Owner-or-admin gated:
     * the path's {@code userId} must match the JWT principal,
     * otherwise the caller must hold ROLE_ADMIN. Files live at
     * {@code signed-agreements/{filename}} on the backend's working
     * directory; the {@code userId} segment in the URL is purely
     * for display and a defence-in-depth check on the row lookup.
     */
    @GetMapping("/api/agreement/signed-pdf/{userId}/{fileName:.+}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> downloadSignedAgreement(
            @PathVariable Long userId,
            @PathVariable String fileName,
            Authentication auth) {
        Long callerId = Long.parseLong(auth.getPrincipal().toString());
        boolean isOwner = callerId.equals(userId);
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isOwner && !isAdmin) {
            throw new UnauthorizedException("Not allowed to download this agreement");
        }

        // Cross-check the row so a user can't probe other users'
        // filenames even if they accidentally land on a path with
        // someone else's id swapped in.
        var row = agreementRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Agreement", "userId", userId));
        String storedUrl = row.getSignedAgreementPdfUrl();
        if (storedUrl == null || !storedUrl.endsWith("/" + fileName)) {
            throw new ResourceNotFoundException("SignedAgreement", "file", fileName);
        }

        File file = new File("signed-agreements/" + fileName);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"Spire-Agreement-" + userId + ".pdf\"")
                .body(resource);
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
