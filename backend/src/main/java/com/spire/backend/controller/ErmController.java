package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.ErmAssignment;
import com.spire.backend.entity.WeeklyReport;
import com.spire.backend.service.EmploymentService;
import com.spire.backend.service.ErmService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Phase 5B ERM dashboard endpoints. All routes are role-gated to ERM
 * and cross-participant authorization is enforced inside
 * {@link ErmService}.
 */
@RestController
@RequestMapping("/api/erm")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ERM')")
public class ErmController {

    private final ErmService ermService;
    private final com.spire.backend.service.SignedAgreementService signedAgreementService;
    private final EmploymentService employmentService;
    private final com.spire.backend.service.DocumentStorageService storageService;

    @GetMapping("/participants")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> roster(Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(ermService.roster(me)));
    }

    @GetMapping("/participants/{participantId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> participantDetail(
            @PathVariable Long participantId,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                ermService.participantDetail(me, participantId)));
    }

    /**
     * Checklist 2.3: the assigned ERM confirms they reviewed the
     * participant's signed agreement (roadmap step 10: "ERM receives
     * signed agreement and verifies readiness for onboarding").
     */
    @PutMapping("/participants/{participantId}/agreement/reviewed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markAgreementReviewed(
            @PathVariable Long participantId,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        var row = signedAgreementService.markReviewedByErm(participantId, me);
        return ResponseEntity.ok(ApiResponse.success("Agreement marked reviewed", Map.of(
                "reviewedAt", row.getErmReviewedAt() == null ? "" : row.getErmReviewedAt().toString())));
    }

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<List<ErmService.ReportRow>>> myReports(Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                ermService.reportsForMyParticipants(me)));
    }

    @PutMapping("/reports/{reportId}/review")
    public ResponseEntity<ApiResponse<WeeklyReport>> reviewReport(
            @PathVariable Long reportId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        Object notesRaw = body.get("notes");
        String notes = notesRaw == null ? "" : notesRaw.toString();
        return ResponseEntity.ok(ApiResponse.success(
                "Report reviewed", ermService.reviewReport(me, reportId, notes)));
    }

    @PostMapping("/participants/{participantId}/notes")
    public ResponseEntity<ApiResponse<ErmAssignment>> addNote(
            @PathVariable Long participantId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        Object noteRaw = body.get("note");
        String note = noteRaw == null ? "" : noteRaw.toString();
        boolean escalation = Boolean.TRUE.equals(body.get("escalation"));
        return ResponseEntity.ok(ApiResponse.success(
                "Note logged",
                ermService.appendNote(me, participantId, note, escalation)));
    }

    // ── Phase 6 — employment verification + Phase 1 approval ────────

    @GetMapping("/employment/pending")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> pendingEmployment(Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                employmentService.ermPendingVerifications(me)));
    }

    @PutMapping("/employment/{participantId}/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyEmployment(
            @PathVariable Long participantId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        Object notesRaw = body.get("notes");
        String notes = notesRaw == null ? "" : notesRaw.toString();
        var saved = employmentService.verifyEmployment(me, participantId, notes);
        return ResponseEntity.ok(ApiResponse.success(
                "Employment verified",
                Map.of(
                        "success", true,
                        "employmentId", saved.getId(),
                        "ermVerifiedDate", saved.getErmVerifiedDate()
                )));
    }

    /** Checklist 4.5: send the participant's employment details back for correction, with a reason. */
    @PutMapping("/employment/{participantId}/return")
    public ResponseEntity<ApiResponse<Map<String, Object>>> returnEmployment(
            @PathVariable Long participantId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        Object reason = body.get("reason");
        var saved = employmentService.returnForCorrection(me, participantId, reason == null ? "" : reason.toString());
        return ResponseEntity.ok(ApiResponse.success(
                "Sent back to the participant",
                Map.of("employmentId", saved.getId(), "returnedAt", saved.getReturnedAt())));
    }

    /** Checklist 4.5: the participant's offer letter (their current ERM only; each view is recorded). */
    @GetMapping("/employment/{participantId}/offer")
    public ResponseEntity<?> employmentOffer(@PathVariable Long participantId, Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return employmentService.offerFileForErm(me, participantId)
                .<ResponseEntity<?>>map(f -> StoredFileResponse.of(storageService, f, "offer-letter"))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/phases/pending")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> pendingPhaseApprovals(Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                employmentService.ermPendingPhaseApprovals(me)));
    }

    @PutMapping("/phases/{participantId}/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approvePhase1(
            @PathVariable Long participantId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        Object notesRaw = body.get("notes");
        String notes = notesRaw == null ? "" : notesRaw.toString();
        var saved = employmentService.approvePhase1(me, participantId, notes);
        return ResponseEntity.ok(ApiResponse.success(
                "Phase 1 approved",
                Map.of(
                        "success", true,
                        "phaseCompletionId", saved.getId(),
                        "ermApprovedDate", saved.getErmApprovedDate()
                )));
    }
}
