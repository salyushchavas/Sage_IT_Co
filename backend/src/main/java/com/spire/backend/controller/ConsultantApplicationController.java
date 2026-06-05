package com.spire.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.ConsultantApplicationEvent;
import com.spire.backend.entity.ConsultantApplicationRevision;
import com.spire.backend.security.ConsultantRateLimiter;
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
                body.payload,
                request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Consultant application created", app));
    }

    @GetMapping("/api/agreement-erm/applications")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<PageResponse<ConsultantApplication>>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ConsultantApplication> result =
                consultantService.listApplications(status, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(result)));
    }

    @GetMapping("/api/agreement-erm/applications/{appId}")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(
            @PathVariable String appId) {
        ConsultantApplication app = consultantService.getByApplicationId(appId);
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

    // ── Consultant-side (public, rate-limited) ──────────────────────

    @GetMapping("/api/consultant/applications/{appId}")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantGet(
            @PathVariable String appId,
            HttpServletRequest request) {
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
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        consultantService.requestCopy(appId, request);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "A copy is on its way to your inbox.")));
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
