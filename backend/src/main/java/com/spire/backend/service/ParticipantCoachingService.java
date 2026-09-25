package com.spire.backend.service;

import com.spire.backend.entity.CoachAssignment;
import com.spire.backend.entity.CoachingTask;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checklist 4.4: the participant's own coaching record — sessions,
 * practice tasks and feedback their coaches logged — for the Resume and
 * Interviews tabs. Each entry names the coach and their slot (Resume
 * Specialist, Interview Coach, …). Session notes are left out: coaches may
 * write internal notes there (roadmap §13, "internal notes not shared").
 */
@Service
@RequiredArgsConstructor
public class ParticipantCoachingService {

    private final CoachingSessionRepository sessionRepository;
    private final CoachingTaskRepository taskRepository;
    private final CoachingFeedbackRepository feedbackRepository;
    private final CoachAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final RecordService recordService;

    @Transactional(readOnly = true)
    public Map<String, Object> myCoaching(Long userId) {
        // The coach's slot: their current assignment first, then a past one
        // (so work from a coach who was replaced still lands in the right tab).
        Map<Long, String> slotOf = new HashMap<>();
        for (String status : List.of("ACTIVE", "ENDED")) {
            for (CoachAssignment a : assignmentRepository.findByUserIdAndStatus(userId, status)) {
                slotOf.putIfAbsent(a.getCoachUserId(), a.getCoachRole());
            }
        }
        Map<Long, String> names = new HashMap<>();
        java.util.function.Function<Long, String> nameOf = id -> id == null ? null
                : names.computeIfAbsent(id, k -> userRepository.findById(k).map(User::getFullName).orElse(null));

        List<Map<String, Object>> sessions = sessionRepository.findByParticipantUserIdOrderByCreatedAtDesc(userId).stream()
                .map(s -> entry(s.getId(), s.getCoachUserId(), nameOf, slotOf,
                        "date", s.getSessionDate(), "topic", s.getTopic(), "nextSteps", s.getNextSteps(),
                        "durationMinutes", s.getDurationMinutes()))
                .toList();
        List<Map<String, Object>> tasks = taskRepository.findByParticipantUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(t -> !"CANCELLED".equals(t.getStatus()))
                .map(t -> entry(t.getId(), t.getCoachUserId(), nameOf, slotOf,
                        "title", t.getTitle(), "description", t.getDescription(), "dueDate", t.getDueDate(),
                        "status", t.getStatus()))
                .toList();
        List<Map<String, Object>> feedback = feedbackRepository.findByParticipantUserIdOrderByCreatedAtDesc(userId).stream()
                .map(f -> entry(f.getId(), f.getCoachUserId(), nameOf, slotOf,
                        "type", f.getFeedbackType(), "content", f.getContent(), "rating", f.getRating(),
                        "createdAt", f.getCreatedAt()))
                .toList();
        return Map.of("sessions", sessions, "tasks", tasks, "feedback", feedback);
    }

    /** The participant marks one of their own practice tasks done. */
    @Transactional
    public CoachingTask markTaskDone(Long userId, Long taskId) {
        CoachingTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("CoachingTask", "id", taskId));
        if (!userId.equals(task.getParticipantUserId())) {
            throw new AccessDeniedException("That task isn't yours.");
        }
        if ("DONE".equals(task.getStatus())) return task;
        if (!"OPEN".equals(task.getStatus())) {
            throw new IllegalStateException("That task was cancelled by your coach.");
        }
        task.setStatus("DONE");
        CoachingTask saved = taskRepository.save(task);
        recordService.logAction(userId, RecordService.Category.MENTORSHIP,
                "Coaching task done: " + task.getTitle(), null, Map.of("taskId", taskId));
        return saved;
    }

    private static Map<String, Object> entry(Long id, Long coachId, java.util.function.Function<Long, String> nameOf,
                                             Map<Long, String> slotOf, Object... fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("coachName", nameOf.apply(coachId));
        m.put("coachRole", coachId == null ? null : slotOf.get(coachId));
        for (int i = 0; i + 1 < fields.length; i += 2) m.put((String) fields[i], fields[i + 1]);
        return m;
    }
}
