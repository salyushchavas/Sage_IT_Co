package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.CourseMentorDTO;
import com.spire.backend.service.MentorPoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/courses/{courseId}/mentors")
@RequiredArgsConstructor
public class MentorPoolController {

    // ADMIN-only access is enforced by SecurityConfig's
    // .requestMatchers("/api/admin/**").hasRole("ADMIN") rule.
    // Matches the existing AdminController pattern (no method-level
    // @PreAuthorize duplication).

    private final MentorPoolService mentorPoolService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CourseMentorDTO>>> getMentorPool(
            @PathVariable Long courseId) {
        return ResponseEntity.ok(ApiResponse.success(
                mentorPoolService.getMentorPool(courseId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CourseMentorDTO>> addMentor(
            @PathVariable Long courseId, @RequestBody Map<String, Long> body) {
        Long userId = body.get("userId");
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        CourseMentorDTO added = mentorPoolService.addMentorToCourse(courseId, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Mentor added to pool", added));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse<Void>> removeMentor(
            @PathVariable Long courseId, @PathVariable Long userId) {
        mentorPoolService.removeMentorFromCourse(courseId, userId);
        return ResponseEntity.ok(ApiResponse.success(
                "Mentor removed (or deactivated if they had active students) from pool", null));
    }
}
