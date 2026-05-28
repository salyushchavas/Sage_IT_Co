package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.CoachingFeedback;
import com.spire.backend.entity.CoachingSession;
import com.spire.backend.entity.CoachingTask;
import com.spire.backend.service.CoachService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Phase 5B Coach dashboard endpoints. All routes are role-gated to
 * COACH / TECHNICAL_ADVISOR. Cross-participant authorization is
 * enforced inside {@link CoachService} — every read / write call
 * checks that the requested participant is assigned to the caller.
 */
@RestController
@RequestMapping("/api/coaches")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('COACH','TECHNICAL_ADVISOR')")
public class CoachController {

    private final CoachService coachService;

    @GetMapping("/participants")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myParticipants(Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(coachService.myParticipants(me)));
    }

    @GetMapping("/participants/{participantId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> participantDetail(
            @PathVariable Long participantId,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                coachService.participantDetail(me, participantId)));
    }

    // ── Sessions ──────────────────────────────────────────────────

    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<CoachingSession>>> listSessions(
            @RequestParam(value = "participantId", required = false) Long participantId,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                coachService.listSessions(me, participantId)));
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<CoachingSession>> createSession(
            @RequestBody CoachingSession body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                "Session recorded", coachService.createSession(me, body)));
    }

    // ── Tasks ─────────────────────────────────────────────────────

    @GetMapping("/tasks")
    public ResponseEntity<ApiResponse<List<CoachingTask>>> listTasks(
            @RequestParam(value = "participantId", required = false) Long participantId,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                coachService.listTasks(me, participantId)));
    }

    @PostMapping("/tasks")
    public ResponseEntity<ApiResponse<CoachingTask>> createTask(
            @RequestBody CoachingTask body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                "Task assigned", coachService.createTask(me, body)));
    }

    @PutMapping("/tasks/{taskId}/status")
    public ResponseEntity<ApiResponse<CoachingTask>> updateTaskStatus(
            @PathVariable Long taskId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        String status = body.getOrDefault("status", "OPEN");
        return ResponseEntity.ok(ApiResponse.success(
                coachService.updateTaskStatus(me, taskId, status)));
    }

    // ── Feedback ──────────────────────────────────────────────────

    @GetMapping("/feedback")
    public ResponseEntity<ApiResponse<List<CoachingFeedback>>> listFeedback(
            @RequestParam(value = "participantId", required = false) Long participantId,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                coachService.listFeedback(me, participantId)));
    }

    @PostMapping("/feedback")
    public ResponseEntity<ApiResponse<CoachingFeedback>> createFeedback(
            @RequestBody CoachingFeedback body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                "Feedback recorded", coachService.createFeedback(me, body)));
    }
}
