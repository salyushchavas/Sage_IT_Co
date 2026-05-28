package com.spire.backend.service;

import com.spire.backend.dto.DashboardSummaryDTO;
import com.spire.backend.dto.NextActionDTO;
import com.spire.backend.entity.Assignment;
import com.spire.backend.entity.Enrollment;
import com.spire.backend.entity.Lesson;
import com.spire.backend.entity.MentorAssignment;
import com.spire.backend.entity.Module;
import com.spire.backend.entity.Progress;
import com.spire.backend.entity.SessionRequest;
import com.spire.backend.repository.AssignmentRepository;
import com.spire.backend.repository.CertificateRepository;
import com.spire.backend.repository.EnrollmentRepository;
import com.spire.backend.repository.LessonRepository;
import com.spire.backend.repository.MentorAssignmentRepository;
import com.spire.backend.repository.ModuleRepository;
import com.spire.backend.repository.ProgressRepository;
import com.spire.backend.repository.SessionRequestRepository;
import com.spire.backend.repository.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes the student's "one next action" (PRODUCT.md) and the
 * supplementary dashboard summary. All logic stays here; the
 * controller is a thin wrapper.
 */
@Service
@RequiredArgsConstructor
public class NextActionService {

    private final EnrollmentRepository enrollmentRepository;
    private final LessonRepository lessonRepository;
    private final ModuleRepository moduleRepository;
    private final ProgressRepository progressRepository;
    private final SessionRequestRepository sessionRequestRepository;
    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final MentorAssignmentRepository mentorAssignmentRepository;
    private final CertificateRepository certificateRepository;

