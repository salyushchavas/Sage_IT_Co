package com.spire.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.ConsultantApplicationEvent;
import com.spire.backend.entity.ConsultantApplicationRevision;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.security.AgreementAuthz;
import com.spire.backend.security.ConsultantRateLimiter;
import com.spire.backend.security.JwtService;
import com.spire.backend.service.AgreementDocumentService;
import com.spire.backend.service.ConsultantApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two scoping segments:
 *   /api/agreement-erm/applications/**     -- operator console, gated
 *                                              by ROLE_AGREEMENT_ERM.
 *   /api/consultant/applications/**        -- consultant himself,
 *                                              public + rate-limited.
 *                                              The UUID applicationId
 *                                              is the credential.
 *
 * Both bases are deliberately separate from the marketing site and
 * from Sage's regular /erm-dashboard surface. The consultant base
 * has no auth header at all -- a leak of an application UUID gives
 * an attacker access to that single application until it expires
 * (7 days) or is cancelled. The rate limiter and audit log mitigate
 * the blast radius.
 */
@RestController
@RequiredArgsConstructor
public class ConsultantApplicationController {

    private final ConsultantApplicationService consultantService;
    private final ConsultantRateLimiter rateLimiter;
    private final AgreementDocumentService agreementDocumentService;
    private final JwtService jwtService;

