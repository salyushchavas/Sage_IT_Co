package com.spire.backend.service;

import com.spire.backend.entity.CoachAssignment;
import com.spire.backend.entity.CoachingFeedback;
import com.spire.backend.entity.CoachingSession;
import com.spire.backend.entity.CoachingTask;
import com.spire.backend.entity.User;
import com.spire.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 4.4: the Resume and Interviews tabs show the participant's own
 * coaching record, with each coach's name and slot, and never the coach's
 * internal session notes. A participant can tick off only their own task.
 */
class ParticipantCoachingTest {

    private CoachingTaskRepository tasks;
    private ParticipantCoachingService service;

    @BeforeEach
    void setUp() {
        CoachingSessionRepository sessions = mock(CoachingSessionRepository.class);
        tasks = mock(CoachingTaskRepository.class);
        CoachingFeedbackRepository feedback = mock(CoachingFeedbackRepository.class);
        CoachAssignmentRepository assignments = mock(CoachAssignmentRepository.class);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(20L)).thenReturn(Optional.of(User.builder().id(20L).fullName("Rita Resume").build()));
        when(users.findById(21L)).thenReturn(Optional.of(User.builder().id(21L).fullName("Ivan Interview").build()));
        when(assignments.findByUserIdAndStatus(10L, "ACTIVE")).thenReturn(List.of(
                CoachAssignment.builder().userId(10L).coachUserId(21L).coachRole("INTERVIEW_COACH").build()));
        when(assignments.findByUserIdAndStatus(10L, "ENDED")).thenReturn(List.of(
                CoachAssignment.builder().userId(10L).coachUserId(20L).coachRole("RESUME_SPECIALIST").build()));
        when(sessions.findByParticipantUserIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(
                CoachingSession.builder().id(1L).participantUserId(10L).coachUserId(21L).sessionDate(LocalDate.of(2026, 9, 20))
                        .topic("Behavioural questions").notes("Seems nervous; internal only").nextSteps("Practise STAR").build()));
        when(tasks.findByParticipantUserIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(
                CoachingTask.builder().id(5L).participantUserId(10L).coachUserId(20L).title("Rewrite summary").status("OPEN").build(),
                CoachingTask.builder().id(6L).participantUserId(10L).coachUserId(21L).title("Old task").status("CANCELLED").build()));
        when(feedback.findByParticipantUserIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(
                CoachingFeedback.builder().id(7L).participantUserId(10L).coachUserId(20L).feedbackType("RESUME")
                        .content("Lead with impact").rating(4).build()));
        when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new ParticipantCoachingService(sessions, tasks, feedback, assignments, users, mock(RecordService.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRecordNamesEachCoachAndHidesSessionNotes() {
        Map<String, Object> mine = service.myCoaching(10L);
        Map<String, Object> session = ((List<Map<String, Object>>) mine.get("sessions")).get(0);
        assertEquals("Ivan Interview", session.get("coachName"));
        assertEquals("INTERVIEW_COACH", session.get("coachRole"));
        assertEquals("Practise STAR", session.get("nextSteps"));
        assertFalse(session.containsKey("notes"), "a coach's internal notes are never sent");
        assertFalse(session.toString().contains("nervous"));

        List<Map<String, Object>> taskRows = (List<Map<String, Object>>) mine.get("tasks");
        assertEquals(1, taskRows.size(), "cancelled tasks are left out");
        assertEquals("RESUME_SPECIALIST", taskRows.get(0).get("coachRole"), "a replaced coach keeps their slot");
        assertEquals("Rita Resume", ((List<Map<String, Object>>) mine.get("feedback")).get(0).get("coachName"));
    }

    @Test
    void aParticipantTicksOffOnlyTheirOwnOpenTask() {
        CoachingTask mine = CoachingTask.builder().id(5L).participantUserId(10L).coachUserId(20L).title("Rewrite summary").status("OPEN").build();
        CoachingTask theirs = CoachingTask.builder().id(8L).participantUserId(99L).coachUserId(20L).title("Someone else's").status("OPEN").build();
        CoachingTask cancelled = CoachingTask.builder().id(6L).participantUserId(10L).coachUserId(21L).title("Old").status("CANCELLED").build();
        when(tasks.findById(5L)).thenReturn(Optional.of(mine));
        when(tasks.findById(8L)).thenReturn(Optional.of(theirs));
        when(tasks.findById(6L)).thenReturn(Optional.of(cancelled));

        assertEquals("DONE", service.markTaskDone(10L, 5L).getStatus());
        assertThrows(AccessDeniedException.class, () -> service.markTaskDone(10L, 8L));
        assertEquals("OPEN", theirs.getStatus());
        assertThrows(IllegalStateException.class, () -> service.markTaskDone(10L, 6L));
    }
}
