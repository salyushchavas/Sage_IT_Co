package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.CourseDTO;
import com.spire.backend.dto.MentorInfoDTO;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.service.EnrollmentService;
import com.spire.backend.service.MentorAssignmentService;
import com.spire.backend.service.ProfileCompletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/enrollments")
@RequiredArgsConstructor
public class EnrollmentController {

    private final EnrollmentService enrollmentService;
    private final MentorAssignmentService mentorAssignmentService;
    private final ProfileCompletionService profileCompletionService;
    private final UserRepository userRepository;

    @PostMapping("/{courseId}")
    public ResponseEntity<ApiResponse<?>> enroll(
            Authentication authentication, @PathVariable Long courseId) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());

        // Phase 1C gate — purchase / enroll is blocked until the
        // participant finishes the six-step profile flow. Browsing
        // is unaffected. The 403 ships the same ProfileCompletionDto
        // the dashboard banner uses so the frontend can render the
        // gate modal without a follow-up call.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (!profileCompletionService.canEnrollInCourses(user)) {
            Map<String, Object> body = Map.of(
                    "message", "Complete your profile to enroll",
                    "completion", profileCompletionService.getStatus(user)
            );
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.<Map<String, Object>>error("PROFILE_INCOMPLETE", body));
        }

        enrollmentService.enrollUser(userId, courseId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Enrolled successfully", null));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CourseDTO>>> getEnrollments(Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(enrollmentService.getUserEnrollments(userId)));
    }

    // Resolves the current user's mentor info for a given course (via enrollment lookup).
    // Returns 404 if the user is not enrolled in the course.
    @GetMapping("/courses/{courseId}/mentor")
    public ResponseEntity<ApiResponse<MentorInfoDTO>> getMyMentorForCourse(
            @PathVariable Long courseId, Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                mentorAssignmentService.getMentorInfoForCourse(userId, courseId)));
    }
}
