package com.spire.backend.service;

import com.spire.backend.entity.CoachingFeedback;
import com.spire.backend.entity.CoachingSession;
import com.spire.backend.entity.CoachingTask;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.CoachAssignmentRepository;
import com.spire.backend.repository.CoachingFeedbackRepository;
import com.spire.backend.repository.CoachingSessionRepository;
import com.spire.backend.repository.CoachingTaskRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Coaching detail for a single participant. Throws 401 if the
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

    @Transactional
    public CoachingSession createSession(Long coachUserId, CoachingSession in) {
        requireAssignment(coachUserId, in.getParticipantUserId());
        in.setCoachUserId(coachUserId);
        return sessionRepository.save(in);
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

    @Transactional
    public CoachingTask createTask(Long coachUserId, CoachingTask in) {
        requireAssignment(coachUserId, in.getParticipantUserId());
        in.setCoachUserId(coachUserId);
        if (in.getStatus() == null || in.getStatus().isBlank()) in.setStatus("OPEN");
        return taskRepository.save(in);
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
            throw new UnauthorizedException("Not your task");
        }
        t.setStatus(status);
        return taskRepository.save(t);
    }

    // ── Feedback ──────────────────────────────────────────────────

    @Transactional
    public CoachingFeedback createFeedback(Long coachUserId, CoachingFeedback in) {
        requireAssignment(coachUserId, in.getParticipantUserId());
        in.setCoachUserId(coachUserId);
        if (in.getFeedbackType() == null || in.getFeedbackType().isBlank()) {
            in.setFeedbackType("GENERAL");
        }
        return feedbackRepository.save(in);
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

    private void requireAssignment(Long coachUserId, Long participantId) {
        boolean assigned = coachAssignmentRepository
                .findByCoachUserIdAndStatus(coachUserId, "ACTIVE")
                .stream()
                .anyMatch(a -> participantId.equals(a.getUserId()));
        if (!assigned) {
            throw new UnauthorizedException(
                    "You are not assigned as a coach to this participant.");
        }
    }
}
