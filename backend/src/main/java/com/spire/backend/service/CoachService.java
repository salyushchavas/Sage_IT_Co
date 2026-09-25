package com.spire.backend.service;

import com.spire.backend.entity.CoachingFeedback;
import com.spire.backend.entity.CoachingSession;
import com.spire.backend.entity.CoachingTask;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.CoachAssignmentRepository;
import com.spire.backend.repository.CoachingFeedbackRepository;
import com.spire.backend.repository.CoachingSessionRepository;
import com.spire.backend.repository.CoachingTaskRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 5B — backing service for the coach dashboard. All read /
 * write methods scope to the calling coach: a coach can only see
 * participants whose coach_assignments row points back to them.
 *
 * The role-restriction (no SSN / no check images / no identity docs)
 * is enforced by simply not surfacing those fields from this service.
 * Coaches see profile basics, program selection, sessions / tasks /
 * feedback they created — nothing else.
 */
@Service
@RequiredArgsConstructor
public class CoachService {

    private final CoachAssignmentRepository coachAssignmentRepository;
    private final UserRepository userRepository;
    private final ProgramSelectionRepository programSelectionRepository;
    private final CoachingSessionRepository sessionRepository;
    private final CoachingTaskRepository taskRepository;
    private final CoachingFeedbackRepository feedbackRepository;

