package com.spire.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.dto.WeeklyReportRequest;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WeeklyReport;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WeeklyReportRepository;
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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeeklyReportService {

    private final WeeklyReportRepository weeklyReportRepository;
    private final UserRepository userRepository;
    private final WorkflowService workflowService;
    private final RecordService recordService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public WeeklyReport submit(Long userId, WeeklyReportRequest req) {
        User user = requireGatedUser(userId);
        LocalDate weekStart = req.getWeekStart() != null ? req.getWeekStart() : startOfWeek(LocalDate.now());
        LocalDate weekEnd = req.getWeekEnd() != null ? req.getWeekEnd() : weekStart.plusDays(6);

        WeeklyReport row = upsertRow(userId, weekStart, weekEnd, req);
        row.setStatus("SUBMITTED");
        row.setSubmittedAt(LocalDateTime.now());
        WeeklyReport saved = weeklyReportRepository.save(row);

        // First submit ever → workflow advance.
        if (!workflowService.isStatusAtLeast(user, WorkflowService.Status.WEEKLY_REPORTING_ACTIVE)) {
            workflowService.transition(user,
                    WorkflowService.Status.WEEKLY_REPORTING_ACTIVE,
                    "first_weekly_report_submitted");
        }

        recordService.logAction(userId, RecordService.Category.ACCOUNT,
                "Weekly report submitted",
                "weekStart=" + weekStart + " weekEnd=" + weekEnd,
                Map.of("reportId", saved.getId(),
                        "weekStart", weekStart.toString(),
                        "weekEnd", weekEnd.toString()));
        log.info("Weekly report submitted user={} id={} week={}-{}",
                userId, saved.getId(), weekStart, weekEnd);
        return saved;
    }

    @Transactional
    public WeeklyReport saveDraft(Long userId, WeeklyReportRequest req) {
        User user = requireGatedUser(userId);
        if (user == null) throw new IllegalStateException("gate check returned null");
        LocalDate weekStart = req.getWeekStart() != null ? req.getWeekStart() : startOfWeek(LocalDate.now());
        LocalDate weekEnd = req.getWeekEnd() != null ? req.getWeekEnd() : weekStart.plusDays(6);
        WeeklyReport row = upsertRow(userId, weekStart, weekEnd, req);
        if (row.getStatus() == null || "PENDING".equals(row.getStatus())) {
            row.setStatus("PENDING");
        }
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
            throw new UnauthorizedException("Not allowed to view this report");
        }
        return row;
    }

    /** Returns the in-progress (or most-recent) report for the
     *  current week. Used by the dashboard's "next action" card. */
    @Transactional(readOnly = true)
    public Optional<WeeklyReport> currentWeekReport(Long userId) {
        LocalDate weekStart = startOfWeek(LocalDate.now());
        return weeklyReportRepository.findByUserIdAndWeekStart(userId, weekStart);
    }

    // ── Internals ────────────────────────────────────────────────

    private WeeklyReport upsertRow(Long userId, LocalDate weekStart, LocalDate weekEnd,
                                   WeeklyReportRequest req) {
        Optional<WeeklyReport> existing = weeklyReportRepository
                .findByUserIdAndWeekStart(userId, weekStart);
        WeeklyReport row = existing.orElseGet(() -> WeeklyReport.builder()
                .userId(userId)
                .weekStart(weekStart)
                .weekEnd(weekEnd)
                .submissionDueDate(weekEnd.plusDays(1))
                .status("PENDING")
                .build());
        // Always refresh the week boundaries — caller may have
        // re-derived them from the current date.
        row.setWeekEnd(weekEnd);
        if (row.getSubmissionDueDate() == null) {
            row.setSubmissionDueDate(weekEnd.plusDays(1));
        }
        row.setReportData(serialiseReport(req));
        return row;
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
            throw new UnauthorizedException(
                    "Weekly reports become available once your dashboard is enabled.");
        }
        return user;
    }

    /** Monday-anchored week boundary, matching most US/UK weekly rhythms. */
    private static LocalDate startOfWeek(LocalDate date) {
        int dow = date.getDayOfWeek().getValue(); // Mon=1..Sun=7
        return date.minusDays(dow - DayOfWeek.MONDAY.getValue());
    }
}
