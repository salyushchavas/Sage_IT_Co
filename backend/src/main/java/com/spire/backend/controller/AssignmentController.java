package com.spire.backend.controller;

import com.spire.backend.dto.*;
import com.spire.backend.entity.Assignment;
import com.spire.backend.entity.Progress;
import com.spire.backend.entity.Submission;
import com.spire.backend.service.AssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AssignmentController {

    private final AssignmentService assignmentService;

    // ─── Lesson completion ──────────────────────────────────────────

    @PostMapping("/api/lessons/{lessonId}/complete")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> completeLesson(
            @PathVariable Long lessonId,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        Progress progress = assignmentService.completeLesson(lessonId, userId);
        return ResponseEntity.ok(ApiResponse.success("Lesson completed", Map.of(
                "lessonId", lessonId,
                "completed", progress.getCompleted(),
                "completionPercent", progress.getCompletionPercent()
        )));
    }

    // ─── Create assignment (instructor/admin) ───────────────────────

    @PostMapping("/api/courses/{courseId}/assignments")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('INSTRUCTOR') and @courseSecurity.isOwner(#courseId, authentication))")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createAssignment(
            @PathVariable Long courseId,
            @Valid @RequestBody AssignmentRequest dto,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        boolean isAdmin = isAdmin(authentication);

        Assignment a = assignmentService.createAssignment(courseId, dto, userId, isAdmin);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Assignment created", Map.of(
                "id", a.getId(),
                "title", a.getTitle(),
                "assignmentType", a.getAssignmentType().name(),
                "createdAt", a.getCreatedAt().toString()
        )));
    }

    // ─── Get assignments with unlock status ─────────────────────────

    @GetMapping("/api/courses/{courseId}/assignments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAssignments(
            @PathVariable Long courseId,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        boolean isAdmin = isAdmin(authentication);

        List<Map<String, Object>> data = assignmentService.getAssignmentsWithAccess(courseId, userId, isAdmin);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    // ─── Submit assignment (student) ────────────────────────────────

    @PostMapping("/api/assignments/{assignmentId}/submit")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitAssignment(
            @PathVariable Long assignmentId,
            @Valid @RequestBody SubmissionRequest dto,
            Authentication authentication) {
        Long studentId = extractUserId(authentication);
        Submission s = assignmentService.submitAssignment(assignmentId, dto, studentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Assignment submitted", Map.of(
                "id", s.getId(),
                "assignmentId", s.getAssignment().getId(),
                "submittedAt", s.getSubmittedAt().toString()
        )));
    }

    // ─── Get submissions (instructor/admin) ─────────────────────────

    @GetMapping("/api/assignments/{assignmentId}/submissions")
    @PreAuthorize("hasRole('ADMIN') or hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSubmissions(
            @PathVariable Long assignmentId,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        boolean isAdmin = isAdmin(authentication);

        List<Submission> submissions = assignmentService.getSubmissions(assignmentId, userId, isAdmin);
        List<Map<String, Object>> data = submissions.stream().map(s -> {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", s.getId());
            map.put("studentEmail", s.getStudent().getEmail());
            map.put("studentName", s.getStudent().getFullName());
            map.put("content", s.getContent());
            map.put("submittedAt", s.getSubmittedAt().toString());
            map.put("grade", s.getGrade());
            map.put("feedback", s.getFeedback());
            return map;
        }).toList();

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    // ─── Grade submission (instructor/admin) ────────────────────────

    @PutMapping("/api/submissions/{submissionId}/grade")
    @PreAuthorize("hasRole('ADMIN') or hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> gradeSubmission(
            @PathVariable Long submissionId,
            @Valid @RequestBody GradeRequest dto,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        boolean isAdmin = isAdmin(authentication);

        Submission s = assignmentService.gradeSubmission(submissionId, dto, userId, isAdmin);
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("id", s.getId());
        result.put("grade", s.getGrade());
        result.put("feedback", s.getFeedback());
        return ResponseEntity.ok(ApiResponse.success("Submission graded", result));
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private Long extractUserId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