    @Transactional(readOnly = true)
    public NextActionDTO computeNextAction(Long userId) {
        LocalDateTime now = LocalDateTime.now();

        // a) SESSION_SOON — accepted session within 24h
        Optional<SessionRequest> soon = sessionRequestRepository
                .findByRequestedByIdOrderByRequestedAtDesc(userId).stream()
                .filter(s -> "ACCEPTED".equals(s.getStatus()))
                .filter(s -> s.getScheduledAt() != null)
                .filter(s -> s.getScheduledAt().isAfter(now))
                .filter(s -> s.getScheduledAt().isBefore(now.plusHours(24)))
                .min(Comparator.comparing(SessionRequest::getScheduledAt));
        if (soon.isPresent()) {
            SessionRequest s = soon.get();
            MentorAssignment ma = s.getMentorAssignment();
            return NextActionDTO.builder()
                    .type("SESSION_SOON")
                    .courseId(ma != null && ma.getEnrollment() != null && ma.getEnrollment().getCourse() != null
                            ? ma.getEnrollment().getCourse().getId() : null)
                    .courseTitle(ma != null && ma.getEnrollment() != null && ma.getEnrollment().getCourse() != null
                            ? ma.getEnrollment().getCourse().getTitle() : null)
                    .mentorName(ma != null && ma.getMentor() != null ? ma.getMentor().getFullName() : null)
                    .scheduledAt(s.getScheduledAt())
                    .meetingUrl(s.getMeetingUrl())
                    .build();
        }

        List<Enrollment> enrollments = enrollmentRepository.findByUserId(userId);

        // e) BROWSE_COURSES
        if (enrollments.isEmpty()) {
            return NextActionDTO.builder().type("BROWSE_COURSES").build();
        }

        // Compute per-enrollment progress + last-accessed (used by b/c/d/f)
        List<EnrollmentProgress> progresses = enrollments.stream()
                .map(e -> buildProgress(userId, e))
                .toList();

        // b) ASSIGNMENT_DUE — within 3 days, not submitted
        LocalDateTime dueCutoff = now.plusDays(3);
        Assignment soonestAssignment = null;
        Enrollment soonestEnrollment = null;
        for (EnrollmentProgress ep : progresses) {
            List<Assignment> as = assignmentRepository
                    .findByCourseIdOrderByCreatedAtDesc(ep.enrollment.getCourse().getId());
            for (Assignment a : as) {
                if (a.getDueDate() == null) continue;
                if (!a.getDueDate().isAfter(now)) continue; // past-due — skip
                if (a.getDueDate().isAfter(dueCutoff)) continue;
                if (submissionRepository.existsByAssignmentIdAndStudentId(a.getId(), userId)) continue;
                if (soonestAssignment == null
                        || a.getDueDate().isBefore(soonestAssignment.getDueDate())) {
                    soonestAssignment = a;
                    soonestEnrollment = ep.enrollment;
                }
            }
        }
        if (soonestAssignment != null) {
            return NextActionDTO.builder()
                    .type("ASSIGNMENT_DUE")
                    .courseId(soonestEnrollment.getCourse().getId())
                    .courseTitle(soonestEnrollment.getCourse().getTitle())
                    .assignmentId(soonestAssignment.getId())
                    .assignmentTitle(soonestAssignment.getTitle())
                    .dueDate(soonestAssignment.getDueDate())
                    .build();
        }

        // c) CONTINUE_COURSE — in-progress, pick most-recently-accessed
        Optional<EnrollmentProgress> inProgress = progresses.stream()
                .filter(p -> p.totalLessons > 0)
                .filter(p -> p.completedLessons > 0 && p.completedLessons < p.totalLessons)
                .max(Comparator.comparing(p -> Optional.ofNullable(p.lastAccessed)
                        .orElse(LocalDateTime.MIN)));
        if (inProgress.isPresent()) {
            EnrollmentProgress ep = inProgress.get();
            Optional<NextLesson> nl = findNextLesson(userId, ep.enrollment.getCourse().getId());
            return NextActionDTO.builder()
                    .type("CONTINUE_COURSE")
                    .courseId(ep.enrollment.getCourse().getId())
                    .courseTitle(ep.enrollment.getCourse().getTitle())
                    .nextLessonId(nl.map(n -> n.lesson.getId()).orElse(null))
                    .nextLessonTitle(nl.map(n -> n.lesson.getTitle()).orElse(null))
                    .moduleTitle(nl.map(n -> n.moduleTitle).orElse(null))
                    .progressPercent(percent(ep.completedLessons, ep.totalLessons))
                    .build();
        }

        // d) START_COURSE — enrolled but zero completed; pick newest enrollment
        Optional<EnrollmentProgress> notStarted = progresses.stream()
                .filter(p -> p.completedLessons == 0)
                .max(Comparator.comparing(p -> p.enrollment.getEnrolledAt()));
        if (notStarted.isPresent()) {
            EnrollmentProgress ep = notStarted.get();
            Optional<NextLesson> nl = findNextLesson(userId, ep.enrollment.getCourse().getId());
            String mentorName = mentorAssignmentRepository
                    .findByEnrollmentId(ep.enrollment.getId())
                    .map(MentorAssignment::getMentor)
                    .map(m -> m == null ? null : m.getFullName())
                    .orElse(null);
            return NextActionDTO.builder()
                    .type("START_COURSE")
                    .courseId(ep.enrollment.getCourse().getId())
                    .courseTitle(ep.enrollment.getCourse().getTitle())
                    .firstLessonId(nl.map(n -> n.lesson.getId()).orElse(null))
                    .firstLessonTitle(nl.map(n -> n.lesson.getTitle()).orElse(null))
                    .mentorName(mentorName)
                    .build();
        }

        // f) ALL_COMPLETE — every enrollment fully completed
        boolean allComplete = !progresses.isEmpty() && progresses.stream()
                .allMatch(p -> p.totalLessons > 0 && p.completedLessons >= p.totalLessons);
        if (allComplete) {
            int certCount = certificateRepository.findByUserId(userId).size();
            return NextActionDTO.builder()
                    .type("ALL_COMPLETE")
                    .completedCount(progresses.size())
                    .certificateCount(certCount)
                    .build();
        }

        // Defensive fallback — shouldn't happen, but never return a blank screen.
        return NextActionDTO.builder().type("BROWSE_COURSES").build();
    }

