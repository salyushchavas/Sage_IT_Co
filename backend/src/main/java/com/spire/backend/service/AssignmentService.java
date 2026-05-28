package com.spire.backend.service;

import com.spire.backend.dto.AssignmentRequest;
import com.spire.backend.dto.GradeRequest;
import com.spire.backend.dto.SubmissionRequest;
import com.spire.backend.entity.*;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ProgressRepository progressRepository;
    private final RecordService recordService;
    private final CertificateService certificateService;

    // ─── Create Assignment ──────────────────────────────────────────

    @Transactional
    public Assignment createAssignment(Long courseId, AssignmentRequest dto, Long userId, boolean isAdmin) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        if (!isAdmin && !course.getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only create assignments for your own courses");
        }

        User creator = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Assignment.AssignmentType type = Assignment.AssignmentType.COURSE;
        Lesson lesson = null;

        if (dto.getAssignmentType() != null && "LESSON".equalsIgnoreCase(dto.getAssignmentType())) {
            type = Assignment.AssignmentType.LESSON;
            if (dto.getLessonId() == null) {
                throw new IllegalArgumentException("lessonId is required for LESSON type assignments");
            }
            lesson = lessonRepository.findById(dto.getLessonId())
                    .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", dto.getLessonId()));
            if (!lesson.getCourse().getId().equals(courseId)) {
                throw new IllegalArgumentException("Lesson does not belong to this course");
            }
        }

        Assignment assignment = Assignment.builder()
                .title(dto.getTitle())
                .description(dto.getDescription())
                .dueDate(dto.getDueDate())
                .assignmentType(type)
                .course(course)
                .lesson(lesson)
                .createdBy(creator)
                .build();

        log.info("Assignment created: '{}' type={} courseId={}", dto.getTitle(), type, courseId);
        return assignmentRepository.save(assignment);
    }

    // ─── Get Assignments (with completion-based unlock) ─────────────

    public List<Map<String, Object>> getAssignmentsWithAccess(Long courseId, Long userId, boolean isAdmin) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        boolean isInstructor = course.getInstructor().getId().equals(userId);
        if (!isAdmin && !isInstructor) {
            if (!enrollmentRepository.existsByUserIdAndCourseId(userId, courseId)) {
                // Not enrolled — return empty list instead of blocking access
                return List.of();
            }
        }

        List<Assignment> assignments = assignmentRepository.findByCourseIdOrderByCreatedAtDesc(courseId);

        // Instructor/admin sees everything unlocked
        if (isAdmin || isInstructor) {
            return assignments.stream().map(a -> buildAssignmentMap(a, true)).toList();
        }

        // Student — check completion-based access
        long totalLessons = lessonRepository.findByCourseIdOrderByOrderIndex(courseId).size();
        long completedLessons = progressRepository.countCompletedLessons(userId, courseId);
        boolean allLessonsCompleted = totalLessons > 0 && completedLessons >= totalLessons;

        return assignments.stream().map(a -> {
            boolean unlocked;
            if (a.getAssignmentType() == Assignment.AssignmentType.LESSON) {
                // Unlocked if the specific lesson is completed
                unlocked = a.getLesson() != null &&
                        progressRepository.existsByUserIdAndLessonIdAndCompletedTrue(userId, a.getLesson().getId());
            } else {
                // COURSE type — unlocked if all lessons completed
                unlocked = allLessonsCompleted;
            }
            return buildAssignmentMap(a, unlocked);
        }).toList();
    }

    private Map<String, Object> buildAssignmentMap(Assignment a, boolean unlocked) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", a.getId());
        map.put("title", a.getTitle());
        map.put("description", a.getDescription() != null ? a.getDescription() : "");
        map.put("dueDate", a.getDueDate() != null ? a.getDueDate().toString() : null);
        map.put("assignmentType", a.getAssignmentType().name());
        map.put("lessonId", a.getLesson() != null ? a.getLesson().getId() : null);
        map.put("courseId", a.getCourse().getId());
        map.put("createdAt", a.getCreatedAt().toString());
        map.put("unlocked", unlocked);
        return map;
    }

    // ─── Complete Lesson ────────────────────────────────────────────

    @Transactional
    public Progress completeLesson(Long lessonId, Long userId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", lessonId));

        // Allow: enrolled students, course instructor, or admin
        Long courseId = lesson.getCourse().getId();
        boolean isEnrolled = enrollmentRepository.existsByUserIdAndCourseId(userId, courseId);
        boolean isOwner = lesson.getCourse().getInstructor().getId().equals(userId);
        if (!isEnrolled && !isOwner) {
            throw new UnauthorizedException("You must be enrolled or be the course instructor");
        }

        // Find or create progress record
        Progress progress = progressRepository.findByUserIdAndLessonId(userId, lessonId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
                    return Progress.builder()
                            .user(user)
                            .course(lesson.getCourse())
                            .lesson(lesson)
                            .build();
                });

        if (Boolean.TRUE.equals(progress.getCompleted())) {
            log.info("Lesson {} already completed by user {}", lessonId, userId);
            return progress; // idempotent
        }

        progress.setCompleted(true);
        progress.setCompletionPercent(100.0);
        progress.setLastAccessed(LocalDateTime.now());

        Progress saved = progressRepository.save(progress);

        Map<String, Object> details = new HashMap<>();
        details.put("lessonId", lesson.getId());
        details.put("lessonTitle", lesson.getTitle());
        details.put("courseId", lesson.getCourse().getId());
        details.put("courseTitle", lesson.getCourse().getTitle());
        if (lesson.getModule() != null) {
            details.put("moduleId", lesson.getModule().getId());
            details.put("moduleTitle", lesson.getModule().getTitle());
        }
        recordService.record(userId, "LESSON_COMPLETED", RecordService.Category.LEARNING,
                "Completed lesson: " + lesson.getTitle(),
                "Completed lesson '" + lesson.getTitle() + "' in " + lesson.getCourse().getTitle(),
                details);

        // Course completion milestone — emit a record once the final
        // lesson clicks over so the audit trail captures graduation.
        // Then attempt cert auto-generation; the lenient sibling never
        // throws — if quizzes / assignments are still pending the
        // student can issue manually from the course page.
        long totalLessons = lessonRepository.findByCourseIdOrderByOrderIndex(courseId).size();
        long completedLessons = progressRepository.countCompletedLessons(userId, courseId);
        if (totalLessons > 0 && completedLessons >= totalLessons) {
            Map<String, Object> done = new HashMap<>();
            done.put("courseId", courseId);
            done.put("courseTitle", lesson.getCourse().getTitle());
            done.put("totalLessons", totalLessons);
            recordService.record(userId, "COURSE_COMPLETED", RecordService.Category.LEARNING,
                    "Completed course: " + lesson.getCourse().getTitle(),
                    "Completed all " + totalLessons + " lessons in '" + lesson.getCourse().getTitle() + "'",
                    done);
            certificateService.tryAutoGenerate(courseId, userId);
        }

        log.info("Lesson {} completed by user {}", lessonId, userId);
        return saved;
    }

    // ─── Submit Assignment ──────────────────────────────────────────

    @Transactional
    public Submission submitAssignment(Long assignmentId, SubmissionRequest dto, Long studentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", "id", assignmentId));

        boolean isEnrolled = enrollmentRepository.existsByUserIdAndCourseId(studentId, assignment.getCourse().getId());
        boolean isOwner = assignment.getCourse().getInstructor().getId().equals(studentId);
        if (!isEnrolled && !isOwner) {
            throw new UnauthorizedException("You must be enrolled or be the course instructor");
        }

        // Check if assignment is unlocked
        if (assignment.getAssignmentType() == Assignment.AssignmentType.LESSON) {
            if (assignment.getLesson() != null &&
                    !progressRepository.existsByUserIdAndLessonIdAndCompletedTrue(studentId, assignment.getLesson().getId())) {
                throw new UnauthorizedException("Complete the lesson before submitting this assignment");
            }
        } else {
            long total = lessonRepository.findByCourseIdOrderByOrderIndex(assignment.getCourse().getId()).size();
            long completed = progressRepository.countCompletedLessons(studentId, assignment.getCourse().getId());
            if (total > 0 && completed < total) {
                throw new UnauthorizedException("Complete all lessons before submitting this course assignment");
            }
        }

        if (submissionRepository.existsByAssignmentIdAndStudentId(assignmentId, studentId)) {
            throw new IllegalArgumentException("You have already submitted this assignment");
        }

        User student = userRepository.findById(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", studentId));

        Submission submission = Submission.builder()
                .assignment(assignment)
                .student(student)
                .content(dto.getContent())
                .build();

        Submission saved = submissionRepository.save(submission);

        Map<String, Object> details = new HashMap<>();
        details.put("assignmentId", assignment.getId());
        details.put("assignmentTitle", assignment.getTitle());
        details.put("courseId", assignment.getCourse().getId());
        details.put("courseTitle", assignment.getCourse().getTitle());
        recordService.record(studentId, "ASSIGNMENT_SUBMITTED", RecordService.Category.ASSESSMENT,
                "Submitted assignment: " + assignment.getTitle(),
                "Submitted '" + assignment.getTitle() + "' for course '" + assignment.getCourse().getTitle() + "'",
                details);

        return saved;
    }

    // ─── Get Submissions (instructor/admin) ─────────────────────────

    public List<Submission> getSubmissions(Long assignmentId, Long userId, boolean isAdmin) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", "id", assignmentId));

        if (!isAdmin && !assignment.getCourse().getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("Only the course instructor or admin can view submissions");
        }

        return submissionRepository.findByAssignmentId(assignmentId);
    }

    // ─── Grade Submission ───────────────────────────────────────────

    @Transactional
    public Submission gradeSubmission(Long submissionId, GradeRequest dto, Long userId, boolean isAdmin) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", "id", submissionId));

        if (!isAdmin && !submission.getAssignment().getCourse().getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("Only the course instructor or admin can grade submissions");
        }

        submission.setGrade(dto.getGrade());
        submission.setFeedback(dto.getFeedback());
        Submission graded = submissionRepository.save(submission);

        Map<String, Object> details = new HashMap<>();
        details.put("assignmentId", submission.getAssignment().getId());
        details.put("assignmentTitle", submission.getAssignment().getTitle());
        details.put("grade", dto.getGrade());
        details.put("feedback", dto.getFeedback());
        details.put("gradedBy", userId);
        recordService.record(submission.getStudent().getId(), "ASSIGNMENT_GRADED",
                RecordService.Category.ASSESSMENT,
                "Assignment graded: " + submission.getAssignment().getTitle(),
                "Grade: " + dto.getGrade() + " for assignment '" + submission.getAssignment().getTitle() + "'",
                details);

        return graded;
    }
}