    /**
     * Portal-phase consultant gate. The token represents the verified
     * PERSON (email), not a single appId. Authorization is per-request:
     * the token's email claim must match the addressed application's
     * {@code consultantEmail} (case-insensitive). Mismatch is treated
     * as "agreement does not exist" -- 404, not 403 -- so a token
     * holder can't probe for other consultants' agreement IDs.
     *
     * Returns the verified, lower-cased email so the caller can also
     * stamp audit / access metadata against it.
     */
    private String requireConsultantToken(String appId, HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new UnauthorizedException("Verification required.");
        }
        String token = auth.substring(7);
        if (!jwtService.isTokenValid(token)
                || !"consultant".equals(jwtService.extractTokenType(token))) {
            throw new UnauthorizedException("Verification required.");
        }
        String tokenEmail = jwtService.extractConsultantEmail(token);
        if (tokenEmail == null || tokenEmail.isBlank()) {
            throw new UnauthorizedException("Verification required.");
        }
        String normalised = tokenEmail.trim().toLowerCase();
        // Email-match guard: an agreement addressed to a different
        // consultant looks like it doesn't exist to this token holder.
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        String onRecord = app.getConsultantEmail();
        if (onRecord == null
                || !onRecord.trim().toLowerCase().equals(normalised)) {
            throw new com.spire.backend.exception.ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", appId);
        }
        return normalised;
    }

    // ── Agreement-ERM-side (requires ROLE_AGREEMENT_ERM) ────────────

    @PostMapping("/api/agreement-erm/applications")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> create(
            @RequestBody CreateBody body,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.createApplication(
                body.consultantEmail,
                body.consultantName,
                body.consultantPhone,
                body.ratePeriod1,
                body.rateAmount1,
                body.ratePeriod2,
                body.rateAmount2,
                body.payload,
                // Authenticated agreement user, stamped as the owner.
                AgreementAuthz.userId(request),
                request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Consultant application created", app));
    }

    @GetMapping("/api/agreement-erm/applications")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<PageResponse<ConsultantApplication>>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        // Phase B — per-ERM isolation: an ERM gets only their own rows;
        // the super-admin gets all.
        Page<ConsultantApplication> result = consultantService.listApplications(
                status, pageable,
                AgreementAuthz.userId(request), AgreementAuthz.roleEnum(request));
        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(result)));
    }

    @GetMapping("/api/agreement-erm/applications/{appId}")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(
            @PathVariable String appId,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        // Non-owner ERM is treated as if the application doesn't exist (404).
        consultantService.assertErmCanAccess(app, request);
        List<ConsultantApplicationEvent> events = consultantService.listEvents(appId);
        List<ConsultantApplicationRevision> revisions =
                consultantService.listRevisions(appId);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("application", app);
        view.put("events", events);
        view.put("revisions", revisions);
        return ResponseEntity.ok(ApiResponse.success(view));
    }

    @PutMapping("/api/agreement-erm/applications/{appId}")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> update(
            @PathVariable String appId,
            @RequestBody UpdateBody body,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.updateApplication(
                appId,
                body.consultantEmail, body.consultantName, body.consultantPhone,
                body.payload, request);
        return ResponseEntity.ok(ApiResponse.success("Updated", app));
    }

    @PostMapping("/api/agreement-erm/applications/{appId}/cancel")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> cancel(
            @PathVariable String appId,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.cancel(appId, request);
        return ResponseEntity.ok(ApiResponse.success("Cancelled", app));
    }

    @PostMapping("/api/agreement-erm/applications/{appId}/resend-invite")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<Map<String, String>>> resendInvite(
            @PathVariable String appId,
            HttpServletRequest request) {
        consultantService.resendInvite(appId, request);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "Invite re-sent")));
    }

    /**
     * Phase C feature 1 — owner ERM (or super-admin) fixes a wrong
     * consultant email / name so a typo'd address never leaves a
     * consultant stuck. Ownership-gated (non-owner → 404).
     */
    @PatchMapping("/api/agreement-erm/applications/{appId}/consultant-contact")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> updateConsultantContact(
            @PathVariable String appId,
            @RequestBody ConsultantContactBody body,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.updateConsultantContact(
                appId,
                body == null ? null : body.consultantEmail,
                body == null ? null : body.consultantName,
                request);
        return ResponseEntity.ok(ApiResponse.success("Consultant contact updated", app));
    }

    // ── Agreement-ERM side: two-stage workflow (Phase 3) ────────────

    @PostMapping("/api/agreement-erm/applications/{appId}/request-revision")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> ermRequestRevision(
            @PathVariable String appId,
            @RequestBody RequestRevisionBody body,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Revision requested",
                consultantService.ermRequestRevision(appId, body.remarks, request)));
    }

    @PostMapping("/api/agreement-erm/applications/{appId}/approve-and-sign")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> approveAndSign(
            @PathVariable String appId,
            @RequestBody ApproveAndSignBody body,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Approved and signed",
                consultantService.ermApproveAndSign(
                        appId, body.ermName, body.ermTitle,
                        body.ermSignatureBase64, request)));
    }

    @PostMapping("/api/agreement-erm/applications/{appId}/send-email")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<Void> sendEmail(
            @PathVariable String appId,
            @RequestBody SendEmailBody body,
            HttpServletRequest request) {
        consultantService.sendPdfToCustomRecipient(
                appId, body.recipientEmail, body.note, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Streams the PDF bytes through the backend. The Cloudinary URL
     * never leaves the server, so View Inline / Download buttons can't
     * leak a token-in-URL to anyone watching the operator's browser
     * (right-click "Copy link", history scrape, address-bar copy,
     * shoulder-surf).
     *
     * Disposition default is {@code inline} -- browsers render the PDF
     * in-tab. Pass {@code ?disposition=attachment} to force a save
     * dialog. Either way the filename is the human-readable
     * {@code SageITCO-Agreement_Name_Track.pdf} pattern.
     *
     * Falls back to the pre-Phase-7 {@code finalPdfUrl} when no
     * {@code finalPdfPublicId} is on the row, and to the legacy
     * single-stage {@code signedPdfUrl} when neither final field is
     * set. 404 when nothing's available.
     */
    @GetMapping("/api/agreement-erm/applications/{appId}/download-pdf")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        // Phase B — the critical ownership check: an authenticated ERM-B
        // streaming ERM-A's appId must get 404 (treated as nonexistent),
        // even though the bytes are backend-streamed.
        consultantService.assertErmCanAccess(app, request);

        String sourceUrl;
        String publicId = app.getFinalPdfPublicId();
        if (publicId != null && !publicId.isBlank()) {
            // Server-side fetch URL -- still signed via the API
            // secret, but it's used only inside this JVM and never
            // returned to the client.
            sourceUrl = agreementDocumentService.signedPdfUrl(
                    publicId, java.time.Duration.ofMinutes(5));
        } else if (app.getFinalPdfUrl() != null && !app.getFinalPdfUrl().isBlank()) {
            sourceUrl = app.getFinalPdfUrl();
        } else if (app.getSignedPdfUrl() != null && !app.getSignedPdfUrl().isBlank()) {
            sourceUrl = app.getSignedPdfUrl();
        } else {
            return ResponseEntity.notFound().build();
        }

        byte[] bytes;
        try {
            java.net.URLConnection conn = new java.net.URL(sourceUrl).openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(30_000);
            try (java.io.InputStream in = conn.getInputStream()) {
                bytes = in.readAllBytes();
            }
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }

        String filename = AgreementDocumentService.buildPdfFilename(app);
        String dispositionMode = "attachment".equalsIgnoreCase(disposition)
                ? "attachment"
                : "inline";
        // Cache-Control: private,no-store -- corporate proxies and
        // shared-browser histories don't retain the bytes.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        dispositionMode + "; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(bytes);
    }

    // ── Consultant portal auth (PUBLIC — no session token) ──────────

    /**
     * Step 1 of the portal gate. Email-only body; generic response
     * either way (no enumeration). Backend sends an OTP only when the
     * email matches the {@code consultantEmail} of at least one
     * actionable agreement. Rate-limited per IP because the email is
     * the only other input and shouldn't be keyable directly.
     */
    @PostMapping("/api/consultant/auth/request-otp")
    public ResponseEntity<ApiResponse<Map<String, String>>> portalRequestOtp(
            @RequestBody(required = false) PortalOtpRequestBody body,
            HttpServletRequest request) {
        if (!rateLimiter.allowOtpRequest("portal|" + clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        String message = consultantService.requestPortalOtp(
                body == null ? null : body.email, request);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", message)));
    }

    /**
     * Step 2 of the portal gate. On success returns an email-scoped
     * consultant session token (type=consultant, email, ~2h). Generic
     * 400 on any failure; 5 wrong attempts invalidate the code.
     */
    @PostMapping("/api/consultant/auth/verify-otp")
    public ResponseEntity<ApiResponse<Map<String, String>>> portalVerifyOtp(
            @RequestBody(required = false) PortalOtpVerifyBody body,
            HttpServletRequest request) {
        if (!rateLimiter.allowOtpVerify("portal|" + clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        String token = consultantService.verifyPortalOtp(
                body == null ? null : body.email,
                body == null ? null : body.otp,
                request);
        return ResponseEntity.ok(ApiResponse.success(Map.of("token", token)));
    }

    /**
     * Portal dashboard list. Returns every actionable / in-flight /
     * completed agreement addressed to the verified email. Sorted so
     * the action items surface first.
     */
    @GetMapping("/api/consultant/agreements")
    public ResponseEntity<ApiResponse<List<ConsultantAgreementSummary>>> portalDashboard(
            HttpServletRequest request) {
        String email = requireConsultantEmail(request);
        if (!rateLimiter.allowRead(clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        List<ConsultantAgreementSummary> items = consultantService.listForConsultant(email)
                .stream()
                .map(ConsultantAgreementSummary::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /**
     * Validate a portal token and return the verified email. Used by
     * the dashboard endpoint (no appId in path so it can't use the
     * email-match guard).
     */
    private String requireConsultantEmail(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new UnauthorizedException("Verification required.");
        }
        String token = auth.substring(7);
        if (!jwtService.isTokenValid(token)
                || !"consultant".equals(jwtService.extractTokenType(token))) {
            throw new UnauthorizedException("Verification required.");
        }
        String emailClaim = jwtService.extractConsultantEmail(token);
        if (emailClaim == null || emailClaim.isBlank()) {
            throw new UnauthorizedException("Verification required.");
        }
        return emailClaim.trim().toLowerCase();
    }

    /**
     * Stream the final signed PDF to the verified consultant. Mirrors
     * the ERM streaming endpoint -- backend fetches from Cloudinary with
     * credentials, the Cloudinary URL never reaches the client; frontend
     * wraps the bytes in a blob URL. Only available for COMPLETED.
     */
    @GetMapping("/api/consultant/applications/{appId}/pdf")
    public ResponseEntity<byte[]> consultantDownloadPdf(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        if (!ConsultantApplication.Status.COMPLETED.name().equals(app.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        String sourceUrl;
        String publicId = app.getFinalPdfPublicId();
        if (publicId != null && !publicId.isBlank()) {
            sourceUrl = agreementDocumentService.signedPdfUrl(
                    publicId, java.time.Duration.ofMinutes(5));
        } else if (app.getFinalPdfUrl() != null && !app.getFinalPdfUrl().isBlank()) {
            sourceUrl = app.getFinalPdfUrl();
        } else {
            return ResponseEntity.notFound().build();
        }

        byte[] bytes;
        try {
            java.net.URLConnection conn = new java.net.URL(sourceUrl).openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(30_000);
            try (java.io.InputStream in = conn.getInputStream()) {
                bytes = in.readAllBytes();
            }
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }

        String filename = AgreementDocumentService.buildPdfFilename(app);
        String dispositionMode = "attachment".equalsIgnoreCase(disposition)
                ? "attachment"
                : "inline";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        dispositionMode + "; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(bytes);
    }

    // ── Consultant-side (consultant-token gated, rate-limited) ──────

    @GetMapping("/api/consultant/applications/{appId}")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantGet(
            @PathVariable String appId,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowRead(clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                consultantService.getForConsultant(appId, request)));
    }

    @PostMapping("/api/consultant/applications/{appId}/verify-details")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantVerifyDetails(
            @PathVariable String appId,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Details verified",
                consultantService.verifyDetails(appId, request)));
    }

    @PostMapping("/api/consultant/applications/{appId}/request-revision")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantRequestRevision(
            @PathVariable String appId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Revision requested",
                consultantService.requestRevision(appId, body.get("reason"), request)));
    }

    @PostMapping("/api/consultant/applications/{appId}/sign")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantSign(
            @PathVariable String appId,
            @RequestBody SignBody body,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Signed",
                consultantService.sign(appId, body.legalName, body.signatureImage, request)));
    }

    @PostMapping("/api/consultant/applications/{appId}/request-copy")
    public ResponseEntity<ApiResponse<Map<String, String>>> consultantRequestCopy(
            @PathVariable String appId,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        consultantService.requestCopy(appId, request);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "A copy is on its way to your inbox.")));
    }

    // ── Consultant-side: two-stage workflow (Phase 3) ───────────────

    /**
     * Partial-save endpoint for the consultant /fill page. Body is any
     * subset of the 38 fillable fields -- missing keys are left
     * untouched on the entity. Idempotent: a request with no field
     * changes returns the unchanged entity and emits no audit event.
     */
    @PutMapping("/api/consultant/applications/{appId}/fill")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantFill(
            @PathVariable String appId,
            @RequestBody ConsultantApplicationService.ConsultantFillPatch body,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Saved",
                consultantService.consultantFill(appId, body, request)));
    }

    /**
     * Consultant signs and submits. Uploads the signature image to
     * Cloudinary, locks the legal name, and transitions to VERIFIED.
     * The final PDF is NOT rendered here -- it's produced once when
     * the ERM countersigns to avoid ~10s of LibreOffice cold start
     * on the consultant's click.
     */
    @PostMapping("/api/consultant/applications/{appId}/submit")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantSubmit(
            @PathVariable String appId,
            @RequestBody ConsultantSubmitBody body,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Submitted",
                consultantService.consultantSubmit(
                        appId, body.signatureBase64, body.signedLegalName, request)));
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    // ── DTOs ────────────────────────────────────────────────────────

    public static class CreateBody {
        public String consultantEmail;
        public String consultantName;
        public String consultantPhone;
        // Rate card -- two free-form pairs (e.g. period="hourly",
        // amount="$60") set by the ERM at create time and rendered
        // into the Word template's Section 11 + Appendix 1 cells.
        public String ratePeriod1;
        public String rateAmount1;
        public String ratePeriod2;
        public String rateAmount2;
        // Legacy free-form payload, preserved for backward compat
        // with the existing /agreement-erm/new form. New flow ignores it.
        public JsonNode payload;
    }

    public static class RequestRevisionBody {
        public String remarks;
    }

    public static class ApproveAndSignBody {
        public String ermName;
        public String ermTitle;
        /** data:image/png;base64,... */
        public String ermSignatureBase64;
    }

    public static class SendEmailBody {
        public String recipientEmail;
        public String note;
    }

    public static class ConsultantContactBody {
        public String consultantEmail;
        public String consultantName;
    }

    public static class ConsultantSubmitBody {
        /** data:image/png;base64,... */
        public String signatureBase64;
        public String signedLegalName;
    }

    public static class UpdateBody {
        public String consultantEmail;
        public String consultantName;
        public String consultantPhone;
        public JsonNode payload;
    }

    public static class SignBody {
        public String legalName;
        public String signatureImage;
    }

    public static class PortalOtpRequestBody {
        public String email;
    }

    public static class PortalOtpVerifyBody {
        public String email;
        public String otp;
    }

    /**
     * Dashboard row served to the verified consultant. Each item carries
     * the raw status (drives PDF download eligibility) plus a
     * consultant-friendly label + an action enum the frontend switches
     * on to render the right button.
     */
    public static class ConsultantAgreementSummary {
        public String appId;
        public String agreementTitle;
        public String technologyTrack;
        public String status;
        public String statusLabel;
        public String action;
        public String createdAt;
        public String updatedAt;

        public static ConsultantAgreementSummary from(ConsultantApplication app) {
            ConsultantAgreementSummary r = new ConsultantAgreementSummary();
            r.appId = app.getApplicationId();
            r.agreementTitle =
                    "Integrated Two-Phase Coaching & Post-Offer Support Agreement";
            r.technologyTrack = app.getTechnologyTrack();
            r.status = app.getStatus();
            String s = app.getStatus();
            if (ConsultantApplication.Status.SUBMITTED.name().equals(s)) {
                r.statusLabel = "Awaiting your signature";
                r.action = "SIGN";
            } else if (ConsultantApplication.Status.REVISION_REQUESTED.name().equals(s)) {
                r.statusLabel = "Changes requested — review & re-sign";
                r.action = "REVIEW_AND_SIGN";
            } else if (ConsultantApplication.Status.UPDATED.name().equals(s)
                    || ConsultantApplication.Status.VERIFIED.name().equals(s)
                    || ConsultantApplication.Status.SIGNED.name().equals(s)) {
                r.statusLabel = "Submitted — under review by Sage IT";
                r.action = "VIEW";
            } else if (ConsultantApplication.Status.COMPLETED.name().equals(s)) {
                r.statusLabel = "Completed";
                r.action = "DOWNLOAD";
            } else {
                r.statusLabel = s;
                r.action = "VIEW";
            }
            r.createdAt = app.getCreatedAt() == null ? null : app.getCreatedAt().toString();
            r.updatedAt = app.getUpdatedAt() == null ? null : app.getUpdatedAt().toString();
            return r;
        }
    }

    public static class PageResponse<T> {
        public List<T> content;
        public int page;
        public int size;
        public long totalElements;
        public int totalPages;
        public boolean hasNext;

        public static <T> PageResponse<T> from(Page<T> page) {
            PageResponse<T> r = new PageResponse<>();
            r.content = page.getContent();
            r.page = page.getNumber();
            r.size = page.getSize();
            r.totalElements = page.getTotalElements();
            r.totalPages = page.getTotalPages();
            r.hasNext = page.hasNext();
            return r;
        }
    }
}
