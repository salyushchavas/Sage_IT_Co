package com.spire.backend.service;

import com.spire.backend.dto.CourseMentorDTO;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.CourseMentor;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.CourseMentorRepository;
import com.spire.backend.repository.CourseRepository;
import com.spire.backend.repository.MentorAssignmentRepository;
import com.spire.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class MentorPoolService {

    private static final Logger log = LoggerFactory.getLogger(MentorPoolService.class);

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final CourseMentorRepository courseMentorRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    // Needed for active-student counts (capacity checks + DTO mapping).
    private final MentorAssignmentRepository mentorAssignmentRepository;
    private final MentorAssignmentService mentorAssignmentService;

    /**
     * Manual constructor (no Lombok @RequiredArgsConstructor) so we
     * can put @Lazy on the MentorAssignmentService parameter.
     *
     * The cycle to break:
     *   MentorAssignmentService → MentorPoolService.getAvailableMentor
     *   MentorPoolService       → MentorAssignmentService.fillPending…
     *
     * Lombok's @RequiredArgsConstructor doesn't propagate field-level
     * annotations to the generated constructor parameters, so a
     * field-level @Lazy gets ignored at injection time and Spring
     * tries to resolve the bean eagerly — which throws the circular-
     * reference error at startup. Putting @Lazy on the parameter
     * itself makes Spring inject a CGLIB proxy and resolve the real
     * bean only when a method on it is called.
     */
    @Autowired
    public MentorPoolService(
            CourseMentorRepository courseMentorRepository,
            CourseRepository courseRepository,
            UserRepository userRepository,
            MentorAssignmentRepository mentorAssignmentRepository,
            @Lazy MentorAssignmentService mentorAssignmentService) {
        this.courseMentorRepository = courseMentorRepository;
        this.courseRepository = courseRepository;
        this.userRepository = userRepository;
        this.mentorAssignmentRepository = mentorAssignmentRepository;
        this.mentorAssignmentService = mentorAssignmentService;
    }

    @Transactional
    public CourseMentorDTO addMentorToCourse(Long courseId, Long userId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (user.getRole() == null || !"INSTRUCTOR".equals(user.getRole().getName())) {
            throw new IllegalArgumentException("User must have INSTRUCTOR role to be added to a mentor pool");
        }

        if (courseMentorRepository.existsByCourseIdAndUserId(courseId, userId)) {
            throw new IllegalArgumentException("This mentor is already in the course's pool");
        }

        // Note: we deliberately don't check the 10-student global cap here.
        // A mentor may be added to a pool even if currently at capacity — they
        // just won't be picked by getAvailableMentor until a slot opens up.
        CourseMentor saved = courseMentorRepository.save(CourseMentor.builder()
                .course(course)
                .user(user)
                .build());

        // Retroactive fill: any students who enrolled in this course
        // before any mentor existed have a MentorAssignment row stuck
        // at status=PENDING_ASSIGNMENT. Now that we have a mentor with
        // capacity, walk those rows and promote as many as the pool
        // can absorb. Failures here shouldn't roll back the pool add —
        // wrap so a fill failure surfaces as a log line, not a 500.
        try {
            int promoted = mentorAssignmentService.fillPendingAssignmentsForCourse(courseId);
            if (promoted > 0) {
                log.info("Mentor {} added to course {} pool — retroactively assigned to {} pending enrollment(s)",
                        user.getFullName(), courseId, promoted);
            }
        } catch (Exception e) {
            log.error("Pool add succeeded but retroactive fill failed for course {}", courseId, e);
        }

        return toDTO(saved);
    }

    @Transactional
    public void removeMentorFromCourse(Long courseId, Long userId) {
        CourseMentor cm = courseMentorRepository.findByCourseIdAndUserId(courseId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CourseMentor", "courseId+userId", courseId + "+" + userId));

        // Don't orphan students mid-course. If this mentor has any ACTIVE
        // assignments in this course, deactivate the pool entry instead of
        // deleting it. Admin must reassign those students first.
        long activeInThisCourse = mentorAssignmentRepository
                .findByMentorIdAndStatus(userId, STATUS_ACTIVE).stream()
                .filter(a -> a.getEnrollment() != null
                        && a.getEnrollment().getCourse() != null
                        && a.getEnrollment().getCourse().getId().equals(courseId))
                .count();

        if (activeInThisCourse > 0) {
            cm.setIsActive(false);
            courseMentorRepository.save(cm);
        } else {
            courseMentorRepository.delete(cm);
        }
    }

    @Transactional(readOnly = true)
    public List<CourseMentorDTO> getMentorPool(Long courseId) {
        return courseMentorRepository.findByCourseIdAndIsActiveTrue(courseId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Pick a mentor from the course's pool with capacity, load-balanced.
     *
     * Capacity rule: a mentor is "full" when their count of ACTIVE assignments
     * across ALL courses reaches their maxStudents (default 10) — the cap is
     * global, not per-course.
     *
     * Tiebreaker when several mentors have capacity: pick the one with the
     * fewest active students.
     */
    @Transactional(readOnly = true)
    public Optional<CourseMentor> getAvailableMentor(Long courseId) {
        return courseMentorRepository.findByCourseIdAndIsActiveTrue(courseId).stream()
                .map(cm -> Map.entry(cm,
                        mentorAssignmentRepository.countByMentorIdAndStatus(
                                cm.getUser().getId(), STATUS_ACTIVE)))
                .filter(e -> e.getValue() < e.getKey().getMaxStudents())
                .min(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey);
    }

    private CourseMentorDTO toDTO(CourseMentor cm) {
        long activeCount = mentorAssignmentRepository
                .countByMentorIdAndStatus(cm.getUser().getId(), STATUS_ACTIVE);
        return CourseMentorDTO.builder()
                .id(cm.getId())
                .courseId(cm.getCourse().getId())
                .mentorId(cm.getUser().getId())
                .mentorName(cm.getUser().getFullName())
                .mentorEmail(cm.getUser().getEmail())
                .activeStudentCount((int) activeCount)
                .maxStudents(cm.getMaxStudents())
                .isActive(cm.getIsActive())
                .build();
    }
}
