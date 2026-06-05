package com.spire.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.ConsultantApplicationEvent;
import com.spire.backend.entity.ConsultantApplicationRevision;
import com.spire.backend.security.ConsultantOtpRateLimiter;
import com.spire.backend.service.ConsultantApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two scoping segments:
 *   /api/internal/consultant-applications/**  -- ERM-only, normal JWT
 *   /api/consultant/applications/**           -- public OTP + consultant JWT
 *
 * The "internal" base path is a deliberate signal that this surface
 * is not exposed via the public marketing nav. It's a hidden feature
 * accessed by direct URL only.
 */
@RestController
@RequiredArgsConstructor
public class ConsultantApplicationController {

    private final ConsultantApplicationService consultantService;
    private final ConsultantOtpRateLimiter otpRateLimiter;

    // ── ERM-side (requires ERM or SYSTEM_ADMIN) ─────────────────────

    @PostMapping("/api/internal/consultant-applications")
    @PreAuthorize("hasAnyRole('ERM', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> create(
            @RequestBody CreateBody body,
            Authentication auth,
            HttpServletRequest request) {
        Long ermUserId = Long.parseLong(auth.getPrincipal().toString());
        ConsultantApplication app = consultantService.createApplication(
                ermUserId,
                body.consultantEmail,
                body.consultantName,
                body.consultantPhone,
                body.payload,
                request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Consultant application created", app));
    }

    @GetMapping("/api/internal/consultant-applications")
    @PreAuthorize("hasAnyRole('ERM', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<ConsultantApplication>>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            Authentication auth) {
        Long ermUserId = Long.parseLong(auth.getPrincipal().toString());
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ConsultantApplication> result =
                consultantService.listForErm(ermUserId, status, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(result)));
    }

    @GetMapping("/api/internal/consultant-applications/{appId}")
    @PreAuthorize("hasAnyRole('ERM', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(
            @PathVariable String appId,
            Authentication auth) {
        Long ermUserId = Long.parseLong(auth.getPrincipal().toString());
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        if (!app.getErmUserId().equals(ermUserId)
                && !auth.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_SYSTEM_ADMIN".equals(a.getAuthority()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("This application belongs to a different ERM."));
        }
        List<ConsultantApplicationEvent> events =
                consultantService.listEvents(appId, app.getErmUserId());
        List<ConsultantApplicationRevision> revisions =
                consultantService.listRevisions(appId, app.getErmUserId());

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("application", app);
        view.put("events", events);
        view.put("revisions", revisions);
        return ResponseEntity.ok(ApiResponse.success(view));
    }

    @PutMapping("/api/internal/consultant-applications/{appId}")
    @PreAuthorize("hasAnyRole('ERM', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> update(
            @PathVariable String appId,
            @RequestBody UpdateBody body,
            Authentication auth,
            HttpServletRequest request) {
        Long ermUserId = Long.parseLong(auth.getPrincipal().toString());
        ConsultantApplication app = consultantService.updateApplication(
                appId, ermUserId,
                body.consultantEmail, body.consultantName, body.consultantPhone,
                body.payload, request);
        return ResponseEntity.ok(ApiResponse.success("Updated", app));
    }

    @PostMapping("/api/internal/consultant-applications/{appId}/cancel")
    @PreAuthorize("hasAnyRole('ERM', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> cancel(
            @PathVariable String appId,
            Authentication auth,
            HttpServletRequest request) {
        Long ermUserId = Long.parseLong(auth.getPrincipal().toString());
        ConsultantApplication app = consultantService.cancel(appId, ermUserId, request);
        return ResponseEntity.ok(ApiResponse.success("Cancelled", app));
    }

