package com.spire.backend.controller;

import com.spire.backend.dto.AcceptSessionRequest;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.CreateSessionRequest;
import com.spire.backend.dto.SessionRequestDTO;
import com.spire.backend.service.SessionRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionRequestController {

    private final SessionRequestService sessionRequestService;

    // ─── Student creates a session request ─────────────────────────

    @PostMapping
    public ResponseEntity<ApiResponse<SessionRequestDTO>> createRequest(
            @Valid @RequestBody CreateSessionRequest dto,
            Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        SessionRequestDTO created = sessionRequestService.createRequest(
                userId, dto.getEnrollmentId(), dto.getTopic());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Session requested", created));
    }

    // ─── Student lists own requests ────────────────────────────────

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<SessionRequestDTO>>> getMyRequests(
            Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                sessionRequestService.getRequestsForStudent(userId)));
    }

    // ─── Mentor: full list (PENDING first, then ACCEPTED, then others) ──

    @GetMapping("/mentor")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<List<SessionRequestDTO>>> getMentorRequests(
            Authentication authentication) {
        Long mentorId = Long.parseLong(authentication.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                sessionRequestService.getRequestsForMentor(mentorId)));
    }

    // ─── Mentor: pending inbox only ────────────────────────────────

    @GetMapping("/mentor/pending")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<List<SessionRequestDTO>>> getMentorPending(
            Authentication authentication) {
        Long mentorId = Long.parseLong(authentication.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                sessionRequestService.getPendingRequestsForMentor(mentorId)));
    }

    // ─── Mentor accepts and schedules ──────────────────────────────

    @PutMapping("/{id}/accept")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<SessionRequestDTO>> accept(
            @PathVariable Long id,
            @Valid @RequestBody AcceptSessionRequest dto,
            Authentication authentication) {
        Long mentorId = Long.parseLong(authentication.getPrincipal().toString());
        SessionRequestDTO updated = sessionRequestService.acceptRequest(
                mentorId, id, dto.getScheduledAt(), dto.getMeetingUrl());
        return ResponseEntity.ok(ApiResponse.success("Session accepted and scheduled", updated));
    }

    // ─── Mark complete (either party) ──────────────────────────────

    @PutMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<SessionRequestDTO>> complete(
            @PathVariable Long id, Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        SessionRequestDTO updated = sessionRequestService.completeRequest(userId, id);
        return ResponseEntity.ok(ApiResponse.success("Session marked complete", updated));
    }

    // ─── Cancel (either party) ─────────────────────────────────────

    @PutMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<SessionRequestDTO>> cancel(
            @PathVariable Long id, Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        SessionRequestDTO updated = sessionRequestService.cancelRequest(userId, id);
        return ResponseEntity.ok(ApiResponse.success("Session cancelled", updated));
    }
}
