package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.AuthResponse;
import com.spire.backend.service.AcknowledgmentService;
import com.spire.backend.service.ParticipantApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Roadmap step 1 (30 Sep): apply on the website, an ERM confirms, the
 * applicant registers from the emailed link.
 *
 * Public: apply, read a registration link, register from it.
 * Staff (ERM, Operations admin, System admin): the applications queue.
 */
@RestController
@RequiredArgsConstructor
public class ParticipantApplicationController {

    private static final String STAFF = "hasAnyRole('ERM','OPERATIONS_ADMIN','SYSTEM_ADMIN')";

    private final ParticipantApplicationService applicationService;

    // ── Public ───────────────────────────────────────────────────

    @PostMapping("/api/participants/apply")
    public ResponseEntity<ApiResponse<Map<String, Object>>> apply(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        var saved = applicationService.apply(body.get("fullName"), body.get("email"), body.get("phone"),
                body.get("selectedTechnology"), AcknowledgmentService.clientIp(request));
        return ResponseEntity.ok(ApiResponse.success("Application received",
                Map.of("email", saved.getEmail(), "status", saved.getStatus())));
    }

    @GetMapping("/api/participants/registration")
    public ResponseEntity<ApiResponse<Map<String, Object>>> registration(@RequestParam("token") String token) {
        return ResponseEntity.ok(ApiResponse.success(applicationService.registrationDetails(token)));
    }

    @PostMapping("/api/participants/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@RequestBody Map<String, String> body) {
        AuthResponse auth = applicationService.register(body.get("token"), body.get("password"), body.get("phone"));
        return ResponseEntity.ok(ApiResponse.success("Registered", auth));
    }

    // ── Staff ────────────────────────────────────────────────────

    @GetMapping("/api/applications")
    @PreAuthorize(STAFF)
    public ResponseEntity<ApiResponse<List<ParticipantApplicationService.Row>>> list(
            @RequestParam(value = "status", required = false) String status, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(applicationService.list(callerId(auth), status)));
    }

    @GetMapping("/api/applications/counts")
    @PreAuthorize(STAFF)
    public ResponseEntity<ApiResponse<Map<String, Long>>> counts(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(applicationService.counts(callerId(auth))));
    }

    /** Confirms the application and emails the applicant their registration link. */
    @PostMapping("/api/applications/{id}/confirm")
    @PreAuthorize(STAFF)
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirm(@PathVariable Long id, Authentication auth) {
        var result = applicationService.confirm(id, callerId(auth));
        return ResponseEntity.ok(ApiResponse.success(
                result.emailSent() ? "Confirmed. The registration link was emailed to " + result.application().getEmail()
                        : "Confirmed, but the email couldn't be sent. Use \"Send link again\" once email works.",
                Map.of("application", applicationService.row(result.application()), "emailSent", result.emailSent())));
    }

    @PostMapping("/api/applications/{id}/resend")
    @PreAuthorize(STAFF)
    public ResponseEntity<ApiResponse<Map<String, Object>>> resend(@PathVariable Long id, Authentication auth) {
        var result = applicationService.resend(id, callerId(auth));
        return ResponseEntity.ok(ApiResponse.success(
                result.emailSent() ? "A new registration link was emailed to " + result.application().getEmail()
                        : "The email couldn't be sent. Check the email log.",
                Map.of("application", applicationService.row(result.application()), "emailSent", result.emailSent())));
    }

    @PostMapping("/api/applications/{id}/decline")
    @PreAuthorize(STAFF)
    public ResponseEntity<ApiResponse<ParticipantApplicationService.Row>> decline(
            @PathVariable Long id, @RequestBody(required = false) Map<String, String> body, Authentication auth) {
        var saved = applicationService.decline(id, callerId(auth), body == null ? null : body.get("reason"));
        return ResponseEntity.ok(ApiResponse.success("Application declined", applicationService.row(saved)));
    }

    private static Long callerId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }
}
