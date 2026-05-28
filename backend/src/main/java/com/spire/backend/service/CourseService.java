package com.spire.backend.service;

import com.spire.backend.dto.CourseDTO;
import com.spire.backend.dto.CourseRequest;
import java.math.BigDecimal;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.CourseRepository;
import com.spire.backend.repository.EnrollmentRepository;
import com.spire.backend.repository.LessonRepository;
import com.spire.backend.repository.ModuleRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final ModuleRepository moduleRepository;
    private final LessonRepository lessonRepository;
    private final EnrollmentRepository enrollmentRepository;

    public List<CourseDTO> getAllCourses() {
        return getAllCourses("COURSE");
    }

    /**
     * Public listing filtered by type. type='COURSE' returns regular
     * learning courses; type='SERVICE' returns Resume Prep / Interview
     * Training / etc. Default at the controller is 'COURSE' for
     * backward compatibility with existing /api/courses callers.
     */
    public List<CourseDTO> getAllCourses(String type) {
        return courseRepository.findByTypeAndIsPublished(type, true).stream()
                .map(CourseDTO::from)
                .collect(Collectors.toList());
    }

    public List<CourseDTO> getAllCoursesAdmin() {
        return courseRepository.findAll().stream()
                .map(CourseDTO::from)
                .collect(Collectors.toList());
    }

    public CourseDTO getCourseById(Long id) {
        Course course = courseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", id));
        return CourseDTO.from(course);
    }

    public List<CourseDTO> getCoursesByLevel(String level) {
        Course.Level courseLevel = Course.Level.valueOf(level.toUpperCase());
        return courseRepository.findByLevelAndIsPublished(courseLevel, true).stream()
                .map(CourseDTO::from)
                .collect(Collectors.toList());
    }

    public List<CourseDTO> getCoursesByInstructor(Long instructorId) {
        return courseRepository.findByInstructorId(instructorId).stream()
                .map(c -> {
                    CourseDTO dto = CourseDTO.from(c);
                    // Cheap count fetch — instructor dashboards need this
                    // to render the "X modules · Y lessons" hint, and
                    // adding it here saves an N+1 round trip from the
                    // frontend.
                    dto.setModulesCount(
                            moduleRepository.findByCourseIdOrderByOrderIndexAsc(c.getId()).size());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    public List<CourseDTO> searchCourses(String query) {
        return courseRepository.searchByTitle(query).stream()
                .map(CourseDTO::from)
                .collect(Collectors.toList());
    }

    /**
     * Create course — instructor is set from authenticated user, NOT from request body.
     * Slug is auto-generated from title.
     */
    @Transactional
    public CourseDTO createCourse(CourseRequest dto, Long instructorId) {
        User instructor = userRepository.findById(instructorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", instructorId));

        String slug = dto.getTitle().toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        String type = (dto.getType() != null && !dto.getType().isBlank())
                ? dto.getType().toUpperCase() : "COURSE";

        // Resolve trainer for type=SERVICE. Optional — admin can create the
        // service first and assign a trainer later.
        User trainer = null;
        if ("SERVICE".equals(type) && dto.getTrainerId() != null) {
            trainer = userRepository.findById(dto.getTrainerId())
                    .orElseThrow(() -> new ResourceNotFoundException("User", "id", dto.getTrainerId()));
        }

        Course course = Course.builder()
                .title(dto.getTitle())
                .slug(slug)
                .description(dto.getDescription())
                .shortDescription(dto.getShortDescription())
                .level(dto.getLevel() != null ? Course.Level.valueOf(dto.getLevel().toUpperCase()) : Course.Level.BEGINNER)
                .price(dto.getPrice() != null ? dto.getPrice() : BigDecimal.ZERO)
                .isFree(dto.getPrice() == null || dto.getPrice().compareTo(BigDecimal.ZERO) <= 0)
                .durationHours(dto.getDurationHours())
                .thumbnailUrl(dto.getThumbnailUrl())
                .instructor(instructor)   // Server-side only — never from request
                .type(type)
                .trainer(trainer)
                .category(dto.getCategory())
                .tags(dto.getTags())
                .isPublished(dto.getIsPublished() != null ? dto.getIsPublished() : false)
                .build();

        return CourseDTO.from(courseRepository.save(course));
    }

    /**
     * Update course — service-level ownership check (defense-in-depth).
     * Admin can update any course. Instructor can only update own courses.
     */
    @Transactional
    public CourseDTO updateCourse(Long courseId, CourseRequest dto, Long userId, boolean isAdmin) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        // Service-layer ownership check (even if controller already checked via @PreAuthorize)
        if (!isAdmin && !course.getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only update your own courses");
        }

        if (dto.getTitle() != null) course.setTitle(dto.getTitle());
        if (dto.getDescription() != null) course.setDescription(dto.getDescription());
        if (dto.getShortDescription() != null) course.setShortDescription(dto.getShortDescription());
        if (dto.getLevel() != null) course.setLevel(Course.Level.valueOf(dto.getLevel().toUpperCase()));
        if (dto.getPrice() != null) {
            course.setPrice(dto.getPrice());
            // Auto-derive isFree from price
            course.setIsFree(dto.getPrice().compareTo(BigDecimal.ZERO) <= 0);
        }
        if (dto.getDurationHours() != null) course.setDurationHours(dto.getDurationHours());
        if (dto.getThumbnailUrl() != null) course.setThumbnailUrl(dto.getThumbnailUrl());
        if (dto.getCategory() != null) course.setCategory(dto.getCategory());
        if (dto.getTags() != null) course.setTags(dto.getTags());
        if (dto.getIsPublished() != null) course.setIsPublished(dto.getIsPublished());

        return CourseDTO.from(courseRepository.save(course));
    }

    /**
     * Delete course — service-layer ownership check (defense-in-depth).
     *
     * Refuses to delete a course that has any enrollments — students
     * who paid for it would lose access without warning. Admin bypasses
     * this guard since they sometimes need to clean up bad data.
     */
    @Transactional
    public void deleteCourse(Long courseId, Long userId, boolean isAdmin) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        if (!isAdmin && !course.getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only delete your own courses");
        }

        if (!isAdmin) {
            long enrollments = enrollmentRepository.countByCourseId(courseId);
            if (enrollments > 0) {
                throw new IllegalArgumentException(
                        "Cannot delete a course with active enrollments. Unpublish it instead.");
            }
        }

        courseRepository.delete(course);
    }

    /**
     * Publish course — sets isPublished = true after a content
     * readiness check. The frontend uses {@link #checkPublishReadiness}
     * to surface what's missing without trying the publish call, so the
     * card on /instructor can show a "Missing: …" hint inline.
     */
    @Transactional
    public CourseDTO publishCourse(Long courseId, Long userId, boolean isAdmin) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        if (!isAdmin && !course.getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only publish your own courses");
        }

        List<String> missing = collectMissingForPublish(course);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Course isn't ready to publish — " + String.join("; ", missing));
        }

        course.setIsPublished(true);
        return CourseDTO.from(courseRepository.save(course));
    }

    /**
     * Returns the list of human-readable items the course is still
     * missing before it can be published. Empty list means it's
     * ready. Used by the instructor dashboard's draft cards.
     */
    @Transactional(readOnly = true)
    public List<String> checkPublishReadiness(Long courseId, Long userId, boolean isAdmin) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));
        if (!isAdmin && !course.getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only view publish readiness for your own courses");
        }
        return collectMissingForPublish(course);
    }

    private List<String> collectMissingForPublish(Course course) {
        List<String> missing = new ArrayList<>();
        if (course.getTitle() == null || course.getTitle().isBlank()) {
            missing.add("title is empty");
        }
        if (course.getDescription() == null || course.getDescription().isBlank()) {
            missing.add("description is empty");
        }
        if (course.getPrice() == null
                || course.getPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            missing.add("price must be greater than zero");
        }
        var modules = moduleRepository.findByCourseIdOrderByOrderIndexAsc(course.getId());
        if (modules.isEmpty()) {
            missing.add("needs at least 1 module");
        } else {
            // Identify modules that have zero lessons attached. Each is
            // a publish blocker — a module without lessons would render
            // as an empty section to students.
            var courseLessons = lessonRepository.findByCourseIdOrderByOrderIndex(course.getId());
            for (var m : modules) {
                long lessonsInModule = courseLessons.stream()
                        .filter(l -> l.getModule() != null
                                && m.getId().equals(l.getModule().getId()))
                        .count();
                if (lessonsInModule == 0) {
                    missing.add("module \"" + m.getTitle() + "\" has no lessons");
                }
            }
        }
        return missing;
    }

    /**
     * Unpublish course — sets isPublished = false.
     */
    @Transactional
    public CourseDTO unpublishCourse(Long courseId, Long userId, boolean isAdmin) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        if (!isAdmin && !course.getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only unpublish your own courses");
        }

        course.setIsPublished(false);
        return CourseDTO.from(courseRepository.save(course));
    }
}
