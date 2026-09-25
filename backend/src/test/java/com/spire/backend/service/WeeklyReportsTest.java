package com.spire.backend.service;

import org.springframework.transaction.PlatformTransactionManager;
import com.spire.backend.dto.WeeklyReportRequest;
import com.spire.backend.entity.ErmAssignment;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WeeklyReport;
import com.spire.backend.entity.WorkflowState;
import com.spire.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklists 4.1–4.3 (roadmap step 16): weekly reports in business time;
 * a draft never overwrites a submitted report; last week can be filed;
 * missing weeks are marked overdue; "I need help" reaches the ERM; the
 * ERM sees names and full reports and only reviews submitted ones.
 */
class WeeklyReportsTest {

    // Tuesday 22 Sep 2026, 10:00 in Texas.
    private static final Clock TUESDAY = Clock.fixed(
            ZonedDateTime.of(2026, 9, 22, 10, 0, 0, 0, ZoneId.of("America/Chicago")).toInstant(),
            ZoneId.of("America/Chicago"));
    private static final LocalDate THIS_WEEK = LocalDate.of(2026, 9, 21);
    private static final LocalDate LAST_WEEK = LocalDate.of(2026, 9, 14);

    private final List<WeeklyReport> rows = new ArrayList<>();
    private UserRepository users;
    private EmailTemplateService emails;
    private ErmAssignmentService erms;
    private WeeklyReportRepository repo;
    private WorkflowStateRepository history;
    private User pat;
    private WeeklyReportService service;
    private BusinessClock clock;