    @Transactional(readOnly = true)
    public DashboardSummaryDTO buildSummary(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<Enrollment> enrollments = enrollmentRepository.findByUserId(userId);

        List<DashboardSummaryDTO.CourseProgress> courses = enrollments.stream()
                .map(e -> {
                    EnrollmentProgress ep = buildProgress(userId, e);
                    return DashboardSummaryDTO.CourseProgress.builder()
                            .id(e.getCourse().getId())
                            .title(e.getCourse().getTitle())
                            .type(e.getCourse().getType())
                            .progressPercent(percent(ep.completedLessons, ep.totalLessons))
                            .completedLessons(ep.completedLessons)
                            .totalLessons(ep.totalLessons)
                            .lastAccessedAt(ep.lastAccessed)
                            .build();
                })
                .sorted(Comparator.comparing(
                        (DashboardSummaryDTO.CourseProgress c) -> Optional.ofNullable(c.getLastAccessedAt())
                                .orElse(LocalDateTime.MIN))
                        .reversed())
                .toList();

        List<DashboardSummaryDTO.UpcomingSession> upcoming = sessionRequestRepository
                .findByRequestedByIdOrderByRequestedAtDesc(userId).stream()
                .filter(s -> "ACCEPTED".equals(s.getStatus()))
                .filter(s -> s.getScheduledAt() != null && s.getScheduledAt().isAfter(now))
                .filter(s -> s.getScheduledAt().isBefore(now.plusDays(7)))
                .sorted(Comparator.comparing(SessionRequest::getScheduledAt))
                .limit(5)
                .map(s -> {
                    MentorAssignment ma = s.getMentorAssignment();
                    String courseTitle = ma != null && ma.getEnrollment() != null
                            && ma.getEnrollment().getCourse() != null
                            ? ma.getEnrollment().getCourse().getTitle() : null;
                    String mentorName = ma != null && ma.getMentor() != null
                            ? ma.getMentor().getFullName() : null;
                    return DashboardSummaryDTO.UpcomingSession.builder()
                            .sessionId(s.getId())
                            .courseTitle(courseTitle)
                            .mentorName(mentorName)
                            .scheduledAt(s.getScheduledAt())
                            .meetingUrl(s.getMeetingUrl())
                            .build();
                })
                .toList();

        // Recent activity: TODO when activity-event tracking lands.
        // Returning empty rather than fabricating entries.
        List<DashboardSummaryDTO.Activity> recent = List.of();

        // Streak: derived from Progress.streakDays (max across rows).
        int streak = progressRepository.findByUserId(userId).stream()
                .map(Progress::getStreakDays)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);

        return DashboardSummaryDTO.builder()
                .enrolledCourses(courses)
                .upcomingSessions(upcoming)
                .recentActivity(recent)
                .streakDays(streak)
                .build();
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private EnrollmentProgress buildProgress(Long userId, Enrollment e) {
        Long courseId = e.getCourse().getId();
        int totalLessons = lessonRepository.findByCourseIdOrderByOrderIndex(courseId).size();
        int completed = (int) progressRepository.countCompletedLessons(userId, courseId);
        LocalDateTime lastAccessed = progressRepository.findByUserIdAndCourseId(userId, courseId).stream()
                .map(Progress::getLastAccessed)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(e.getEnrolledAt());
        return new EnrollmentProgress(e, completed, totalLessons, lastAccessed);
    }

    private Optional<NextLesson> findNextLesson(Long userId, Long courseId) {
        List<Module> modules = moduleRepository.findByCourseIdOrderByOrderIndexAsc(courseId);
        List<Lesson> lessons = lessonRepository.findByCourseIdOrderByOrderIndex(courseId);
        Set<Long> completedIds = progressRepository.findByUserIdAndCourseId(userId, courseId).stream()
                .filter(p -> Boolean.TRUE.equals(p.getCompleted()))
                .filter(p -> p.getLesson() != null)
                .map(p -> p.getLesson().getId())
                .collect(Collectors.toSet());

        // Walk modules in order, then lessons in order. Orphans last.
        List<Lesson> orderedInModules = new ArrayList<>();
        for (Module m : modules) {
            List<Lesson> moduleLessons = lessons.stream()
                    .filter(l -> l.getModule() != null
                            && Objects.equals(l.getModule().getId(), m.getId()))
                    .sorted(Comparator.comparing(Lesson::getOrderIndex))
                    .toList();
            for (Lesson l : moduleLessons) {
                orderedInModules.add(l);
                if (!completedIds.contains(l.getId())) {
                    return Optional.of(new NextLesson(l, m.getTitle()));
                }
            }
        }
        // Orphan lessons (no module) — fall back in their declared order.
        List<Lesson> orphans = lessons.stream()
                .filter(l -> l.getModule() == null)
                .sorted(Comparator.comparing(Lesson::getOrderIndex))
                .toList();
        for (Lesson l : orphans) {
            if (!completedIds.contains(l.getId())) {
                return Optional.of(new NextLesson(l, null));
            }
        }
        return Optional.empty();
    }

    private static int percent(int completed, int total) {
        if (total <= 0) return 0;
        return (int) Math.round(100.0 * completed / total);
    }

    private record EnrollmentProgress(
            Enrollment enrollment, int completedLessons, int totalLessons, LocalDateTime lastAccessed) {}

    private record NextLesson(Lesson lesson, String moduleTitle) {}
}
