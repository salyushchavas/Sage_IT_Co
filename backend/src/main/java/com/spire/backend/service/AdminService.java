package com.spire.backend.service;

import com.spire.backend.dto.AdminEnrollmentRow;
import com.spire.backend.dto.AdminSessionRow;
import com.spire.backend.dto.UserDTO;
import com.spire.backend.entity.Enrollment;
import com.spire.backend.entity.MentorAssignment;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.SessionRequest;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final LessonRepository lessonRepository;
    private final ProgressRepository progressRepository;
    private final CertificateRepository certificateRepository;
    private final SessionRequestRepository sessionRequestRepository;
    private final MentorAssignmentRepository mentorAssignmentRepository;
    private final RecordService recordService;

    /**
     * Platform-wide statistics powering the admin Overview tab.
     * Kept as a flat Map for easy frontend consumption.
     */
    public Map<String, Object> getAnalytics() {
        long totalStudents = countUsersWithRole("STUDENT");
        long totalInstructors = countUsersWithRole("INSTRUCTOR");
        long totalTrainers = countUsersWithRole("TRAINER");
        long totalAdmins = countUsersWithRole("ADMIN");

        long totalCoursesOnly = courseRepository.findAll().stream()
                .filter(c -> !"SERVICE".equals(c.getType()))
                .count();
        long totalServices = courseRepository.findAll().stream()
                .filter(c -> "SERVICE".equals(c.getType()))
                .count();

        long totalCompletions = countCompletedEnrollments();

        List<SessionRequest> allSessions = sessionRequestRepository.findAll();
        long sessionPending = allSessions.stream()
                .filter(s -> "PENDING".equals(s.getStatus())).count();
        long sessionAccepted = allSessions.stream()
                .filter(s -> "ACCEPTED".equals(s.getStatus())).count();
        long sessionCompleted = allSessions.stream()
                .filter(s -> "COMPLETED".equals(s.getStatus())).count();

        LocalDateTime now = LocalDateTime.now();
        long active7 = countActiveUsersSince(now.minusDays(7));
        long active30 = countActiveUsersSince(now.minusDays(30));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", userRepository.count());
        stats.put("totalStudents", totalStudents);
        stats.put("totalInstructors", totalInstructors);
        stats.put("totalTrainers", totalTrainers);
        stats.put("totalAdmins", totalAdmins);
        stats.put("totalCourses", totalCoursesOnly);
        stats.put("totalServices", totalServices);
        stats.put("totalEnrollments", enrollmentRepository.count());
        stats.put("totalCompletions", totalCompletions);
        stats.put("totalCertificates", certificateRepository.count());
        stats.put("totalSessionRequests", (long) allSessions.size());
        stats.put("totalSessionsPending", sessionPending);
        stats.put("totalSessionsAccepted", sessionAccepted);
        stats.put("totalSessionsCompleted", sessionCompleted);
        stats.put("activeUsersLast7Days", active7);
        stats.put("activeUsersLast30Days", active30);
        return stats;
    }

    private long countUsersWithRole(String roleName) {
        return userRepository.findAll().stream()
                .filter(u -> u.getRole() != null && roleName.equals(u.getRole().getName()))
                .count();
    }

    private long countCompletedEnrollments() {
        long completed = 0;
        for (Enrollment e : enrollmentRepository.findAll()) {
            Long courseId = e.getCourse().getId();
            int total = lessonRepository.findByCourseIdOrderByOrderIndex(courseId).size();
            long done = progressRepository.countCompletedLessons(e.getUser().getId(), courseId);
            if (total > 0 && done >= total) completed++;
        }
        return completed;
    }

    private long countActiveUsersSince(LocalDateTime cutoff) {
        return progressRepository.findAll().stream()
                .filter(p -> p.getLastAccessed() != null
                        && p.getLastAccessed().isAfter(cutoff))
                .map(p -> p.getUser() != null ? p.getUser().getId() : null)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    public List<UserDTO> getAllUsers() {
        return getAllUsers(null);
    }

    /**
     * Filtered admin list. {@code status} accepts:
     *   - "active"   → only is_active = true rows
     *   - "inactive" → only is_active = false rows
     *   - null / "all" / anything else → every row (default)
     *
     * Powers the Active / Deactivated tabs in the admin Users panel.
     * Sorted by user id desc so newly-deactivated rows surface first
     * on the Deactivated tab.
     */
    public List<UserDTO> getAllUsers(String status) {
        boolean wantActive = "active".equalsIgnoreCase(status);
        boolean wantInactive = "inactive".equalsIgnoreCase(status);
        return userRepository.findAll().stream()
                .filter(u -> {
                    boolean active = !Boolean.FALSE.equals(u.getIsActive());
                    if (wantActive) return active;
                    if (wantInactive) return !active;
                    return true;
                })
                .sorted(Comparator.comparing(User::getId,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(UserDTO::from)
                .collect(Collectors.toList());
    }

    /**
     * Returns active / inactive / total counts for the admin users
     * tab labels. Computed in a single repository round-trip.
     */
    public Map<String, Long> getUserCounts() {
        long active = 0, inactive = 0;
        for (User u : userRepository.findAll()) {
            if (Boolean.FALSE.equals(u.getIsActive())) inactive++;
            else active++;
        }
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("active", active);
        out.put("inactive", inactive);
        out.put("total", active + inactive);
        return out;
    }

    @Transactional(readOnly = true)
    public List<AdminEnrollmentRow> getAllEnrollments() {
        return enrollmentRepository.findAll().stream()
                .map(this::toEnrollmentRow)
                .sorted(Comparator.comparing(
                        AdminEnrollmentRow::getEnrolledAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    private AdminEnrollmentRow toEnrollmentRow(Enrollment e) {
        Long userId = e.getUser().getId();
        Long courseId = e.getCourse().getId();
        int total = lessonRepository.findByCourseIdOrderByOrderIndex(courseId).size();
        long done = progressRepository.countCompletedLessons(userId, courseId);
        boolean isComplete = total > 0 && done >= total;
        int pct = total > 0 ? (int) Math.round(100.0 * done / total) : 0;

        String mentorName = null;
        String assignmentStatus = null;
        if (!"SERVICE".equals(e.getCourse().getType())) {
            MentorAssignment ma = mentorAssignmentRepository.findByEnrollmentId(e.getId()).orElse(null);
            if (ma != null) {
                assignmentStatus = ma.getStatus();
                if (ma.getMentor() != null) mentorName = ma.getMentor().getFullName();
            }
        }

        return AdminEnrollmentRow.builder()
                .enrollmentId(e.getId())
                .userId(userId)
                .studentName(e.getUser().getFullName())
                .studentEmail(e.getUser().getEmail())
                .courseId(courseId)
                .courseTitle(e.getCourse().getTitle())
                .courseType(e.getCourse().getType())
                .enrolledAt(e.getEnrolledAt())
                .progressPercent(pct)
                .completedLessons((int) done)
                .totalLessons(total)
                .completed(isComplete)
                .mentorName(mentorName)
                .mentorAssignmentStatus(assignmentStatus)
                .build();
    }

    @Transactional(readOnly = true)
    public List<AdminSessionRow> getAllSessions() {
        return sessionRequestRepository.findAll().stream()
                .map(this::toSessionRow)
                .sorted(Comparator.comparing(
                        AdminSessionRow::getRequestedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    private AdminSessionRow toSessionRow(SessionRequest s) {
        MentorAssignment ma = s.getMentorAssignment();
        String mentorName = ma != null && ma.getMentor() != null
                ? ma.getMentor().getFullName() : null;
        String courseTitle = ma != null && ma.getEnrollment() != null
                && ma.getEnrollment().getCourse() != null
                ? ma.getEnrollment().getCourse().getTitle() : null;

        return AdminSessionRow.builder()
                .sessionId(s.getId())
                .studentName(s.getRequestedBy() != null ? s.getRequestedBy().getFullName() : null)
                .studentEmail(s.getRequestedBy() != null ? s.getRequestedBy().getEmail() : null)
                .mentorName(mentorName)
                .courseTitle(courseTitle)
                .status(s.getStatus())
                .topic(s.getTopic())
                .requestedAt(s.getRequestedAt())
                .scheduledAt(s.getScheduledAt())
                .completedAt(s.getCompletedAt())
                .meetingUrl(s.getMeetingUrl())
                .build();
    }

    @Transactional
    public UserDTO updateUserRole(Long userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        String normalizedRole = roleName.toUpperCase();

        // SECURITY: Cannot directly assign INSTRUCTOR via this endpoint.
        // Use the Instructor Approval System instead (approve-instructor).
        if ("INSTRUCTOR".equals(normalizedRole)) {
            throw new IllegalArgumentException(
                    "Cannot assign INSTRUCTOR role directly. Use the instructor approval system.");
        }

        Role role = roleRepository.findByName(normalizedRole)
                .orElseThrow(() -> new IllegalArgumentException("Invalid role: " + roleName));

        String oldRole = user.getRole().getName();
        if ("INSTRUCTOR".equals(oldRole) && !"INSTRUCTOR".equals(normalizedRole)) {
            user.setInstructorApproved(false);
        }

        user.setRole(role);
        UserDTO saved = UserDTO.from(userRepository.save(user));

        recordService.record(userId, "ACCOUNT_ROLE_CHANGED", RecordService.Category.ACCOUNT,
                "Role changed by admin",
                "Role changed from " + oldRole + " to " + normalizedRole,
                java.util.Map.of("oldRole", oldRole, "newRole", normalizedRole));

        return saved;
    }

    /**
     * Soft-deletes a user — the row stays in the database (so its
     * activity logs, certificates, and enrollments remain valid for
     * audit) but the account is marked inactive and personal data is
     * scrubbed. The original email is replaced with
     * {@code deleted_<id>@removed.com} so the address can be reused
     * by a fresh signup.
     *
     * Guards:
     *   - admins cannot soft-delete themselves (would lock them out)
     *   - admins cannot soft-delete other admins (admin role changes
     *     go through {@link #updateUserRole}, not deletion)
     */
    @Transactional
    public UserDTO softDeleteUser(Long userId, Long currentAdminId) {
        if (userId.equals(currentAdminId)) {
            throw new IllegalArgumentException("You can't deactivate your own account.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (user.getRole() != null && "ADMIN".equalsIgnoreCase(user.getRole().getName())) {
            throw new IllegalArgumentException("Admin accounts cannot be deactivated this way. Change their role first.");
        }

        // Capture identifiers before scrubbing so the audit record
        // can name the original account, not the placeholder.
        String originalEmail = user.getEmail();
        String originalName = user.getFullName();

        user.setIsActive(false);
        user.setDeactivatedAt(java.time.LocalDateTime.now());
        user.setEmail("deleted_" + user.getId() + "@removed.com");
        user.setFullName("Deleted User");
        user.setPhone(null);
        user.setLocation(null);
        user.setBio(null);
        user.setAvatarUrl(null);
        // Clear in-flight verification / reset tokens too — none of
        // them should be honoured against an anonymized account.
        user.setVerificationToken(null);
        user.setVerificationExpiresAt(null);
        user.setResetToken(null);
        user.setResetTokenExpiresAt(null);

        UserDTO saved = UserDTO.from(userRepository.save(user));

        recordService.record(userId, "ACCOUNT_DEACTIVATED", RecordService.Category.ACCOUNT,
                "Account deactivated by admin",
                "Admin soft-deleted this account; personal data scrubbed",
                java.util.Map.of(
                        "deactivatedBy", currentAdminId,
                        "originalEmail", originalEmail != null ? originalEmail : "",
                        "originalName", originalName != null ? originalName : ""
                ));

        return saved;
    }

    /**
     * Activate or deactivate a user account. An admin can't toggle
     * their own account to avoid lockout.
     */
    @Transactional
    public UserDTO updateUserStatus(Long userId, Long currentAdminId, boolean active) {
        if (userId.equals(currentAdminId)) {
            throw new IllegalArgumentException("You can't change your own account status.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        user.setIsActive(active);
        // Stamp / clear the deactivation timestamp in lockstep with
        // the boolean so the admin UI's "Deactivated on …" column has
        // an accurate value for every inactive row, and reactivated
        // users don't carry a stale timestamp.
        user.setDeactivatedAt(active ? null : java.time.LocalDateTime.now());
        UserDTO saved = UserDTO.from(userRepository.save(user));

        if (active) {
            recordService.record(userId, "ACCOUNT_REACTIVATED", RecordService.Category.ACCOUNT,
                    "Account reactivated by admin",
                    "Admin reactivated this account",
                    java.util.Map.of("reactivatedBy", currentAdminId));
        } else {
            recordService.record(userId, "ACCOUNT_DEACTIVATED", RecordService.Category.ACCOUNT,
                    "Account deactivated by admin",
                    "Admin deactivated this account",
                    java.util.Map.of("deactivatedBy", currentAdminId));
        }

        return saved;
    }
}
