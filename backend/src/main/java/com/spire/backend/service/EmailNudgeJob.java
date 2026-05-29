package com.spire.backend.service;

import com.spire.backend.entity.Enrollment;
import com.spire.backend.entity.User;
import com.spire.backend.repository.EnrollmentRepository;
import com.spire.backend.repository.LessonRepository;
import com.spire.backend.repository.ProgressRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sends a single "we miss you" email per user per fortnight when
 * they've been silent on the platform for 7+ days. Runs nightly at
 * 03:00 UTC (08:30 IST) so the email lands in the morning local time
 * for our primary student base. The repository query already
 * combines the inactivity + throttle predicates so this class stays
 * small.
 *
 * Lives in its own class (not on EmailTemplateService) so the cron
 * lifecycle is easy to find and to disable per env if needed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailNudgeJob {

    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ProgressRepository progressRepository;
    private final LessonRepository lessonRepository;
    private final EmailTemplateService emailTemplateService;
    private final EmailService emailService;
    private final MentorAssignmentService mentorAssignmentService;

    @Value("${app.url:https://sageitco.com}")
    private String appUrl;

    @Value("${app.nudge.inactiveDays:7}")
    private int inactiveDays;

    @Value("${app.nudge.throttleDays:14}")
    private int throttleDays;

    /**
     * Cron: every day at 03:00 UTC ≈ 08:30 IST. Skips entirely when
     * SMTP isn't configured, so dev/staging don't burn through Gmail
     * quotas testing nothing.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void sendInactiveNudges() {
        if (!emailService.isConfigured()) {
            log.debug("Skipping inactive-nudge job — mail not configured");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime inactiveCutoff = now.minusDays(inactiveDays);
        LocalDateTime throttleCutoff = now.minusDays(throttleDays);

        List<User> candidates = userRepository.findInactiveCandidates(inactiveCutoff, throttleCutoff);
        log.info("Inactive-nudge job: {} candidate(s) to email", candidates.size());

        int sent = 0;
        for (User user : candidates) {
            try {
                if (sendNudgeFor(user)) {
                    user.setLastNudgeSentAt(now);
                    userRepository.save(user);
                    sent++;
                }
            } catch (Exception e) {
                log.warn("Skipped nudge for user {}: {}", user.getId(), e.getMessage());
            }
        }
        log.info("Inactive-nudge job: emails sent = {}", sent);
    }

    /**
     * Picks the user's most recent enrollment, computes progress, and
     * fires the nudge. Returns false (and logs) if the user has no
     * eligible course — should be rare since the repo query already
     * filters to enrolled users, but handles edge cases like a user
     * whose only enrollment is on a deleted course.
     */
    private boolean sendNudgeFor(User user) {
        List<Enrollment> enrollments = enrollmentRepository.findByUserId(user.getId());
        if (enrollments.isEmpty()) return false;

        // Pick the enrollment for the course with the most progress —
        // that's the one we want to nudge them back to. Ties broken
        // by most recent enrollment.
        Enrollment best = null;
        int bestPct = -1;
        for (Enrollment e : enrollments) {
            if (e.getCourse() == null || e.getCourse().isService()) continue;
            int pct = progressPercentFor(user.getId(), e.getCourse().getId());
            if (pct > bestPct) {
                bestPct = pct;
                best = e;
            }
        }
        if (best == null) return false;

        Long courseId = best.getCourse().getId();
        String courseTitle = best.getCourse().getTitle();
        String mentorName = mentorAssignmentService.getAssignmentForEnrollment(best.getId())
                .map(a -> a.getMentor() != null ? a.getMentor().getFullName() : null)
                .orElse(null);
        Long nextLessonId = nextUncompletedLesson(user.getId(), courseId);
        String lessonUrl = nextLessonId != null
                ? appUrl + "/learn/" + courseId + "/" + nextLessonId
                : appUrl + "/courses/" + courseId;

        emailTemplateService.sendInactiveNudgeEmail(
                user, courseTitle, Math.max(0, bestPct), mentorName, lessonUrl);
        return true;
    }

    private int progressPercentFor(Long userId, Long courseId) {
        long total = lessonRepository.findByCourseIdOrderByOrderIndex(courseId).size();
        if (total == 0) return 0;
        long done = progressRepository.countCompletedLessons(userId, courseId);
        return (int) Math.round((done * 100.0) / total);
    }

    /**
     * First lesson in {@code orderIndex} order that isn't yet marked
     * complete — the email's CTA deep-links straight to it. Falls
     * back to the course page when every lesson is somehow already
     * done (in which case the cert-eligible nudge would be more
     * appropriate, but we don't model that today).
     */
    private Long nextUncompletedLesson(Long userId, Long courseId) {
        var lessons = lessonRepository.findByCourseIdOrderByOrderIndex(courseId);
        for (var lesson : lessons) {
            var p = progressRepository.findByUserIdAndLessonId(userId, lesson.getId());
            if (p.isEmpty() || !Boolean.TRUE.equals(p.get().getCompleted())) {
                return lesson.getId();
            }
        }
        return null;
    }
}
