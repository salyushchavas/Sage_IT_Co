package com.spire.backend.service;

import com.spire.backend.dto.SessionRequestDTO;
import com.spire.backend.entity.Enrollment;
import com.spire.backend.entity.MentorAssignment;
import com.spire.backend.entity.SessionRequest;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.MentorAssignmentRepository;
import com.spire.backend.repository.SessionRequestRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SessionRequestService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private static final String ASSIGNMENT_PENDING = "PENDING_ASSIGNMENT";

    // For mentor's combined view: PENDING first, then ACCEPTED, then others.
    private static final Map<String, Integer> STATUS_PRIORITY = Map.of(
            STATUS_PENDING, 0,
            STATUS_ACCEPTED, 1,
            STATUS_COMPLETED, 2,
            STATUS_CANCELLED, 3
    );

    private final SessionRequestRepository sessionRequestRepository;
    private final MentorAssignmentRepository mentorAssignmentRepository;
    private final UserRepository userRepository;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;

    @Transactional
    public SessionRequestDTO createRequest(Long userId, Long enrollmentId, String topic) {
        MentorAssignment assignment = mentorAssignmentRepository.findByEnrollmentId(enrollmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "MentorAssignment", "enrollmentId", enrollmentId));

        // The user must own this enrollment.
        if (assignment.getEnrollment() == null
                || assignment.getEnrollment().getUser() == null
                || !assignment.getEnrollment().getUser().getId().equals(userId)) {
            throw new UnauthorizedException("You can only request sessions for your own enrollments");
        }

        // PENDING_ASSIGNMENT means no mentor assigned yet (pool was empty/full at enrollment).
        if (ASSIGNMENT_PENDING.equals(assignment.getStatus()) || assignment.getMentor() == null) {
            throw new IllegalArgumentException("No mentor assigned yet. Please contact support.");
        }

        User student = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        SessionRequest request = SessionRequest.builder()
                .mentorAssignment(assignment)
                .requestedBy(student)
                .status(STATUS_PENDING)
                .topic(topic)
                .build();

        SessionRequest saved = sessionRequestRepository.save(request);

        String mentorName = assignment.getMentor() != null ? assignment.getMentor().getFullName() : "mentor";
        String courseTitle = assignment.getEnrollment() != null && assignment.getEnrollment().getCourse() != null
                ? assignment.getEnrollment().getCourse().getTitle() : "course";
        recordService.record(userId, "SESSION_REQUESTED", RecordService.Category.MENTORSHIP,
                "Session requested",
                "Requested session with " + mentorName + " for '" + courseTitle + "'. Topic: " + topic,
                Map.of(
                        "sessionId", saved.getId(),
                        "mentorName", mentorName,
                        "courseTitle", courseTitle,
                        "topic", topic != null ? topic : ""
                ));

        return toDTO(saved);
    }

    @Transactional
    public SessionRequestDTO acceptRequest(Long mentorId, Long requestId,
                                           String scheduledAt, String meetingUrl) {
        SessionRequest request = sessionRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("SessionRequest", "id", requestId));

        // The acting user must be the assigned mentor on this request.
        Long assignedMentorId = mentorIdOf(request);
        if (assignedMentorId == null || !assignedMentorId.equals(mentorId)) {
            throw new UnauthorizedException("You are not the assigned mentor for this request");
        }

        // Only PENDING requests can be accepted (defensive — re-accepting an
        // already-ACCEPTED/COMPLETED/CANCELLED request would silently overwrite
        // scheduling fields).
        if (!STATUS_PENDING.equals(request.getStatus())) {
            throw new IllegalArgumentException(
                    "Only PENDING requests can be accepted (current status: " + request.getStatus() + ")");
        }

        LocalDateTime parsed;
        try {
            parsed = LocalDateTime.parse(scheduledAt);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "scheduledAt must be ISO datetime format (e.g. 2026-04-30T14:00:00)");
        }

        request.setStatus(STATUS_ACCEPTED);
        request.setScheduledAt(parsed);
        request.setMeetingUrl(meetingUrl);

        SessionRequest saved = sessionRequestRepository.save(request);

        Long studentId = request.getRequestedBy() != null ? request.getRequestedBy().getId() : null;
        if (studentId != null) {
            String mentorName = request.getMentorAssignment() != null
                    && request.getMentorAssignment().getMentor() != null
                    ? request.getMentorAssignment().getMentor().getFullName() : "mentor";
            recordService.record(studentId, "SESSION_SCHEDULED", RecordService.Category.MENTORSHIP,
                    "Session scheduled",
                    "Session scheduled with " + mentorName + " on " + parsed,
                    Map.of(
                            "sessionId", saved.getId(),
                            "mentorName", mentorName,
                            "scheduledAt", parsed.toString(),
                            "meetingUrl", meetingUrl != null ? meetingUrl : ""
                    ));

            // Confirmation email — best-effort.
            try {
                emailTemplateService.sendSessionScheduledEmail(request.getRequestedBy(), saved);
            } catch (Exception ignored) {}
        }

        return toDTO(saved);
    }

    @Transactional
    public SessionRequestDTO completeRequest(Long userId, Long requestId) {
        SessionRequest request = sessionRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("SessionRequest", "id", requestId));

        ensureStudentOrMentor(userId, request,
                "Only the student or the mentor can mark this session complete");

        request.setStatus(STATUS_COMPLETED);
        request.setCompletedAt(LocalDateTime.now());

        SessionRequest saved = sessionRequestRepository.save(request);

        Long studentId = request.getRequestedBy() != null ? request.getRequestedBy().getId() : null;
        if (studentId != null) {
            String mentorName = request.getMentorAssignment() != null
                    && request.getMentorAssignment().getMentor() != null
                    ? request.getMentorAssignment().getMentor().getFullName() : "mentor";
            recordService.record(studentId, "SESSION_ATTENDED", RecordService.Category.MENTORSHIP,
                    "Session attended",
                    "Attended session with " + mentorName,
                    Map.of(
                            "sessionId", saved.getId(),
                            "mentorName", mentorName,
                            "topic", request.getTopic() != null ? request.getTopic() : "",
                            "completedAt", request.getCompletedAt().toString()
                    ));
        }

        return toDTO(saved);
    }

    @Transactional
    public SessionRequestDTO cancelRequest(Long userId, Long requestId) {
        SessionRequest request = sessionRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("SessionRequest", "id", requestId));

        ensureStudentOrMentor(userId, request,
                "Only the student or the mentor can cancel this session");

        request.setStatus(STATUS_CANCELLED);
        SessionRequest saved = sessionRequestRepository.save(request);

        Long studentId = request.getRequestedBy() != null ? request.getRequestedBy().getId() : null;
        if (studentId != null) {
            Long mentorId = mentorIdOf(request);
            String cancelledBy = userId.equals(studentId) ? "student" : userId.equals(mentorId) ? "mentor" : "other";
            recordService.record(studentId, "SESSION_CANCELLED", RecordService.Category.MENTORSHIP,
                    "Session cancelled",
                    "Session #" + saved.getId() + " cancelled by " + cancelledBy,
                    Map.of("sessionId", saved.getId(), "cancelledBy", cancelledBy));
        }

        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<SessionRequestDTO> getRequestsForStudent(Long userId) {
        return sessionRequestRepository.findByRequestedByIdOrderByRequestedAtDesc(userId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SessionRequestDTO> getRequestsForMentor(Long mentorId) {
        return sessionRequestRepository
                .findByMentorAssignment_MentorIdOrderByRequestedAtDesc(mentorId).stream()
                .sorted(Comparator
                        .<SessionRequest>comparingInt(r -> STATUS_PRIORITY.getOrDefault(r.getStatus(), 99))
                        .thenComparing(SessionRequest::getRequestedAt, Comparator.reverseOrder()))
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SessionRequestDTO> getPendingRequestsForMentor(Long mentorId) {
        return sessionRequestRepository
                .findByMentorAssignment_MentorIdAndStatus(mentorId, STATUS_PENDING).stream()
                .sorted(Comparator.comparing(SessionRequest::getRequestedAt, Comparator.reverseOrder()))
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void ensureStudentOrMentor(Long userId, SessionRequest request, String message) {
        Long studentId = request.getRequestedBy() != null ? request.getRequestedBy().getId() : null;
        Long mentorId = mentorIdOf(request);
        if (!userId.equals(studentId) && !userId.equals(mentorId)) {
            throw new UnauthorizedException(message);
        }
    }

    private Long mentorIdOf(SessionRequest request) {
        MentorAssignment a = request.getMentorAssignment();
        if (a == null || a.getMentor() == null) return null;
        return a.getMentor().getId();
    }

    private SessionRequestDTO toDTO(SessionRequest r) {
        MentorAssignment a = r.getMentorAssignment();
        Enrollment e = a != null ? a.getEnrollment() : null;
        User student = r.getRequestedBy();
        User mentor = a != null ? a.getMentor() : null;
        return SessionRequestDTO.builder()
                .id(r.getId())
                .enrollmentId(e != null ? e.getId() : null)
                .courseTitle(e != null && e.getCourse() != null ? e.getCourse().getTitle() : null)
                .studentName(student != null ? student.getFullName() : null)
                .studentEmail(student != null ? student.getEmail() : null)
                .mentorName(mentor != null ? mentor.getFullName() : null)
                .mentorEmail(mentor != null ? mentor.getEmail() : null)
                .status(r.getStatus())
                .topic(r.getTopic())
                .requestedAt(r.getRequestedAt())
                .scheduledAt(r.getScheduledAt())
                .meetingUrl(r.getMeetingUrl())
                .notes(r.getNotes())
                .completedAt(r.getCompletedAt())
                .build();
    }
}
