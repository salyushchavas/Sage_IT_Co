package com.spire.backend.service;

import com.spire.backend.entity.User;
import com.spire.backend.entity.WeeklyReport;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Checklist 4.2: marks missing weekly reports OVERDUE. For every
 * participant who owes reports, each finished week (from their first owed
 * week) whose due date — the Monday after it — has passed without a
 * submitted report is marked OVERDUE (a row is created if there was none),
 * recorded, and shows in the ERM's report list. The participant is emailed
 * about last week only; older weeks are marked silently (so the first run
 * after going live doesn't send a pile of emails). Idempotent: a week
 * already marked isn't marked or emailed again.
 *
 * Cron: daily at 09:15 business time (app.business-zone).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WeeklyOverdueJob {

    private final UserRepository userRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final WeeklyReportService weeklyReportService;
    private final BusinessClock clock;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(cron = "0 15 9 * * *", zone = "${app.business-zone:America/Chicago}")
    public void runScheduled() {
        int flagged = flagOverdueWeeks();
        log.info("Weekly-overdue job: weeks marked overdue = {}", flagged);
    }

    /** What one participant's run changed. */
    private record Outcome(User user, int flagged, boolean lastWeekFlagged) {}

    /**
     * One participant at a time, each in its own short transaction. A
     * problem with one person's rows can't undo everyone else's (which
     * would re-send their emails the next day), and no row stays locked
     * while emails go out: the email about last week is sent after that
     * person's changes are saved.
     */
    public int flagOverdueWeeks() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        LocalDate today = clock.today();
        LocalDate lastWeek = clock.startOfWeek().minusWeeks(1);
        List<Long> ids = tx.execute(s -> userRepository.findAll().stream().map(User::getId).toList());
        int flagged = 0;
        for (Long userId : ids == null ? List.<Long>of() : ids) {
            Outcome outcome;
            try {
                outcome = tx.execute(s -> flagFor(userId, today, lastWeek));
            } catch (Exception e) {
                log.warn("Weekly-overdue: skipped user {}: {}", userId, e.getMessage());
                continue;
            }
            if (outcome == null) continue;
            flagged += outcome.flagged();
            if (outcome.lastWeekFlagged()) {
                try {
                    emailTemplateService.sendWeeklyOverdueEmail(outcome.user(), lastWeek, lastWeek.plusDays(6));
                } catch (Exception e) {
                    log.warn("Overdue email to user {} failed: {}", userId, e.getMessage());
                }
            }
        }
        return flagged;
    }

    private Outcome flagFor(Long userId, LocalDate today, LocalDate lastWeek) {
        User u = userRepository.findById(userId).orElse(null);
        if (u == null || !weeklyReportService.owesReports(u)) return null;
        LocalDate week = weeklyReportService.firstOwedWeek(u);
        if (week == null) return null;
        int flagged = 0;
        boolean lastWeekFlagged = false;
        for (; !week.isAfter(lastWeek); week = week.plusWeeks(1)) {
            LocalDate due = week.plusDays(7);
            if (!today.isAfter(due)) continue;              // due today: not late yet
            final LocalDate weekStart = week;
            WeeklyReport row = weeklyReportRepository.findByUserIdAndWeekStart(u.getId(), weekStart)
                    .orElseGet(() -> WeeklyReport.builder().userId(u.getId()).weekStart(weekStart)
                            .weekEnd(weekStart.plusDays(6)).submissionDueDate(due).status("PENDING").build());
            String status = row.getStatus();
            if ("SUBMITTED".equals(status) || "REVIEWED".equals(status) || row.getOverdueFlaggedAt() != null) continue;
            row.setStatus(WeeklyReportService.OVERDUE);
            row.setOverdueFlaggedAt(LocalDateTime.now());
            row.setSubmissionDueDate(due);
            weeklyReportRepository.save(row);
            recordService.logAction(u.getId(), RecordService.Category.ACCOUNT,
                    "Weekly report overdue", "week " + weekStart + " was due " + due,
                    Map.of("weekStart", weekStart.toString(), "dueDate", due.toString()));
            flagged++;
            if (weekStart.equals(lastWeek)) lastWeekFlagged = true;
        }
        return new Outcome(u, flagged, lastWeekFlagged);
    }
}
