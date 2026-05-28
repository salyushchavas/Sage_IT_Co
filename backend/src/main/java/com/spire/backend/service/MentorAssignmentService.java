package com.spire.backend.service;

import com.spire.backend.dto.MentorAssignmentDTO;
import com.spire.backend.dto.MentorInfoDTO;
import com.spire.backend.entity.CourseMentor;
import com.spire.backend.entity.Enrollment;
import com.spire.backend.entity.MentorAssignment;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.CourseMentorRepository;
import com.spire.backend.repository.EnrollmentRepository;
import com.spire.backend.repository.MentorAssignmentRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MentorAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(MentorAssignmentService.class);

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PENDING = "PENDING_ASSIGNMENT";

    private final MentorAssignmentRepository mentorAssignmentRepository;
    private final MentorPoolService mentorPoolService;
    private final CourseMentorRepository courseMentorRepository;
    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final EmailTemplateService emailTemplateService;

    /**
     * Called from EnrollmentService after a new enrollment is saved.
     * Picks a mentor from the course's pool (load-balanced) and persists
     * the assignment. If no mentor has capacity, persists a placeholder
     * with mentor=null and status=PENDING_ASSIGNMENT — admin is expected
     * to add more mentors to the pool.
     */
    @Transactional
    public MentorAssignment assignMentor(Enrollment enrollment) {
        Long courseId = enrollment.getCourse().getId();
        Optional<CourseMentor> available = mentorPoolService.getAvailableMentor(courseId);

        MentorAssignment assignment;
        if (available.isPresent()) {
            assignment = MentorAssignment.builder()
                    .enrollment(enrollment)
                    .mentor(available.get().getUser())
                    .status(STATUS_ACTIVE)
                    .build();
        } else {
            log.warn("No available mentor for course {}, enrollment {} — created PENDING_ASSIGNMENT",
                    courseId, enrollment.getId());
            assignment = MentorAssignment.builder()
                    .enrollment(enrollment)
                    .mentor(null)
                    .status(STATUS_PENDING)
                    .build();
        }
        MentorAssignment saved = mentorAssignmentRepository.save(assignment);

        // "Meet your mentor" email — only when a real mentor was
        // assigned. PENDING_ASSIGNMENT slots get the email later
        // when MentorPoolService back-fills via promotePending().
        if (saved.getMentor() != null) {
            try {
                emailTemplateService.sendMentorAssignedEmail(
                        enrollment.getUser(), saved.getMentor(), enrollment.getCourse());
            } catch (Exception ignored) {}
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<MentorAssignment> getAssignmentForEnrollment(Long enrollmentId) {
        return mentorAssignmentRepository.findByEnrollmentId(enrollmentId);
    }

    /**
     * Retroactively assigns mentors to existing enrollments for a
     * course. Called from MentorPoolService after a new mentor is
     * added to the pool — students who enrolled before any mentor
     * existed get matched immediately instead of waiting for
     * support to manually clean things up.
     *
     * Handles two distinct populations:
     *   1. Enrollments with a MentorAssignment row stuck at
     *      PENDING_ASSIGNMENT (the field's there, mentor is null).
     *      EnrollmentService.enrollUser creates these when no
     *      mentor is available at signup time.
     *   2. Enrollments with NO MentorAssignment row at all. Seeded
     *      students fall in this bucket — DataSeeder.seedEnrollment
     *      writes Enrollment rows directly via the repository,
     *      bypassing the auto-assign path.
     *
     * Both paths use the same load-balancer
     * (MentorPoolService.getAvailableMentor) so capacity rules and
     * the "fewest current students wins" tiebreaker stay consistent
     * with new-enrollment auto-assign. Capacity is re-evaluated per
     * row, so a single mentor with room for 5 students doesn't get
     * assigned 50.
     *
     * Returns the total count of enrollments that ended up with an
     * ACTIVE mentor as a result of this call.
     */
    @Transactional
    public int fillPendingAssignmentsForCourse(Long courseId) {
        int promoted = 0;

        // Pass 1: existing PENDING_ASSIGNMENT rows — mentor swap.
        List<MentorAssignment> pending = mentorAssignmentRepository
                .findByEnrollment_Course_IdAndStatus(courseId, STATUS_PENDING);
        for (MentorAssignment assignment : pending) {
            Optional<CourseMentor> available = mentorPoolService.getAvailableMentor(courseId);
            if (available.isEmpty()) break; // no more capacity — leave the rest pending
            assignment.setMentor(available.get().getUser());
            assignment.setStatus(STATUS_ACTIVE);
            mentorAssignmentRepository.save(assignment);
            // Newly-matched student finally gets their "meet your mentor"
            // notice — same email the auto-assign path sends, just on a
            // delayed trigger.
            try {
                emailTemplateService.sendMentorAssignedEmail(
                        assignment.getEnrollment().getUser(),
                        assignment.getMentor(),
                        assignment.getEnrollment().getCourse());
            } catch (Exception ignored) {}
            promoted++;
        }

        // Pass 2: enrollments with no MentorAssignment row at all.
        // Catches seeded students and any pre-Step-2 legacy data.
        List<Enrollment> orphans = enrollmentRepository
                .findByCourseIdAndNoMentorAssignment(courseId);
        for (Enrollment enrollment : orphans) {
            Optional<CourseMentor> available = mentorPoolService.getAvailableMentor(courseId);
            // If the pool is exhausted we still create a PENDING row
            // so the next mentor-add can pick it up via Pass 1, and
            // the student's MentorCard renders the friendly
            // "Mentor coming soon" copy instead of erroring on a
            // missing assignment.
            MentorAssignment fresh = MentorAssignment.builder()
                    .enrollment(enrollment)
                    .mentor(available.map(CourseMentor::getUser).orElse(null))
                    .status(available.isPresent() ? STATUS_ACTIVE : STATUS_PENDING)
                    .build();
            mentorAssignmentRepository.save(fresh);
            if (available.isPresent()) {
                try {
                    emailTemplateService.sendMentorAssignedEmail(
                            enrollment.getUser(),
                            available.get().getUser(),
                            enrollment.getCourse());
                } catch (Exception ignored) {}
                promoted++;
            }
        }

        if (promoted > 0) {
            log.info("Retroactive mentor fill on course {} — assigned {} enrollment(s)",
                    courseId, promoted);
        }
        return promoted;
    }

    /**
     * Resolves "what's MY mentor for this course" in one call.
     * Throws if the user has no enrollment for the course (404),
     * or if the enrollment somehow has no mentor assignment row (shouldn't
     * happen post Step-2; would mean an enrollment was created before the
     * auto-assign logic shipped). PENDING_ASSIGNMENT is a valid response —
     * mentor name/email come back null, status carries the state.
     */
    @Transactional(readOnly = true)
    public MentorInfoDTO getMentorInfoForCourse(Long userId, Long courseId) {
        Enrollment enrollment = enrollmentRepository.findByUserIdAndCourseId(userId, courseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Enrollment", "userId+courseId", userId + "+" + courseId));

        MentorAssignment a = mentorAssignmentRepository.findByEnrollmentId(enrollment.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "MentorAssignment", "enrollmentId", enrollment.getId()));

        User mentor = a.getMentor();
        return MentorInfoDTO.builder()
                .enrollmentId(enrollment.getId())
                .assignmentId(a.getId())
                .mentorName(mentor != null ? mentor.getFullName() : null)
                .mentorEmail(mentor != null ? mentor.getEmail() : null)
                .status(a.getStatus())
                .build();
    }

    @Transactional(readOnly = true)
    public List<MentorAssignmentDTO> getStudentsForMentor(Long mentorId) {
        return mentorAssignmentRepository.findByMentorIdAndStatus(mentorId, STATUS_ACTIVE).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Admin reassigns a student to a different mentor.
     *
     * The new mentor must already be in the course's pool and must have
     * spare capacity (ACTIVE count < maxStudents).
     *
     * The existing assignment is updated in-place (mentor swap; status
     * stays ACTIVE). Note: mentor_assignments.enrollment_id is UNIQUE,
     * so we cannot keep an audit row with status=REASSIGNED for the same
     * enrollment — that would require an audit log table or relaxing the
     * UNIQUE constraint. Out of scope for this commit.
     */
    @Transactional
    public MentorAssignment reassignMentor(Long assignmentId, Long newMentorId) {
        MentorAssignment assignment = mentorAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("MentorAssignment", "id", assignmentId));

        User newMentor = userRepository.findById(newMentorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", newMentorId));

        Long courseId = assignment.getEnrollment().getCourse().getId();
        CourseMentor poolEntry = courseMentorRepository.findByCourseIdAndUserId(courseId, newMentorId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "New mentor is not in this course's pool"));

        if (!Boolean.TRUE.equals(poolEntry.getIsActive())) {
            throw new IllegalArgumentException("New mentor's pool entry is inactive");
        }

        long currentActive = mentorAssignmentRepository.countByMentorIdAndStatus(newMentorId, STATUS_ACTIVE);
        if (currentActive >= poolEntry.getMaxStudents()) {
            throw new IllegalArgumentException("New mentor is at capacity");
        }

        assignment.setMentor(newMentor);
        assignment.setStatus(STATUS_ACTIVE);
        return mentorAssignmentRepository.save(assignment);
    }

    private MentorAssignmentDTO toDTO(MentorAssignment a) {
        Enrollment e = a.getEnrollment();
        User student = e != null ? e.getUser() : null;
        User mentor = a.getMentor();
        return MentorAssignmentDTO.builder()
                .id(a.getId())
                .enrollmentId(e != null ? e.getId() : null)
                .studentName(student != null ? student.getFullName() : null)
                .studentEmail(student != null ? student.getEmail() : null)
                .courseTitle(e != null && e.getCourse() != null ? e.getCourse().getTitle() : null)
                .mentorName(mentor != null ? mentor.getFullName() : null)
                .mentorEmail(mentor != null ? mentor.getEmail() : null)
                .assignedAt(a.getAssignedAt())
                .status(a.getStatus())
                .build();
    }
}
