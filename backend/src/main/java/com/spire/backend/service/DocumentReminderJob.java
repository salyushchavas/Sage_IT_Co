package com.spire.backend.service;

import com.spire.backend.entity.User;
import com.spire.backend.repository.ParticipantDocumentRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Phase 1B Email #3 — daily nudge for participants stuck at the
 * document-upload step.
 *
 * Targets users in status {@code ID_EMAIL_SENT} or
 * {@code ACKNOWLEDGMENT_ACCEPTED} who have been at that step for
 * more than 24 hours and have no documents on file. Idempotent —
 * a per-user throttle (read off the existing {@code last_nudge_sent_at}
 * column reused from the LMS inactive-nudge job) prevents
 * back-to-back reminders. Three-day cooldown matches the cadence
 * the PRD calls out for re-prompting incomplete profiles.
 *
 * Cron: daily at 03:30 UTC (≈ 09:00 IST). Also reachable via the
 * Vercel cron route at {@code /api/cron/document-reminder}, which
 * proxies into the internal endpoint with the shared cron secret.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentReminderJob {

    private static final Set<String> STUCK_STATUSES = Set.of(
            "ID_EMAIL_SENT", "ACKNOWLEDGMENT_ACCEPTED");
    /** Account must be at least this old before we nudge — keeps a
     *  brand-new participant from getting reminded before they've
     *  had a chance to act. */
    private static final int MIN_ACCOUNT_AGE_HOURS = 24;
    private static final int COOLDOWN_DAYS = 3;

    private final UserRepository userRepository;
    private final ParticipantDocumentRepository documentRepository;
    private final EmailTemplateService emailTemplateService;
    private final EmailService emailService;

    /** 03:30 UTC daily ≈ 09:00 IST. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void runScheduled() {
        if (!emailService.isConfigured()) {
            log.debug("Skipping document-reminder job — mail not configured");
            return;
        }
        int sent = sendReminders();
        log.info("Document-reminder job (scheduled): emails sent = {}", sent);
    }

    @Transactional
    public int sendReminders() {
        LocalDateTime minAge = LocalDateTime.now().minusHours(MIN_ACCOUNT_AGE_HOURS);
        LocalDateTime cooldown = LocalDateTime.now().minusDays(COOLDOWN_DAYS);
        int sent = 0;

        for (String status : STUCK_STATUSES) {
            List<User> bucket = userRepository.findByCurrentStatus(status);
            for (User u : bucket) {
                if (!Boolean.TRUE.equals(u.getIsActive())) continue;
                if (u.getEmail() == null || u.getEmail().isBlank()) continue;
                // Skip very new accounts — give them a day before the
                // first nudge so the email lands in a real-time-of-day
                // window rather than seconds after signup.
                if (u.getCreatedAt() != null
                        && u.getCreatedAt().isAfter(minAge)) continue;
                // Throttle to every 3 days. We reuse last_nudge_sent_at
                // from the LMS inactive-nudge job since the same
                // semantics apply: "did we already prod this user
                // recently?".
                if (u.getLastNudgeSentAt() != null
                        && u.getLastNudgeSentAt().isAfter(cooldown)) continue;
                // Skip if any documents are already on file (covers
                // the case where the user uploaded but didn't click
                // "submit for review", which the dashboard handles
                // separately).
                if (!documentRepository.findAll().stream()
                        .filter(d -> u.getId().equals(d.getUserId()))
                        .toList().isEmpty()) continue;
                try {
                    emailTemplateService.sendDocumentReminderEmail(u);
                    u.setLastNudgeSentAt(LocalDateTime.now());
                    userRepository.save(u);
                    sent++;
                } catch (Exception e) {
                    log.warn("Document-reminder skipped for user {}: {}",
                            u.getId(), e.getMessage());
                }
            }
        }
        return sent;
    }
}
