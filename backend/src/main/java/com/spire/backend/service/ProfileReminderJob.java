package com.spire.backend.service;

import com.spire.backend.dto.ProfileCompletionDto;
import com.spire.backend.entity.User;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1C — daily nudge for participants under 100% profile
 * completion. Modelled on {@link DocumentReminderJob}: idempotent,
 * per-user throttled, max 3 reminders per user lifetime so a stuck
 * signup never gets pinged forever.
 *
 * Cron: daily at 04:00 UTC ≈ 09:30 IST. Also reachable via the Vercel
 * cron route at {@code /api/cron/profile-reminder} which proxies in
 * with the shared cron secret.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProfileReminderJob {

    private static final int MIN_ACCOUNT_AGE_HOURS = 24;
    private static final int COOLDOWN_DAYS = 2;
    private static final int MAX_REMINDERS = 3;

    private final UserRepository userRepository;
    private final ProfileCompletionService profileCompletionService;
    private final EmailTemplateService emailTemplateService;
    private final EmailService emailService;

    /** 04:00 UTC daily ≈ 09:30 IST. */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void runScheduled() {
        if (!emailService.isConfigured()) {
            log.debug("Skipping profile-reminder job — mail not configured");
            return;
        }
        int sent = sendReminders();
        log.info("Profile-reminder job (scheduled): emails sent = {}", sent);
    }

    @Transactional
    public int sendReminders() {
        LocalDateTime minAge = LocalDateTime.now().minusHours(MIN_ACCOUNT_AGE_HOURS);
        LocalDateTime cooldown = LocalDateTime.now().minusDays(COOLDOWN_DAYS);
        int sent = 0;

        // Pull every still-incomplete user. The list is small relative
        // to the user table and the per-user check is fast, so a full
        // scan is fine for now.
        List<User> users = userRepository.findAll();
        for (User u : users) {
            if (!Boolean.TRUE.equals(u.getIsActive())) continue;
            if (Boolean.TRUE.equals(u.getProfileComplete())) continue;
            if (u.getEmail() == null || u.getEmail().isBlank()) continue;
            // Brand-new accounts get a grace window before the first nudge.
            if (u.getCreatedAt() != null
                    && u.getCreatedAt().isAfter(minAge)) continue;
            int sentSoFar = u.getProfileReminderCount() == null
                    ? 0 : u.getProfileReminderCount();
            if (sentSoFar >= MAX_REMINDERS) continue;
            if (u.getLastProfileReminderAt() != null
                    && u.getLastProfileReminderAt().isAfter(cooldown)) continue;

            ProfileCompletionDto status = profileCompletionService.getStatus(u);
            List<String> remaining = new ArrayList<>();
            for (ProfileCompletionDto.StepInfo step : status.getSteps()) {
                if (!step.isCompleted()) {
                    remaining.add(step.getTitle() + " (" + step.getEstimatedTime() + ")");
                }
            }
            if (remaining.isEmpty()) continue;

            try {
                emailTemplateService.sendProfileReminderEmail(u,
                        status.getCompletionPercentage(), remaining);
                u.setProfileReminderCount(sentSoFar + 1);
                u.setLastProfileReminderAt(LocalDateTime.now());
                userRepository.save(u);
                sent++;
            } catch (Exception e) {
                log.warn("Profile-reminder skipped for user {}: {}",
                        u.getId(), e.getMessage());
            }
        }
        return sent;
    }
}
