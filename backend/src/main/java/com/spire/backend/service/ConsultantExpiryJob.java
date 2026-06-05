package com.spire.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily sweep that flips in-flight Consultant Agreement applications
 * past their {@code expiresAt} to EXPIRED. Default application TTL
 * is 7 days from creation.
 *
 * Runs at 03:45 UTC (~09:15 IST) — same band as the other Sage
 * cron jobs so failures land in a single morning review window.
 *
 * Safe to call manually from a test or via a future operations
 * endpoint: {@link ConsultantApplicationService#expireStaleApplications()}
 * is idempotent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsultantExpiryJob {

    private final ConsultantApplicationService consultantService;

    @Scheduled(cron = "0 45 3 * * *")
    public void runDailySweep() {
        int expired = consultantService.expireStaleApplications();
        if (expired > 0) {
            log.info("Consultant-expiry-cron: applications expired = {}", expired);
        } else {
            log.debug("Consultant-expiry-cron: no stale applications");
        }
    }
}
