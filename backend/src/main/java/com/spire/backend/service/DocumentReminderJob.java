package com.spire.backend.service;

import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.User;
import com.spire.backend.repository.EmailLogRepository;
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
 * Roadmap email "Acknowledgment / Document Upload Reminder" (checklist
 * 1.5): to a participant whose next step is the acknowledgment or the
 * required documents — including a document Operations sent back before
 * the agreement was signed. It says exactly what is still missing.
 *
 * Participants only (never staff), active, email verified, with a
 * Participant ID. The first reminder comes after a day, then every 3
 * days, at most {@value #MAX_REMINDERS} in all (counted from the email
 * log). Someone whose only open item is a "not applicable" request
 * waiting for Operations isn't reminded: it's not theirs to do.
 * Participants at other steps get the profile reminder instead
 * (ProfileReminderJob), so nobody gets both.
 *
 * Cron: daily at 09:00 business time (app.business-zone, Central).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentReminderJob {

    static final Set<String> STEPS = Set.of("ACKNOWLEDGMENT", "DOCUMENTS");
    static final Set<String> PARTICIPANT_ROLES = Set.of("PARTICIPANT", "STUDENT");
    /** Account must be at least this old before we nudge. */
    private static final int MIN_ACCOUNT_AGE_HOURS = 24;
    private static final int COOLDOWN_DAYS = 3;
    static final int MAX_REMINDERS = 5;

    private final UserRepository userRepository;
    private final ParticipantDocumentRepository documentRepository;
    private final DocumentService documentService;
    private final ProfileCompletionService profileCompletionService;
    private final EmailLogRepository emailLogRepository;
    private final EmailTemplateService emailTemplateService;
    private final EmailService emailService;

    @Scheduled(cron = "0 0 9 * * *", zone = "${app.business-zone:America/Chicago}")
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
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime minAge = now.minusHours(MIN_ACCOUNT_AGE_HOURS);
        LocalDateTime cooldown = now.minusDays(COOLDOWN_DAYS);
        int sent = 0;
        for (User u : userRepository.findAll()) {
            if (!isCandidate(u, minAge, cooldown)) continue;
            String step = profileCompletionService.nextStepKey(u);
            if (!STEPS.contains(step)) continue;
            if (emailLogRepository.countByEmailTypeAndUserIdAndStatus(
                    "DOCUMENT_REMINDER", u.getId(), EmailLogService.SENT) >= MAX_REMINDERS) continue;

            boolean acknowledgmentPending = "ACKNOWLEDGMENT".equals(step);
            List<String> missing = List.of();
            if (!acknowledgmentPending) {
                List<ParticipantDocument> docs = documentRepository.findByUserIdOrderByUploadedAtDesc(u.getId());
                missing = documentService.missingRequired(u.getId()).stream()
                        .filter(t -> docs.stream().noneMatch(d -> t.equals(d.getDocumentType())
                                && DocumentService.EXCEPTION_REQUESTED.equals(d.getReviewStatus())))
                        .map(DocumentService::labelFor)
                        .toList();
                if (missing.isEmpty()) continue;   // only waiting on Operations
            }
            try {
                // Stamp only when it really went out, so a failed
                // reminder is tried again on the next run.
                if (!emailTemplateService.sendDocumentReminderEmail(u, acknowledgmentPending, missing)) continue;
                u.setLastNudgeSentAt(LocalDateTime.now());
                userRepository.save(u);
                sent++;
            } catch (Exception e) {
                log.warn("Document-reminder skipped for user {}: {}", u.getId(), e.getMessage());
            }
        }
        return sent;
    }

    /** A verified, active participant with a Participant ID, not reminded lately. */
    static boolean isCandidate(User u, LocalDateTime minAge, LocalDateTime cooldown) {
        if (u.getRole() == null || !PARTICIPANT_ROLES.contains(u.getRole().getName())) return false;
        if (!Boolean.TRUE.equals(u.getIsActive()) || !Boolean.TRUE.equals(u.getEmailVerified())) return false;
        if (u.getParticipantId() == null || u.getParticipantId().isBlank()) return false;
        if (u.getEmail() == null || u.getEmail().isBlank()) return false;
        if (u.getCreatedAt() != null && u.getCreatedAt().isAfter(minAge)) return false;
        return u.getLastNudgeSentAt() == null || !u.getLastNudgeSentAt().isAfter(cooldown);
    }
}
