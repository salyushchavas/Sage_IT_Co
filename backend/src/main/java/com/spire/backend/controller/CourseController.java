package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.CourseDTO;
import com.spire.backend.dto.CourseProgressDTO;
import com.spire.backend.dto.CourseRequest;
import com.spire.backend.dto.LessonDTO;
import com.spire.backend.entity.Lesson;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.LessonRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.service.CourseService;
import com.spire.backend.service.EnrollmentService;
import com.spire.backend.service.ProgressService;
import com.spire.backend.service.ThumbnailUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;
    private final LessonRepository lessonRepository;
    private final EnrollmentService enrollmentService;
    private final UserRepository userRepository;
    private final ProgressService progressService;
    private final ThumbnailUploadService thumbnailUploadService;

    // ─── Instructor's own courses ─────────────────────────────────

    @GetMapping("/my")
    @PreAuthorize("hasRole('INSTRUCTOR') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<CourseDTO>>> getMyCourses(Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        List<CourseDTO> courses = courseService.getCoursesByInstructor(userId);
        return ResponseEntity.ok(ApiResponse.success(courses));
    }

    // ─── Public endpoints ───────────────────────────────────────────

    @GetMapping
    public ResponseEntity<ApiResponse<List<CourseDTO>>> getAllCourses(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "COURSE") String type) {
        // Default type=COURSE keeps existing /api/courses callers backward
        // compatible. Pass ?type=SERVICE to list services. Search and level
        // results are post-filtered by type to avoid mixing the two.
        List<CourseDTO> courses;
        if (search != null && !search.isBlank()) {
            courses = courseService.searchCourses(search).stream()
                    .filter(c -> type.equals(c.getType()))
                    .toList();
        } else if (level != null && !level.isBlank()) {
            courses = courseService.getCoursesByLevel(level).stream()
                    .filter(c -> type.equals(c.getType()))
                    .toList();
        } else {
            courses = courseService.getAllCourses(type);
        }
        return ResponseEntity.ok(ApiResponse.success(courses));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CourseDTO>> getCourse(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(courseService.getCourseById(id)));
    }

    // ─── Course progress (enrolled student only) ────────────────────
    //
    // courseId-based instead of enrollmentId-based: the frontend
    // already has courseId in every URL that needs progress, and the
    // service resolves the enrollment via (userId, courseId) and 403s
    // if there isn't one.
    @GetMapping("/{courseId}/progress")
    public ResponseEntity<ApiResponse<CourseProgressDTO>> getCourseProgress(
            @PathVariable Long courseId, Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                progressService.getCourseProgressDetail(userId, courseId)));
    }

    @GetMapping("/{id}/lessons")
    public ResponseEntity<ApiResponse<List<LessonDTO>>> getCourseLessons(
            @PathVariable Long id, Authentication authentication) {
        List<Lesson> lessons = lessonRepository.findByCourseIdOrderByOrderIndex(id);

        boolean hasAccess = false;
        if (authentication != null) {
            Long userId = Long.parseLong(authentication.getPrincipal().toString());
            // Admins supervise the platform — they get full content access
            // on every course without enrolling. Same goes for the course's
            // owning instructor (already covered by the @PreAuthorize on
            // edit endpoints; treated as access here too).
            boolean isAdmin = authentication.getAuthorities()
                    .contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
            hasAccess = isAdmin || enrollmentService.isEnrolled(userId, id);
        }

        boolean finalHasAccess = hasAccess;
        List<LessonDTO> dtos = lessons.stream()
                .map(l -> LessonDTO.from(l, finalHasAccess || Boolean.TRUE.equals(l.getIsFree())))
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    // ─── Create course (ADMIN or approved INSTRUCTOR) ───────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR', 'TRAINER')")
    public ResponseEntity<ApiResponse<CourseDTO>> createCourse(
            @Valid @RequestBody CourseRequest dto, Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());

        // Instructors must be approved
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if ("INSTRUCTOR".equals(user.getRole().getName()) && !Boolean.TRUE.equals(user.getInstructorApproved())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Your instructor account is pending approval."));
        }

        // Trainers can only create services. Instructors can only create courses.
        // Admins can create either.
        String roleName = user.getRole().getName();
        String requestedType = (dto.getType() != null && !dto.getType().isBlank())
                ? dto.getType().toUpperCase() : "COURSE";
        if ("TRAINER".equals(roleName) && !"SERVICE".equals(requestedType)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Trainers can only create services."));
        }
        if ("INSTRUCTOR".equals(roleName) && !"COURSE".equals(requestedType)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Instructors can only create courses."));
        }

        CourseDTO created = courseService.createCourse(dto, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Course created", created));
    }

    // ─── Update course (ADMIN or course owner INSTRUCTOR) ───────────

    @PutMapping("/{courseId}")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('INSTRUCTOR') and @courseSecurity.isOwner(#courseId, authentication))")
    public ResponseEntity<ApiResponse<CourseDTO>> updateCourse(
            @PathVariable Long courseId,
            @Valid @RequestBody CourseRequest dto,
            Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        boolean isAdmin = authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        CourseDTO updated = courseService.updateCourse(courseId, dto, userId, isAdmin);
        return ResponseEntity.ok(ApiResponse.success("Course updated", updated));
    }

    // ─── Delete course (ADMIN or course owner INSTRUCTOR) ───────────

    @DeleteMapping("/{courseId}")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('INSTRUCTOR') and @courseSecurity.isOwner(#courseId, authentication))")
    public ResponseEntity<ApiResponse<Void>> deleteCourse(
            @PathVariable Long courseId,
            Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        boolean isAdmin = authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        courseService.deleteCourse(courseId, userId, isAdmin);
        return ResponseEntity.ok(ApiResponse.success("Course deleted", null));
    }

    // ─── Publish course (ADMIN or course owner INSTRUCTOR) ──────────

    @PutMapping("/{courseId}/publish")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('INSTRUCTOR') and @courseSecurity.isOwner(#courseId, authentication))")
    public ResponseEntity<ApiResponse<CourseDTO>> publishCourse(
            @PathVariable Long courseId,
            Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        boolean isAdmin = authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        CourseDTO published = courseService.publishCourse(courseId, userId, isAdmin);
        return ResponseEntity.ok(ApiResponse.success("Course published successfully", published));
    }

    // ─── Unpublish course ───────────────────────────────────────────

    @PutMapping("/{courseId}/unpublish")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('INSTRUCTOR') and @courseSecurity.isOwner(#courseId, authentication))")
    public ResponseEntity<ApiResponse<CourseDTO>> unpublishCourse(
            @PathVariable Long courseId,
            Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        boolean isAdmin = authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        CourseDTO unpublished = courseService.unpublishCourse(courseId, userId, isAdmin);
        return ResponseEntity.ok(ApiResponse.success("Course unpublished", unpublished));
    }

    // ─── Publish readiness check ────────────────────────────────────
    // Used by the instructor dashboard to show "Missing: …" hints on
    // draft cards without forcing the user to click Publish first.

    @GetMapping("/{courseId}/publish-readiness")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('INSTRUCTOR') and @courseSecurity.isOwner(#courseId, authentication))")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publishReadiness(
            @PathVariable Long courseId,
            Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        boolean isAdmin = authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
        List<String> missing = courseService.checkPublishReadiness(courseId, userId, isAdmin);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "ready", missing.isEmpty(),
                "missing", missing
        )));
    }

    // ─── Thumbnail upload ───────────────────────────────────────────

    @PostMapping("/{courseId}/thumbnail")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('INSTRUCTOR') and @courseSecurity.isOwner(#courseId, authentication))")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadThumbnail(
            @PathVariable Long courseId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        boolean isAdmin = authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
        String url = thumbnailUploadService.uploadThumbnail(courseId, file, userId, isAdmin);
        return ResponseEntity.ok(ApiResponse.success("Thumbnail uploaded",
                Map.of("thumbnailUrl", url)));
    }
}
