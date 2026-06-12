package com.spire.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Build Q — DISABLED. Agreements no longer expire: the 7-day TTL applies
 * only to the consultant ACCESS LINK and is surfaced as a derived,
 * non-mutating indicator ({@link ConsultantApplicationService#isConsultantLinkExpired})
 * — never a status change. The former daily sweep that flipped SUBMITTED
 * rows to EXPIRED has been removed (no {@code @Scheduled}); the bean is
 * retained as an inert component so deployment config referencing it
 * keeps resolving. {@link ConsultantApplicationService#expireStaleApplications()}
 * is now a no-op and can be wired to a manual ops endpoint if ever needed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsultantExpiryJob {

    private final ConsultantApplicationService consultantService;

    /** Manual-only sweep hook; no longer scheduled and no-ops (returns 0). */
    public void runDailySweep() {
        consultantService.expireStaleApplications();
    }
}
