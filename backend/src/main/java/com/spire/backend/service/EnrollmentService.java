package com.spire.backend.service;

import com.spire.backend.dto.CourseDTO;
import com.spire.backend.dto.InstructorStudentDTO;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.Enrollment;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.CourseRepository;
import com.spire.backend.repository.EnrollmentRepository;
import com.spire.backend.repository.LessonRepository;
import com.spire.backend.repository.ModuleRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EnrollmentService {

    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final ModuleRepository moduleRepository;
    private final LessonRepository lessonRepository;
    private final MentorAssignmentService mentorAssignmentService;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;

    @Transactional
    public void enrollUser(Long userId, Long courseId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // Admin is a supervisor, not a student. Block enrollment so the
        // role can't accidentally land in courses/cart flows. Admins use
        // the preview path on /admin to view content without enrolling.
        if (user.getRole() != null && "ADMIN".equals(user.getRole().getName())) {
            throw new IllegalArgumentException(
                    "Admin accounts cannot enroll in courses. Use admin preview to view content.");
        }

        if (enrollmentRepository.existsByUserIdAndCourseId(userId, courseId)) {
            throw new IllegalArgumentException("Already enrolled in this course");
        }

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        // Checklist 5.4: a paid course is bought through the cart (it used
        // to enroll anyone for free through this call).
        if (isPaid(course)) {
            throw new IllegalStateException("\"" + course.getTitle()
                    + "\" is a paid course. Add it to your cart and check out to pay for it.");
        }
        enroll(user, course, null);
    }

    /** Checklist 5.4: a course that costs money — not marked free, with a price above zero. */
    public static boolean isPaid(Course course) {
        return !Boolean.TRUE.equals(course.getIsFree())
                && course.getPrice() != null && course.getPrice().signum() > 0;
    }

    /**
     * Checklist 5.4: enrollment once a course is paid for (or fully covered
     * by a coupon). Returns false when they're already enrolled.
     */
    @Transactional
    public boolean enrollAfterPayment(Long userId, Long courseId, String paymentReference) {
        if (enrollmentRepository.existsByUserIdAndCourseId(userId, courseId)) return false;
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));
        enroll(user, course, paymentReference);
        return true;
    }

    private void enroll(User user, Course course, String paymentReference) {
        Long userId = user.getId();
        Enrollment enrollment = Enrollment.builder()
                .user(user)
                .course(course)
                .build();

        Enrollment savedEnrollment = enrollmentRepository.save(enrollment);

        course.setEnrolledCount(course.getEnrolledCount() + 1);
        courseRepository.save(course);

        // Mentorship applies to type=COURSE only. Services (Resume Prep,
        // Interview Training, etc.) are self-paced video walk-throughs and
        // don't get a mentor assigned — see PRODUCT.md.
        if (!course.isService()) {
            // Auto-assign a mentor from the course's pool. If no mentor has
            // capacity, this still creates an assignment row — with mentor=null
            // and status=PENDING_ASSIGNMENT — so admin can fix the pool later.
            mentorAssignmentService.assignMentor(savedEnrollment);
        }

        Map<String, Object> details = new HashMap<>();
        details.put("courseId", course.getId());
        details.put("courseTitle", course.getTitle());
        details.put("courseType", course.getType());
        details.put("amountPaid", paymentReference == null ? java.math.BigDecimal.ZERO : course.getPrice());
        details.put("isFree", Boolean.TRUE.equals(course.getIsFree()));
        if (paymentReference != null) details.put("payment", paymentReference);
        recordService.record(userId, "COURSE_ENROLLED", RecordService.Category.LEARNING,
                "Enrolled in " + course.getTitle(),
                "Enrolled in course '" + course.getTitle() + "' (ID: " + course.getId() + ")",
                details);

        // Confirmation email — best-effort. Mentor name is left blank
        // when no mentor has capacity yet (assignment row still gets
        // created with mentor=null, and the mentor-assigned email
        // fires later when MentorPoolService back-fills).
        try {
            int lessonCount = lessonRepository.findByCourseIdOrderByOrderIndex(course.getId()).size();
            int moduleCount = moduleRepository.findByCourseIdOrderByOrderIndexAsc(course.getId()).size();
            String mentorName = null;
            if (!course.isService()) {
                var assignment = mentorAssignmentService.getAssignmentForEnrollment(savedEnrollment.getId());
                if (assignment.isPresent() && assignment.get().getMentor() != null) {
                    mentorName = assignment.get().getMentor().getFullName();
                }
            }
            emailTemplateService.sendEnrollmentEmail(user, course, lessonCount, moduleCount, mentorName);
        } catch (Exception ignored) {}
    }

    public List<CourseDTO> getUserEnrollments(Long userId) {
        return enrollmentRepository.findByUserId(userId).stream()
                .map(e -> CourseDTO.from(e.getCourse()))
                .collect(Collectors.toList());
    }

    public List<InstructorStudentDTO> getStudentsForInstructor(Long instructorId) {
        return enrollmentRepository.findByInstructorId(instructorId).stream()
                .map(InstructorStudentDTO::from)
                .collect(Collectors.toList());
    }

    public boolean isEnrolled(Long userId, Long courseId) {
        return enrollmentRepository.existsByUserIdAndCourseId(userId, courseId);
    }
}
