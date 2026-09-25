package com.spire.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


/**
 * Phase 7 — scheduled invoice generation + overdue marking.
 *
 * Two daily cron triggers (business time, US Central):
 *   - 04:00 — issue invoices whose installment is due within 14 days
 *             (idempotent: existing invoices for a plan are skipped).
 *   - 04:15 — flip past-due UNPAID invoices to OVERDUE and email the
 *             participant a reminder.
 *
 * Both methods are safe to call manually from {@link
 * com.spire.backend.controller.FinanceController}'s bulk-generate /
 * mark-overdue endpoints.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentJob {

    private final PaymentService paymentService;
    private final EmailService emailService;
    /** Checklist 5.1: "today" is the business day (US Central), not the server's UTC date. */
    private final BusinessClock clock;

    @Scheduled(cron = "0 0 4 * * *", zone = "${app.business-zone:America/Chicago}")
    @Transactional
    public void runInvoiceGeneration() {
        if (!emailService.isConfigured()) {
            // Without SMTP the issue notice won't reach the user;
            // still safe to issue but log a warning so the operator
            // notices.
            log.debug("Issuing invoices without email — SMTP not configured");
        }
        int issued = paymentService.generateAllDue(clock.today()).size();
        log.info("Payment-cron: invoices issued = {}", issued);
    }

    @Scheduled(cron = "0 15 4 * * *", zone = "${app.business-zone:America/Chicago}")
    @Transactional
    public void runOverdueSweep() {
        int marked = paymentService.markOverdueInvoices(clock.today());
        log.info("Payment-cron: invoices marked overdue = {}", marked);
    }
}
