package com.spire.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.dto.WeeklyReportRequest;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WeeklyReport;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WeeklyReportRepository;
import com.spire.backend.repository.WorkflowStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 5A Step 16 — handles weekly submission reports.
 *
 *   POST /reports/weekly        → final submit (status SUBMITTED)
 *   POST /reports/weekly/draft  → upsert with status PENDING
 *   GET  /reports/weekly        → list
 *   GET  /reports/weekly/{id}   → fetch one
 *
 * One row per (user, weekStart). Draft + submit go to the same row
 * — submit just flips the status and stamps {@code submittedAt}.
 *
 * The first successful submit transitions the user's workflow to
 * {@code WEEKLY_REPORTING_ACTIVE} so the dashboard's roadmap can
 * reflect that the participant is "actively reporting".
 *
 * Rules (checklists 4.2 and 4.3):
 *   - Weeks run Monday–Sunday in business time (BusinessClock); a
 *     week's report is due the Monday after it ends.
 *   - A report is owed for every full week from when the dashboard
 *     opened until employment is accepted; the week the dashboard
 *     opened (if part-week) can still be filed. Weeks in the future
 *     can't. Last week's report can be filed this week (and later,
 *     as a late report).
 *   - "Save draft" never overwrites a submitted or reviewed report; a
 *     submitted report can be updated until the ERM reviews it.
 *   - Ticking "I need help" emails the participant's ERM once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeeklyReportService {

    public static final String PENDING = "PENDING";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String REVIEWED = "REVIEWED";
    public static final String OVERDUE = "OVERDUE";

    private final WeeklyReportRepository weeklyReportRepository;
    private final UserRepository userRepository;
    private final WorkflowService workflowService;
    private final RecordService recordService;
    private final BusinessClock clock;
    private final WorkflowStateRepository workflowStateRepository;
    private final ErmAssignmentService ermAssignmentService;
    private final EmailTemplateService emailTemplateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public WeeklyReport submit(Long userId, WeeklyReportRequest req) {
        User user = requireGatedUser(userId);
        LocalDate weekStart = weekFor(user, req);
        validateForSubmit(req);
        Optional<WeeklyReport> existing = weeklyReportRepository.findByUserIdAndWeekStart(userId, weekStart);
        if (existing.isPresent() && REVIEWED.equals(existing.get().getStatus())) {
            throw new IllegalStateException(
                    "Your ERM has already reviewed this week's report, so it can't be changed.");
        }

        WeeklyReport row = upsertRow(userId, weekStart, req);
        boolean firstSubmission = row.getSubmittedAt() == null;
        row.setStatus(SUBMITTED);
        if (firstSubmission) row.setSubmittedAt(LocalDateTime.now());   // an update keeps the first time
        WeeklyReport saved = weeklyReportRepository.save(row);

        // First submit ever → workflow advance.
        if (!workflowService.isStatusAtLeast(user, WorkflowService.Status.WEEKLY_REPORTING_ACTIVE)) {
            workflowService.transition(user,
                    WorkflowService.Status.WEEKLY_REPORTING_ACTIVE,
                    "first_weekly_report_submitted");
        }

        recordService.logAction(userId, RecordService.Category.ACCOUNT,
                firstSubmission ? "Weekly report submitted" : "Weekly report updated",
                "weekStart=" + weekStart + " weekEnd=" + saved.getWeekEnd(),
                Map.of("reportId", saved.getId(),
                        "weekStart", weekStart.toString(),
                        "weekEnd", saved.getWeekEnd().toString()));
        escalateIfAsked(user, saved, req);
        log.info("Weekly report submitted user={} id={} week={}", userId, saved.getId(), weekStart);
        return saved;
    }

    @Transactional
    public WeeklyReport saveDraft(Long userId, WeeklyReportRequest req) {
        User user = requireGatedUser(userId);
        LocalDate weekStart = weekFor(user, req);
        Optional<WeeklyReport> existing = weeklyReportRepository.findByUserIdAndWeekStart(userId, weekStart);
        if (existing.isPresent() && (SUBMITTED.equals(existing.get().getStatus())
                || REVIEWED.equals(existing.get().getStatus()))) {
            throw new IllegalStateException(
                    "This week's report is already submitted, so a draft can't replace it. Submit again to update it.");
        }
        WeeklyReport row = upsertRow(userId, weekStart, req);
        // An overdue week stays OVERDUE until it's actually submitted.
        if (!OVERDUE.equals(row.getStatus())) row.setStatus(PENDING);
        return weeklyReportRepository.save(row);
    }

    @Transactional(readOnly = true)
    public List<WeeklyReport> listForUser(Long userId) {
        return weeklyReportRepository.findByUserIdOrderByWeekStartDesc(userId);
    }

    @Transactional(readOnly = true)
    public WeeklyReport getReport(Long userId, Long reportId) {
        WeeklyReport row = weeklyReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("WeeklyReport", "id", reportId));
        if (!row.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("Not allowed to view this report");
        }
        return row;
    }

    /** Returns the in-progress (or most-recent) report for the
     *  current week. Used by the dashboard's "next action" card. */
    @Transactional(readOnly = true)
    public Optional<WeeklyReport> currentWeekReport(Long userId) {
        return weeklyReportRepository.findByUserIdAndWeekStart(userId, clock.startOfWeek());
    }

    // ── Which weeks are owed (checklist 4.2) ─────────────────────

    /** Whether the participant owes weekly reports now: dashboard open, employment not accepted yet. */
    public boolean owesReports(User user) {
        if (user.getRole() == null || !java.util.Set.of("PARTICIPANT", "STUDENT").contains(user.getRole().getName())) {
            return false;
        }
        return Boolean.TRUE.equals(user.getIsActive())
                && workflowService.isStatusAtLeast(user, WorkflowService.Status.DASHBOARD_ENABLED)
                && !workflowService.isStatusAtLeast(user, WorkflowService.Status.EMPLOYMENT_ACCEPTED);
    }

    /** The business-time date the participant's dashboard opened, or null. */
    public LocalDate dashboardOpenedOn(User user) {
        return workflowStateRepository.findByUserIdOrderByCreatedAtAsc(user.getId()).stream()
                .filter(w -> "DASHBOARD_ENABLED".equals(w.getToStatus())
                        && !"dashboard_enabled_quick_signup".equals(w.getTriggerEvent()))
                .map(w -> clock.dateOf(w.getCreatedAt()))
                .findFirst()
                .orElse(null);
    }

    /** The first week a report is owed: the first full week after the dashboard opened. */
    public LocalDate firstOwedWeek(User user) {
        LocalDate opened = dashboardOpenedOn(user);
        if (opened == null) return null;
        LocalDate monday = BusinessClock.startOfWeek(opened);
        return monday.equals(opened) ? monday : monday.plusWeeks(1);
    }

    /**
     * The week a request is for: its Monday (this week when none is
     * given), checked — a Monday, not in the future, not before the week
     * the dashboard opened. The week end is always computed here.
     */
    private LocalDate weekFor(User user, WeeklyReportRequest req) {
        LocalDate thisWeek = clock.startOfWeek();
        LocalDate weekStart = req.getWeekStart() != null ? req.getWeekStart() : thisWeek;
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("A week starts on a Monday.");
        }
        if (weekStart.isAfter(thisWeek)) {
            throw new IllegalArgumentException("You can't file a report for a future week.");
        }
        LocalDate opened = dashboardOpenedOn(user);
        if (opened != null && weekStart.isBefore(BusinessClock.startOfWeek(opened))) {
            throw new IllegalArgumentException("That week is before your weekly reporting started.");
        }
        return weekStart;
    }

    // ── Internals ────────────────────────────────────────────────

    private WeeklyReport upsertRow(Long userId, LocalDate weekStart, WeeklyReportRequest req) {
        LocalDate weekEnd = weekStart.plusDays(6);
        WeeklyReport row = weeklyReportRepository.findByUserIdAndWeekStart(userId, weekStart)
                .orElseGet(() -> WeeklyReport.builder()
                        .userId(userId)
                        .weekStart(weekStart)
                        .status(PENDING)
                        .build());
        row.setWeekEnd(weekEnd);
        row.setSubmissionDueDate(weekEnd.plusDays(1));
        row.setReportData(serialiseReport(req));
        return row;
    }

    /** A submitted report must say something: a job submission, or an activity. */
    static void validateForSubmit(WeeklyReportRequest req) {
        boolean jobs = req.getJobSubmissions() != null && req.getJobSubmissions().stream()
                .anyMatch(j -> j.getCompany() != null && !j.getCompany().isBlank());
        boolean activity = java.util.stream.Stream.of(req.getResumeActivities(), req.getInterviewTraining(),
                        req.getCommunications())
                .filter(java.util.Objects::nonNull)
                .flatMap(m -> m.entrySet().stream())
                .anyMatch(e -> !"escalation".equals(e.getKey()) && e.getValue() != null
                        && !e.getValue().isBlank() && !"false".equals(e.getValue()));
        if (!jobs && !activity) {
            throw new IllegalArgumentException(
                    "Add at least one job submission or activity before submitting.");
        }
    }

    /** "I need help": the participant's ERM is emailed, once per report. */
    private void escalateIfAsked(User user, WeeklyReport row, WeeklyReportRequest req) {
        Map<String, String> comms = req.getCommunications();
        if (comms == null || !"true".equals(comms.get("escalation")) || row.getEscalatedAt() != null) return;
        row.setEscalatedAt(LocalDateTime.now());
        weeklyReportRepository.save(row);
        String detail = comms.getOrDefault("escalationDetail", "");
        recordService.logAction(user.getId(), RecordService.Category.ACCOUNT,
                "Participant asked for help in their weekly report",
                detail, Map.of("reportId", row.getId(), "weekStart", row.getWeekStart().toString()));
        ermAssignmentService.getAssignedErm(user.getId()).ifPresent(erm ->
                emailTemplateService.sendWeeklyEscalationEmail(erm, user, row.getWeekStart(), detail));
    }

    private String serialiseReport(WeeklyReportRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobSubmissions", req.getJobSubmissions() == null
                ? List.of() : req.getJobSubmissions());
        body.put("resumeActivities", req.getResumeActivities() == null
                ? Map.of() : req.getResumeActivities());
        body.put("interviewTraining", req.getInterviewTraining() == null
                ? Map.of() : req.getInterviewTraining());
        body.put("communications", req.getCommunications() == null
                ? Map.of() : req.getCommunications());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.warn("Couldn't serialise weekly report payload: {}", e.getMessage());
            return "{}";
        }
    }

    private User requireGatedUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.DASHBOARD_ENABLED)) {
            // 409, not 401: the participant is signed in, it's just not
            // time yet (a 401 made the website think the session expired).
            throw new IllegalStateException(
                    "Weekly reports open once your team is set up and your dashboard is ready.");
        }
        return user;
    }
}