    @BeforeEach
    void setUp() {
        clock = new BusinessClock(TUESDAY);
        users = mock(UserRepository.class);
        pat = User.builder().id(10L).fullName("Pat Doe").participantId("SAGE-2026-00007").email("pat@x.com")
                .role(Role.builder().name("PARTICIPANT").build()).isActive(true)
                .currentStatus("WEEKLY_REPORTING_ACTIVE").build();
        when(users.findById(10L)).thenReturn(Optional.of(pat));
        when(users.findAll()).thenReturn(List.of(pat));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        repo = mock(WeeklyReportRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            WeeklyReport r = inv.getArgument(0);
            if (r.getId() == null) { r.setId((long) rows.size() + 1); rows.add(r); }
            return r;
        });
        when(repo.findByUserIdAndWeekStart(anyLong(), any())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getUserId().equals(inv.getArgument(0)) && r.getWeekStart().equals(inv.getArgument(1))).findFirst());
        when(repo.findByUserIdOrderByWeekStartDesc(anyLong())).thenAnswer(inv -> List.copyOf(rows));
        when(repo.findById(anyLong())).thenAnswer(inv -> rows.stream().filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
        history = mock(WorkflowStateRepository.class);
        dashboardOpened(LocalDateTime.of(2026, 9, 1, 15, 0));   // a Tuesday → first owed week is 7 Sep
        emails = mock(EmailTemplateService.class);
        erms = mock(ErmAssignmentService.class);
        WorkflowService workflow = new WorkflowService(users, mock(WorkflowStateRepository.class), mock(RecordService.class));
        service = new WeeklyReportService(repo, users, workflow, mock(RecordService.class), clock, history, erms, emails);
    }

    private void dashboardOpened(LocalDateTime utc) {
        when(history.findByUserIdOrderByCreatedAtAsc(10L)).thenReturn(List.of(
                WorkflowState.builder().userId(10L).toStatus("DASHBOARD_ENABLED").triggerEvent("dashboard_enabled").createdAt(utc).build()));
    }

    private static WeeklyReportRequest report(LocalDate week) {
        return WeeklyReportRequest.builder().weekStart(week)
                .jobSubmissions(List.of(WeeklyReportRequest.JobSubmission.builder().company("Acme").jobTitle("Dev").build()))
                .communications(Map.of("escalation", "false"))
                .build();
    }

    // ── 4.3 editing rules ─────────────────────────────────────────

    @Test
    void aDraftCantOverwriteASubmittedReportAndAReviewedOneIsFinal() {
        service.submit(10L, report(THIS_WEEK));
        LocalDateTime first = rows.get(0).getSubmittedAt();
        assertThrows(IllegalStateException.class, () -> service.saveDraft(10L, report(THIS_WEEK)));
        service.submit(10L, report(THIS_WEEK));   // an update while it waits for the ERM
        assertEquals(first, rows.get(0).getSubmittedAt(), "the first submission time is kept");
        rows.get(0).setStatus("REVIEWED");
        assertThrows(IllegalStateException.class, () -> service.submit(10L, report(THIS_WEEK)));
    }

    @Test
    void lastWeekCanBeFiledButNotAFutureOrMadeUpWeek() {
        WeeklyReport r = service.submit(10L, report(LAST_WEEK));
        assertEquals(LAST_WEEK.plusDays(6), r.getWeekEnd(), "the week end is worked out by the server");
        assertEquals(THIS_WEEK, r.getSubmissionDueDate(), "due the Monday after");
        assertThrows(IllegalArgumentException.class, () -> service.submit(10L, report(THIS_WEEK.plusWeeks(1))));
        assertThrows(IllegalArgumentException.class, () -> service.submit(10L, report(LocalDate.of(2026, 9, 16))), "not a Monday");
        assertThrows(IllegalArgumentException.class, () -> service.submit(10L, report(LocalDate.of(2026, 8, 24))), "before reporting started");
    }

    @Test
    void anEmptyReportCantBeSubmitted() {
        WeeklyReportRequest empty = WeeklyReportRequest.builder().weekStart(THIS_WEEK)
                .communications(Map.of("escalation", "false")).build();
        assertThrows(IllegalArgumentException.class, () -> service.submit(10L, empty));
    }

    @Test
    void anOverdueWeekStaysOverdueOnADraftAndIsLateOnceSubmitted() {
        rows.add(WeeklyReport.builder().id(1L).userId(10L).weekStart(LAST_WEEK).weekEnd(LAST_WEEK.plusDays(6))
                .status("OVERDUE").overdueFlaggedAt(LocalDateTime.now()).build());
        service.saveDraft(10L, report(LAST_WEEK));
        assertEquals("OVERDUE", rows.get(0).getStatus());
        service.submit(10L, report(LAST_WEEK));
        assertEquals("SUBMITTED", rows.get(0).getStatus());
        assertNotNull(rows.get(0).getOverdueFlaggedAt(), "still shows as late");
    }

    // ── 4.2 overdue, reminders, help ──────────────────────────────

    @Test
    void missingWeeksAreMarkedOverdueOnceAndOnlyLastWeekIsEmailed() {
        WeeklyOverdueJob job = new WeeklyOverdueJob(users, repo, service, clock, mock(RecordService.class), emails, mock(PlatformTransactionManager.class));
        service.submit(10L, report(LocalDate.of(2026, 9, 7)));          // week 1 filed
        assertEquals(1, job.flagOverdueWeeks(), "last week (14 Sep) was due Monday 21st; it's Tuesday");
        assertEquals("OVERDUE", rows.stream().filter(r -> r.getWeekStart().equals(LAST_WEEK)).findFirst().orElseThrow().getStatus());
        verify(emails).sendWeeklyOverdueEmail(pat, LAST_WEEK, LAST_WEEK.plusDays(6));
        assertEquals(0, job.flagOverdueWeeks(), "nothing new the second time");
        verify(emails, times(1)).sendWeeklyOverdueEmail(any(), any(), any());
    }

    @Test
    void aWeekDueTodayIsNotOverdueYet() {
        BusinessClock monday = new BusinessClock(Clock.fixed(
                ZonedDateTime.of(2026, 9, 21, 10, 0, 0, 0, ZoneId.of("America/Chicago")).toInstant(), ZoneId.of("America/Chicago")));
        WorkflowService workflow = new WorkflowService(users, mock(WorkflowStateRepository.class), mock(RecordService.class));
        WeeklyReportService s = new WeeklyReportService(repo, users, workflow, mock(RecordService.class), monday, history, erms, emails);
        s.submit(10L, report(LocalDate.of(2026, 9, 7)));
        WeeklyOverdueJob job = new WeeklyOverdueJob(users, repo, s, monday, mock(RecordService.class), emails, mock(PlatformTransactionManager.class));
        assertEquals(0, job.flagOverdueWeeks(), "14 Sep's report is due today (Monday 21st)");
    }

    @Test
    void theMondayReminderAsksAboutLastWeek() {
        EmailService mail = mock(EmailService.class);
        WeeklyReminderJob reminder = new WeeklyReminderJob(users, repo, emails, mail, service, clock);
        when(emails.sendWeeklyReminderEmail(any(), any(), any())).thenReturn(true);
        assertEquals(1, reminder.sendReminders());
        verify(emails).sendWeeklyReminderEmail(pat, LAST_WEEK, LAST_WEEK.plusDays(6));
        service.submit(10L, report(LAST_WEEK));
        assertEquals(0, reminder.sendReminders(), "not once it's in");
    }

    @Test
    void reportsStopOnceEmploymentIsAccepted() {
        assertTrue(service.owesReports(pat));
        pat.setCurrentStatus("EMPLOYMENT_ACCEPTED");
        assertFalse(service.owesReports(pat));
    }

    @Test
    void askingForHelpEmailsTheErmOnce() {
        User erm = User.builder().id(50L).fullName("Erin Rao").email("erin@x.com").build();
        when(erms.getAssignedErm(10L)).thenReturn(Optional.of(erm));
        WeeklyReportRequest help = report(THIS_WEEK);
        help.setCommunications(Map.of("escalation", "true", "escalationDetail", "I'm stuck on system design"));
        service.submit(10L, help);
        service.submit(10L, help);
        verify(emails, times(1)).sendWeeklyEscalationEmail(erm, pat, THIS_WEEK, "I'm stuck on system design");
        assertNotNull(rows.get(0).getEscalatedAt());
    }

    // ── 4.1 the ERM's view ────────────────────────────────────────

    @Test
    void theErmSeesNamesAndFullReportsAndReviewsOnlySubmittedOnes() {
        ErmAssignmentRepository assignments = mock(ErmAssignmentRepository.class);
        ErmAssignment row = ErmAssignment.builder().userId(10L).ermUserId(50L).build();
        when(assignments.findByErmUserId(50L)).thenReturn(List.of(row));
        when(assignments.findFirstByUserIdOrderByAssignedDateDesc(10L)).thenReturn(Optional.of(row));
        ErmService ermService = new ErmService(assignments, mock(CoachAssignmentRepository.class), users,
                mock(ProgramSelectionRepository.class), mock(ParticipantDocumentRepository.class),
                mock(AgreementAcceptanceRepository.class), repo, mock(UserRecordRepository.class), mock(RecordService.class));
        service.saveDraft(10L, report(THIS_WEEK));                         // a draft: not the ERM's business
        service.submit(10L, report(LAST_WEEK));
        List<ErmService.ReportRow> list = ermService.reportsForMyParticipants(50L);
        assertEquals(1, list.size());
        assertEquals("Pat Doe", list.get(0).participantName());
        assertTrue(list.get(0).reportData().contains("Acme"));
        Long draftId = rows.stream().filter(r -> "PENDING".equals(r.getStatus())).findFirst().orElseThrow().getId();
        assertThrows(IllegalStateException.class, () -> ermService.reviewReport(50L, draftId, "ok"));
        ermService.reviewReport(50L, list.get(0).id(), "Good week");
        assertEquals("REVIEWED", rows.stream().filter(r -> r.getWeekStart().equals(LAST_WEEK)).findFirst().orElseThrow().getStatus());
    }
}