    /** Participants currently assigned to this coach across any role. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myParticipants(Long coachUserId) {
        return coachAssignmentRepository
                .findByCoachUserIdAndStatus(coachUserId, "ACTIVE")
                .stream()
                .map(a -> {
                    User p = userRepository.findById(a.getUserId()).orElse(null);
                    if (p == null) return null;
                    ProgramSelection program = programSelectionRepository
                            .findFirstByUserIdOrderBySelectionDateDesc(p.getId())
                            .orElse(null);
                    long sessionCount = sessionRepository
                            .findByCoachUserIdAndParticipantUserIdOrderByCreatedAtDesc(
                                    coachUserId, p.getId()).size();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("userId", p.getId());
                    row.put("participantId", p.getParticipantId());
                    row.put("fullName", p.getFullName());
                    row.put("technology", program != null ? program.getSkillset() : p.getSelectedTechnology());
                    row.put("targetJobTitle", program != null ? program.getTargetJobTitle() : null);
                    row.put("program", program != null ? program.getProgram() : null);
                    row.put("phase", program != null ? program.getPhase() : null);
                    row.put("coachRole", a.getCoachRole());
                    row.put("sessions", sessionCount);
                    row.put("currentStatus", p.getCurrentStatus());
                    return row;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** Coaching detail for a single participant. Throws 403 if the
     *  caller isn't an assigned coach for this participant. */
    @Transactional(readOnly = true)
    public Map<String, Object> participantDetail(Long coachUserId, Long participantId) {
        requireAssignment(coachUserId, participantId);
        User p = userRepository.findById(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", participantId));
        ProgramSelection program = programSelectionRepository
                .findFirstByUserIdOrderBySelectionDateDesc(p.getId())
                .orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", p.getId());
        out.put("participantId", p.getParticipantId());
        out.put("fullName", p.getFullName());
        out.put("email", p.getEmail());
        out.put("technology", program != null ? program.getSkillset() : p.getSelectedTechnology());
        out.put("targetJobTitle", program != null ? program.getTargetJobTitle() : null);
        out.put("program", program != null ? program.getProgram() : null);
        out.put("phase", program != null ? program.getPhase() : null);
        out.put("availability", program != null ? program.getAvailability() : p.getAvailability());
        out.put("currentStatus", p.getCurrentStatus());
        out.put("sessions", sessionRepository
                .findByCoachUserIdAndParticipantUserIdOrderByCreatedAtDesc(coachUserId, participantId));
        out.put("tasks", taskRepository
                .findByCoachUserIdAndParticipantUserIdOrderByCreatedAtDesc(coachUserId, participantId));
        out.put("feedback", feedbackRepository
                .findByCoachUserIdAndParticipantUserIdOrderByCreatedAtDesc(coachUserId, participantId));
        return out;
    }

    // ── Session notes ─────────────────────────────────────────────

    /**
     * Always a new row. Only the note's own fields are taken from the
     * request: an id (or coach id) sent with it would otherwise turn the
     * save into an update of someone else's note.
     */
    @Transactional
    public CoachingSession createSession(Long coachUserId, CoachingSession in) {
        Long participantId = participantOf(in == null ? null : in.getParticipantUserId());
        requireAssignment(coachUserId, participantId);
        Integer minutes = in.getDurationMinutes();
        if (minutes != null && (minutes < 0 || minutes > 24 * 60)) {
            throw new IllegalArgumentException("Enter the session length in minutes (0 to 1440).");
        }
        return sessionRepository.save(CoachingSession.builder()
                .participantUserId(participantId)
                .coachUserId(coachUserId)
                .sessionDate(in.getSessionDate())
                .topic(cut(in.getTopic(), 255))
                .notes(in.getNotes())
                .nextSteps(in.getNextSteps())
                .durationMinutes(minutes)
                .build());
    }

    @Transactional(readOnly = true)
    public List<CoachingSession> listSessions(Long coachUserId, Long participantId) {
        if (participantId != null) {
            requireAssignment(coachUserId, participantId);
            return sessionRepository
                    .findByCoachUserIdAndParticipantUserIdOrderByCreatedAtDesc(coachUserId, participantId);
        }
        return sessionRepository.findByCoachUserIdOrderByCreatedAtDesc(coachUserId);
    }

    // ── Practice tasks ────────────────────────────────────────────

    /** Always a new, open task (see {@link #createSession}). */
    @Transactional
    public CoachingTask createTask(Long coachUserId, CoachingTask in) {
        Long participantId = participantOf(in == null ? null : in.getParticipantUserId());
        requireAssignment(coachUserId, participantId);
        String title = in.getTitle() == null ? "" : in.getTitle().trim();
        if (title.isEmpty()) throw new IllegalArgumentException("Give the task a title.");
        return taskRepository.save(CoachingTask.builder()
                .participantUserId(participantId)
                .coachUserId(coachUserId)
                .title(cut(title, 255))
                .description(in.getDescription())
                .dueDate(in.getDueDate())
                .status("OPEN")
                .build());
    }

    @Transactional(readOnly = true)
    public List<CoachingTask> listTasks(Long coachUserId, Long participantId) {
        if (participantId != null) {
            requireAssignment(coachUserId, participantId);
            return taskRepository
                    .findByCoachUserIdAndParticipantUserIdOrderByCreatedAtDesc(coachUserId, participantId);
        }
        return taskRepository.findByCoachUserIdOrderByCreatedAtDesc(coachUserId);
    }

    @Transactional
    public CoachingTask updateTaskStatus(Long coachUserId, Long taskId, String status) {
        CoachingTask t = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("CoachingTask", "id", taskId));
        if (!coachUserId.equals(t.getCoachUserId())) {
            throw new AccessDeniedException("This task was set by another coach.");
        }
        String next = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!TASK_STATUSES.contains(next)) {
            throw new IllegalArgumentException("A task is OPEN, DONE or CANCELLED.");
        }
        t.setStatus(next);
        return taskRepository.save(t);
    }

    // ── Feedback ──────────────────────────────────────────────────

    /** Always a new row (see {@link #createSession}). */
    @Transactional
    public CoachingFeedback createFeedback(Long coachUserId, CoachingFeedback in) {
        Long participantId = participantOf(in == null ? null : in.getParticipantUserId());
        requireAssignment(coachUserId, participantId);
        String content = in.getContent() == null ? "" : in.getContent().trim();
        if (content.isEmpty()) throw new IllegalArgumentException("Write the feedback first.");
        String type = in.getFeedbackType() == null || in.getFeedbackType().isBlank()
                ? "GENERAL" : in.getFeedbackType().trim().toUpperCase(Locale.ROOT);
        if (!FEEDBACK_TYPES.contains(type)) type = "GENERAL";
        Integer rating = in.getRating();
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("A rating is from 1 to 5.");
        }
        return feedbackRepository.save(CoachingFeedback.builder()
                .participantUserId(participantId)
                .coachUserId(coachUserId)
                .feedbackType(type)
                .content(content)
                .rating(rating)
                .build());
    }

    @Transactional(readOnly = true)
    public List<CoachingFeedback> listFeedback(Long coachUserId, Long participantId) {
        if (participantId != null) {
            requireAssignment(coachUserId, participantId);
            return feedbackRepository
                    .findByCoachUserIdAndParticipantUserIdOrderByCreatedAtDesc(coachUserId, participantId);
        }
        return feedbackRepository.findByCoachUserIdOrderByCreatedAtDesc(coachUserId);
    }

    // ── Auth gate ─────────────────────────────────────────────────

    private static final Set<String> TASK_STATUSES = Set.of("OPEN", "DONE", "CANCELLED");
    private static final Set<String> FEEDBACK_TYPES = Set.of("SESSION", "RESUME", "TECHNICAL", "INTERVIEW", "GENERAL");

    private static Long participantOf(Long participantUserId) {
        if (participantUserId == null) throw new IllegalArgumentException("Pick the participant first.");
        return participantUserId;
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** 403, not 401: the website treats 401 as "sign in again". */
    private void requireAssignment(Long coachUserId, Long participantId) {
        boolean assigned = participantId != null && coachAssignmentRepository
                .findByCoachUserIdAndStatus(coachUserId, "ACTIVE")
                .stream()
                .anyMatch(a -> participantId.equals(a.getUserId()));
        if (!assigned) {
            throw new AccessDeniedException(
                    "You are not assigned as a coach to this participant.");
        }
    }
}
