package com.spire.backend.service;

import com.spire.backend.entity.User;
import com.spire.backend.entity.WeeklyReport;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Phase 5A Email #12 — Monday morning nudge for participants who
 * haven't submitted the current week's report yet.
 *
 * Runs in two places, intentionally idempotent so double-firing is
 * harmless:
 *   - Spring {@code @Scheduled} at 9:00 am US Central every Monday.
 *   - Manual trigger via the {@code /api/cron/weekly-reminder} Vercel
 *     route, which calls {@link #sendReminders()} through the internal
 *     controller endpoint (X-Cron-Secret protected).
 *
 * "Already submitted" means there's a WeeklyReport row for the user
 * with {@code weekStart} equal to the current Monday in SUBMITTED or
 * REVIEWED status. PENDING / OVERDUE rows still get a reminder.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WeeklyReminderJob {

    private final UserRepository userRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final EmailTemplateService emailTemplateService;
    private final EmailService emailService;
    private final WeeklyReportService weeklyReportService;
    private final BusinessClock clock;

    /**
     * 09:00 business time (app.business-zone) every Monday — the day last
     * week's report is due (checklist 4.2). Cron field order in Spring:
     * second minute hour day-of-month month day-of-week.
     */
    @Scheduled(cron = "0 0 9 * * MON", zone = "${app.business-zone:America/Chicago}")
    @Transactional
    public void runScheduled() {
        if (!emailService.isConfigured()) {
            log.debug("Skipping weekly-reminder job — mail not configured");
            return;
        }
        int sent = sendReminders();
        log.info("Weekly-reminder job (scheduled): emails sent = {}", sent);
    }

    /**
     * Reminds every participant who owes reports and hasn't submitted
     * LAST week's (it's due today, Monday). It used to check the week that
     * had just started, and only people at exactly WEEKLY_REPORTING_ACTIVE,
     * so it missed everyone who hadn't filed their first report yet.
     * Returns the number of emails that went out.
     */
    @Transactional
    public int sendReminders() {
        LocalDate weekStart = clock.startOfWeek().minusWeeks(1);
        LocalDate weekEnd = weekStart.plusDays(6);
        int sent = 0;
        for (User u : userRepository.findAll()) {
            if (!weeklyReportService.owesReports(u)) continue;
            if (u.getEmail() == null || u.getEmail().isBlank()) continue;
            LocalDate first = weeklyReportService.firstOwedWeek(u);
            if (first == null || weekStart.isBefore(first)) continue;
            try {
                Optional<WeeklyReport> existing = weeklyReportRepository
                        .findByUserIdAndWeekStart(u.getId(), weekStart);
                if (existing.isPresent()) {
                    String status = existing.get().getStatus();
                    if ("SUBMITTED".equals(status) || "REVIEWED".equals(status)) {
                        continue;
                    }
                }
                if (emailTemplateService.sendWeeklyReminderEmail(u, weekStart, weekEnd)) sent++;
            } catch (Exception e) {
                log.warn("Weekly-reminder skipped for user {}: {}", u.getId(), e.getMessage());
            }
        }
        return sent;
    }

}
