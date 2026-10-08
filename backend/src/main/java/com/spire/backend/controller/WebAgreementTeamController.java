package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.service.WebAgreementAssignmentService;
import com.spire.backend.service.WebAgreementAssignmentService.Team;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * The System Admin's approver teams (the console's per-ERM assignments,
 * AgreementAdminController:411-455): which Managers and Accounts approvers
 * each ERM may route an agreement to. System Admin only; the service checks
 * the role again by name, because legacy ADMIN also holds ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/web-agreement-admin")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class WebAgreementTeamController {

    private final WebAgreementAssignmentService assignmentService;

    /** The ERM's active team: {managerIds, accountsIds}. */
    @GetMapping("/users/{ermId}/assignments")
    public ResponseEntity<ApiResponse<Team>> getAssignments(@PathVariable Long ermId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(assignmentService.team(ermId, userId(auth))));
    }

    /** Replaces both lists in one go; returns the active team. */
    @PutMapping("/users/{ermId}/assignments")
    public ResponseEntity<ApiResponse<Team>> setAssignments(
            @PathVariable Long ermId, @RequestBody(required = false) Team body, Authentication auth) {
        Team saved = assignmentService.replaceTeam(ermId,
                body == null ? null : body.managerIds(),
                body == null ? null : body.accountsIds(),
                userId(auth));
        return ResponseEntity.ok(ApiResponse.success("Assignments saved", saved));
    }

    private static Long userId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }
}