    @PostMapping("/api/internal/consultant-applications/{appId}/resend-invite")
    @PreAuthorize("hasAnyRole('ERM', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> resendInvite(
            @PathVariable String appId,
            Authentication auth,
            HttpServletRequest request) {
        Long ermUserId = Long.parseLong(auth.getPrincipal().toString());
        consultantService.resendInvite(appId, ermUserId, request);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "Invite re-sent")));
    }

    // ── Consultant-side ─────────────────────────────────────────────

    /**
     * Public endpoint. Always returns 200 OK regardless of whether the
     * application or email match -- prevents enumeration. The actual
     * OTP email only goes out when the request is legitimate.
     */
    @PostMapping("/api/consultant/applications/{appId}/request-otp")
    public ResponseEntity<ApiResponse<Map<String, String>>> requestOtp(
            @PathVariable String appId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        if (!otpRateLimiter.allowRequestOtp(appId, clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again later."));
        }
        consultantService.requestOtp(appId, body.get("email"), request);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "If that email matches our records, "
                        + "a verification code has been sent.")));
    }

    /**
     * Public endpoint. Returns a short-lived consultant JWT on success.
     * The frontend stores the token in sessionStorage and presents it
     * as a Bearer header on subsequent consultant calls.
     */
    @PostMapping("/api/consultant/applications/{appId}/verify-otp")
    public ResponseEntity<ApiResponse<Map<String, String>>> verifyOtp(
            @PathVariable String appId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        if (!otpRateLimiter.allowVerifyOtp(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many attempts. Try again later."));
        }
        String token = consultantService.verifyOtp(appId, body.get("otp"), request);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("accessToken", token, "applicationId", appId)));
    }

    // ── Consultant-side (require ROLE_CONSULTANT from the short-lived
    //    JWT issued by verify-otp). The path appId must match the
    //    token's subject -- enforced via requireSelf below. ──────────

    @GetMapping("/api/consultant/applications/{appId}")
    @PreAuthorize("hasRole('CONSULTANT')")
    public ResponseEntity<ApiResponse<com.spire.backend.entity.ConsultantApplication>> consultantGet(
            @PathVariable String appId,
            Authentication auth) {
        requireSelf(appId, auth);
        return ResponseEntity.ok(ApiResponse.success(
                consultantService.getForConsultant(appId)));
    }

    @PostMapping("/api/consultant/applications/{appId}/verify-details")
    @PreAuthorize("hasRole('CONSULTANT')")
    public ResponseEntity<ApiResponse<com.spire.backend.entity.ConsultantApplication>> consultantVerifyDetails(
            @PathVariable String appId,
            Authentication auth,
            HttpServletRequest request) {
        requireSelf(appId, auth);
        return ResponseEntity.ok(ApiResponse.success(
                "Details verified",
                consultantService.verifyDetails(appId, request)));
    }

    @PostMapping("/api/consultant/applications/{appId}/request-revision")
    @PreAuthorize("hasRole('CONSULTANT')")
    public ResponseEntity<ApiResponse<com.spire.backend.entity.ConsultantApplication>> consultantRequestRevision(
            @PathVariable String appId,
            @RequestBody Map<String, String> body,
            Authentication auth,
            HttpServletRequest request) {
        requireSelf(appId, auth);
        return ResponseEntity.ok(ApiResponse.success(
                "Revision requested",
                consultantService.requestRevision(appId, body.get("reason"), request)));
    }

    @PostMapping("/api/consultant/applications/{appId}/sign")
    @PreAuthorize("hasRole('CONSULTANT')")
    public ResponseEntity<ApiResponse<com.spire.backend.entity.ConsultantApplication>> consultantSign(
            @PathVariable String appId,
            @RequestBody SignBody body,
            Authentication auth,
            HttpServletRequest request) {
        requireSelf(appId, auth);
        return ResponseEntity.ok(ApiResponse.success(
                "Signed",
                consultantService.sign(appId, body.legalName, body.signatureImage, request)));
    }

    @PostMapping("/api/consultant/applications/{appId}/request-copy")
    @PreAuthorize("hasRole('CONSULTANT')")
    public ResponseEntity<ApiResponse<Map<String, String>>> consultantRequestCopy(
            @PathVariable String appId,
            Authentication auth,
            HttpServletRequest request) {
        requireSelf(appId, auth);
        consultantService.requestCopy(appId, request);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "A copy is on its way to your inbox.")));
    }

    private static void requireSelf(String pathAppId, Authentication auth) {
        // ConsultantJwtAuthFilter set the principal to the application
        // UUID from the token's subject. Reject any cross-application
        // access attempt.
        if (auth == null || auth.getName() == null
                || !auth.getName().equals(pathAppId)) {
            throw new com.spire.backend.exception.UnauthorizedException(
                    "Token does not match this application.");
        }
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
        public JsonNode payload;
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
