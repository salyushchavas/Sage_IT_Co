package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.service.DocumentReminderJob;
import com.spire.backend.service.ProfileReminderJob;
import com.spire.backend.service.WeeklyReminderJob;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal endpoints that Vercel cron jobs (or operations admins via
 * curl) hit to trigger backend maintenance work. All endpoints here
 * are gated on a shared {@code agreement.cron.secret} header — the
 * same secret the agreement IMAP cron used to use is reused for the
 * weekly reminder so we only carry one rotating token.
 */
@RestController
@RequiredArgsConstructor
public class InternalCronController {

    private final WeeklyReminderJob weeklyReminderJob;
    private final DocumentReminderJob documentReminderJob;
    private final ProfileReminderJob profileReminderJob;

    @Value("${agreement.cron.secret:}")
    private String cronSecret;

    /**
     * Triggers the weekly reminder sweep. Idempotent — re-running in
     * the same week is a no-op for users who already submitted.
     */
    @PostMapping("/api/internal/weekly-reminder")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runWeeklyReminder(
            @RequestHeader(value = "X-Cron-Secret", required = false) String headerSecret) {
        if (cronSecret == null || cronSecret.isBlank()
                || !cronSecret.equals(headerSecret)) {
            throw new UnauthorizedException("Invalid cron secret");
        }
        int sent = weeklyReminderJob.sendReminders();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "ok", true,
                "sent", sent
        )));
    }

    /**
     * Triggers the document-upload reminder sweep. Idempotent — the
     * job's per-user cooldown prevents back-to-back nudges.
     */
    @PostMapping("/api/internal/document-reminder")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runDocumentReminder(
            @RequestHeader(value = "X-Cron-Secret", required = false) String headerSecret) {
        if (cronSecret == null || cronSecret.isBlank()
                || !cronSecret.equals(headerSecret)) {
            throw new UnauthorizedException("Invalid cron secret");
        }
        int sent = documentReminderJob.sendReminders();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "ok", true,
                "sent", sent
        )));
    }

    /**
     * Triggers the profile-completion reminder sweep (Phase 1C).
     * Sends at most 3 emails per user lifetime, throttled by the
     * job's own cooldown so back-to-back invocations are no-ops for
     * already-nudged users.
     */
    @PostMapping("/api/internal/profile-reminder")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runProfileReminder(
            @RequestHeader(value = "X-Cron-Secret", required = false) String headerSecret) {
        if (cronSecret == null || cronSecret.isBlank()
                || !cronSecret.equals(headerSecret)) {
            throw new UnauthorizedException("Invalid cron secret");
        }
        int sent = profileReminderJob.sendReminders();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "ok", true,
                "sent", sent
        )));
    }
}
