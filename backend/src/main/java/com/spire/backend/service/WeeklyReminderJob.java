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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Phase 5A Email #12 — Monday morning nudge for participants who
 * haven't submitted the current week's report yet.
 *
 * Runs in two places, intentionally idempotent so double-firing is
 * harmless:
 *   - Spring {@code @Scheduled} at 03:30 UTC every Monday (≈ 09:00 IST).
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

    /**
     * 03:30 UTC = 09:00 IST every Monday. Cron field order in Spring:
     * second minute hour day-of-month month day-of-week.
     */
    @Scheduled(cron = "0 30 3 * * MON")
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
     * Returns the number of reminder emails actually dispatched.
     * Safe to call manually or repeatedly — already-submitted users
     * are skipped via the SUBMITTED/REVIEWED status check.
     */
    @Transactional
    public int sendReminders() {
        LocalDate weekStart = startOfWeek(LocalDate.now());
        LocalDate weekEnd = weekStart.plusDays(6);

        List<User> active = userRepository.findByCurrentStatus("WEEKLY_REPORTING_ACTIVE");
        int sent = 0;
        for (User u : active) {
            if (!Boolean.TRUE.equals(u.getIsActive())) continue;
            if (u.getEmail() == null || u.getEmail().isBlank()) continue;
            try {
                Optional<WeeklyReport> existing = weeklyReportRepository
                        .findByUserIdAndWeekStart(u.getId(), weekStart);
                if (existing.isPresent()) {
                    String status = existing.get().getStatus();
                    if ("SUBMITTED".equals(status) || "REVIEWED".equals(status)) {
                        continue;
                    }
                }
                emailTemplateService.sendWeeklyReminderEmail(u, weekStart, weekEnd);
                sent++;
            } catch (Exception e) {
                log.warn("Weekly-reminder skipped for user {}: {}", u.getId(), e.getMessage());
            }
        }
        return sent;
    }

    private static LocalDate startOfWeek(LocalDate date) {
        int dow = date.getDayOfWeek().getValue();
        return date.minusDays(dow - DayOfWeek.MONDAY.getValue());
    }
}
