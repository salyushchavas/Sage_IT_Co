package com.spire.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.entity.Invoice;
import com.spire.backend.entity.PaymentLedger;
import com.spire.backend.entity.PaymentPlan;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.InvoiceRepository;
import com.spire.backend.repository.PaymentLedgerRepository;
import com.spire.backend.repository.PaymentPlanRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 7 — payment plans + invoices + ledger.
 *
 * Finance creates the plan; the participant only reviews + accepts.
 * Acceptance flips the workflow from PHASE_1_COMPLETED →
 * PAYMENT_PLAN_ACCEPTED. Generating the first invoice advances the
 * status to INVOICING_ACTIVE. Each accepted payment advances to
 * PAYMENTS_TRACKED.
 *
 * Checklist 5.1: the server builds the schedule — equal monthly
 * instalments in whole cents that add up exactly to the total, due on
 * the same day each month. One open plan per participant. Finance can
 * change a plan until its first invoice; a plan the participant already
 * accepted then needs their acceptance again.
 *
 * Checklist 5.2: the ledger records payments, failed payments, waivers
 * and reversals; a payment can't exceed the balance; an invoice that's
 * part-paid still goes overdue.
 *
 * Per PRD §9.1, full payment data (amounts, ledger) is restricted to
 * the participant themselves and finance; ERMs only see a high-level
 * status summary, and coaches see nothing. Cross-role access is
 * gated at the controller layer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    public static final String PLAN_ACK_VERSION = "PPL-v1.0";
    public static final int MAX_INSTALLMENTS = 60;
    public static final BigDecimal MAX_PLAN_TOTAL = new BigDecimal("1000000");

    public static final String ENTRY_PAYMENT = "PAYMENT";
    public static final String ENTRY_FAILED = "FAILED";
    public static final String ENTRY_WAIVER = "WAIVER";
    public static final String ENTRY_REVERSAL = "REVERSAL";
    public static final Set<String> ENTRY_TYPES = Set.of(ENTRY_PAYMENT, ENTRY_FAILED, ENTRY_WAIVER, ENTRY_REVERSAL);
    /** How money arrives. CHEQUE is a paper check. */
    public static final Set<String> METHODS = Set.of("CHEQUE", "BANK_TRANSFER", "CARD", "CASH", "ONLINE", "ADJUSTMENT");

    private final PaymentPlanRepository planRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentLedgerRepository ledgerRepository;
    private final UserRepository userRepository;
    private final WorkflowService workflowService;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;
    private final BusinessClock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── Finance creates plan ───────────────────────────────────────

    public record ScheduleItem(LocalDate dueDate, BigDecimal amount, String label) {}

    /**
     * Equal monthly instalments in whole cents that add up exactly to the
     * total (the first ones take any leftover cents), due on the same day
     * of each month — the 31st becomes the month's last day.
     */
    public static List<ScheduleItem> buildSchedule(BigDecimal total, int installments, LocalDate firstDue) {
        if (installments < 1 || installments > MAX_INSTALLMENTS) {
            throw new IllegalArgumentException("Installments must be between 1 and " + MAX_INSTALLMENTS + ".");
        }
        if (firstDue == null) throw new IllegalArgumentException("Pick the first due date.");
        long cents = Money.cents(total);
        if (cents < installments) throw new IllegalArgumentException("The total is too small for that many installments.");
        long base = cents / installments;
        long extra = cents % installments;
        List<ScheduleItem> out = new ArrayList<>();
        for (int i = 0; i < installments; i++) {
            out.add(new ScheduleItem(firstDue.plusMonths(i), Money.fromCents(base + (i < extra ? 1 : 0)),
                    "Installment " + (i + 1) + " of " + installments));
        }
        return out;
    }

    /** A schedule must add up to the total to the cent, with positive amounts and due dates in order. */
    static void validateSchedule(BigDecimal total, List<ScheduleItem> schedule) {
        if (schedule == null || schedule.isEmpty()) {
            throw new IllegalArgumentException("Schedule must include at least one installment.");
        }
        if (schedule.size() > MAX_INSTALLMENTS) {
            throw new IllegalArgumentException("A plan can have at most " + MAX_INSTALLMENTS + " installments.");
        }
        long sum = 0;
        LocalDate previous = null;
        for (ScheduleItem it : schedule) {
            if (it.dueDate() == null) throw new IllegalArgumentException("Every installment needs a due date.");
            if (it.amount() == null || it.amount().signum() <= 0) {
                throw new IllegalArgumentException("Every installment needs an amount above zero.");
            }
            if (previous != null && !it.dueDate().isAfter(previous)) {
                throw new IllegalArgumentException("Installment due dates must be in order, one after another.");
            }
            previous = it.dueDate();
            sum += Money.cents(it.amount());
        }
        if (sum != Money.cents(total)) {
            throw new IllegalArgumentException("The installments add up to " + Money.usd(Money.fromCents(sum))
                    + ", not the plan total of " + Money.usd(total) + ".");
        }
    }

    private void validateTotal(BigDecimal totalAmount) {
        if (totalAmount == null || totalAmount.signum() <= 0) {
            throw new IllegalArgumentException("Total amount must be positive.");
        }
        Money.cents(totalAmount);
        if (totalAmount.compareTo(MAX_PLAN_TOTAL) > 0) {
            throw new IllegalArgumentException("That total looks too large. Check the amount.");
        }
    }

    /** The schedule Finance asked for: the given one (checked), or equal monthly instalments. */
    private List<ScheduleItem> scheduleFor(BigDecimal totalAmount, Integer installments, LocalDate firstDue,
                                           List<ScheduleItem> given) {
        List<ScheduleItem> schedule;
        if (given != null && !given.isEmpty()) {
            schedule = given;
        } else {
            if (firstDue != null && firstDue.isBefore(clock.today())) {
                throw new IllegalArgumentException("The first due date can't be in the past.");
            }
            schedule = buildSchedule(totalAmount, installments == null ? 1 : installments, firstDue);
        }
        validateSchedule(totalAmount, schedule);
        return schedule;
    }

    /** Checklist 5.1: what a plan would look like, without saving it. */
    public List<ScheduleItem> previewSchedule(BigDecimal totalAmount, Integer installments, LocalDate firstDue) {
        validateTotal(totalAmount);
        return scheduleFor(totalAmount, installments, firstDue, null);
    }

    @Transactional
    public PaymentPlan createPlan(Long financeUserId, Long participantId,
                                  BigDecimal totalAmount, Integer installments, LocalDate firstDue,
                                  List<ScheduleItem> schedule) {
        User participant = userRepository.findById(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", participantId));
        if (participant.getRole() == null || !"PARTICIPANT".equalsIgnoreCase(participant.getRole().getName())) {
            throw new IllegalArgumentException("Payment plans are for participants only.");
        }
        if (!workflowService.isStatusAtLeast(participant,
                WorkflowService.Status.PHASE_1_COMPLETED)) {
            throw new IllegalStateException(
                    "Participant must complete Phase 1 before a payment plan can be created.");
        }
        validateTotal(totalAmount);
        Optional<PaymentPlan> open = planRepository.findByUserIdOrderByIdDesc(participantId).stream()
                .filter(p -> "PENDING".equals(p.getStatus()) || "ACTIVE".equals(p.getStatus()))
                .findFirst();
        if (open.isPresent()) {
            throw new IllegalStateException("This participant already has a payment plan ("
                    + open.get().getPlanId() + "). Edit that one instead.");
        }
        List<ScheduleItem> items = scheduleFor(totalAmount, installments, firstDue, schedule);

        String planNumber = generatePlanNumber();
        PaymentPlan plan = PaymentPlan.builder()
                .userId(participantId)
                .planId(planNumber)
                .totalAmount(totalAmount)
                .installments(items.size())
                .schedule(serialiseSchedule(items))
                .status("PENDING")
                .build();
        PaymentPlan saved = planRepository.save(plan);

        recordService.logAction(participantId, RecordService.Category.PAYMENT,
                "Payment plan created",
                "Plan " + planNumber + " — " + Money.usd(totalAmount),
                Map.of("planId", saved.getId(), "planNumber", planNumber,
                        "totalAmount", totalAmount,
                        "installments", saved.getInstallments(),
                        "createdByUserId", financeUserId));
        try {
            emailTemplateService.sendPaymentPlanReadyEmail(participant, saved, items, false);
        } catch (Exception e) {
            log.warn("Plan-ready email failed for user {}: {}", participantId, e.getMessage());
        }
        log.info("Payment plan {} created for user {} by finance {}",
                planNumber, participantId, financeUserId);
        return saved;
    }

    /**
     * Checklist 5.1: Finance changes a plan until its first invoice is
     * issued. If the participant had already accepted it, they're asked to
     * accept the changed plan (no invoices go out until they do).
     */
    @Transactional
    public PaymentPlan updatePlan(Long financeUserId, Long planId,
                                  BigDecimal totalAmount, Integer installments, LocalDate firstDue,
                                  List<ScheduleItem> schedule) {
        PaymentPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("PaymentPlan", "id", planId));
        if (!"PENDING".equals(plan.getStatus()) && !"ACTIVE".equals(plan.getStatus())) {
            throw new IllegalStateException("This plan is " + plan.getStatus().toLowerCase() + " and can't be changed.");
        }
        if (!invoiceRepository.findByPaymentPlanId(planId).isEmpty()) {
            throw new IllegalStateException(
                    "Invoices have already been issued for this plan. Record a waiver or an adjustment on the invoice instead.");
        }
        BigDecimal total = totalAmount == null ? plan.getTotalAmount() : totalAmount;
        validateTotal(total);
        List<ScheduleItem> items = scheduleFor(total, installments == null ? plan.getInstallments() : installments,
                firstDue, schedule);
        LocalDateTime previousAcceptance = plan.getAcceptedAt();
        plan.setTotalAmount(total);
        plan.setInstallments(items.size());
        plan.setSchedule(serialiseSchedule(items));
        if (previousAcceptance != null) {
            plan.setAcceptedAt(null);
            plan.setAcceptanceTextVersion(null);
            plan.setIpAddress(null);
            plan.setStatus("PENDING");
        }
        PaymentPlan saved = planRepository.save(plan);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("planId", saved.getId());
        details.put("editorUserId", financeUserId);
        details.put("totalAmount", total);
        details.put("installments", items.size());
        if (previousAcceptance != null) details.put("previouslyAcceptedAt", previousAcceptance.toString());
        recordService.logAction(plan.getUserId(), RecordService.Category.PAYMENT,
                previousAcceptance != null ? "Payment plan changed; the participant must accept it again"
                        : "Payment plan updated",
                "Plan " + plan.getPlanId() + " — " + Money.usd(total), details);
        userRepository.findById(plan.getUserId()).ifPresent(u -> {
            try {
                emailTemplateService.sendPaymentPlanReadyEmail(u, saved, items, true);
            } catch (Exception e) {
                log.warn("Plan-updated email failed for user {}: {}", u.getId(), e.getMessage());
            }
        });
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PaymentPlan> allPlans() {
        return planRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<PaymentPlan> latestPlanForUser(Long userId) {
        return planRepository.findLatestByUserId(userId);
    }

    // ─── Participant reviews + accepts ───────────────────────────────

    @Transactional
    public PaymentPlan acceptPlan(Long userId, Long planId, String version, String ipAddress) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.PHASE_1_COMPLETED)) {
            throw new IllegalStateException(
                    "Phase 1 must be completed before accepting a payment plan.");
        }

        PaymentPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("PaymentPlan", "id", planId));
        if (!userId.equals(plan.getUserId())) {
            throw new org.springframework.security.access.AccessDeniedException("Not your plan.");
        }
        if (plan.getAcceptedAt() != null) {
            return plan;
        }
        if (!"PENDING".equals(plan.getStatus())) {
            throw new IllegalStateException("This plan can no longer be accepted.");
        }

        plan.setAcceptedAt(LocalDateTime.now());
        plan.setAcceptanceTextVersion(version == null || version.isBlank()
                ? PLAN_ACK_VERSION : version);
        plan.setIpAddress(ipAddress);
        plan.setStatus("ACTIVE");
        PaymentPlan saved = planRepository.save(plan);

        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.PAYMENT_PLAN_ACCEPTED)) {
            workflowService.transition(user,
                    WorkflowService.Status.PAYMENT_PLAN_ACCEPTED,
                    "payment_plan_accepted");
        }

        recordService.logAction(userId, RecordService.Category.PAYMENT,
                "Payment plan accepted",
                "Plan " + plan.getPlanId(),
                Map.of("planId", saved.getId(), "version", saved.getAcceptanceTextVersion(),
                        "ip", ipAddress == null ? "" : ipAddress));
        try {
            emailTemplateService.sendPaymentPlanAcceptedEmail(user, saved,
                    parseSchedule(saved.getSchedule()));
        } catch (Exception ignored) {}
        return saved;
    }

    // ─── Invoice generation ─────────────────────────────────────────

    /**
     * Generates the next un-invoiced installment for a plan. Returns
     * the new invoice, or empty if the schedule is exhausted.
     */
    @Transactional
    public Optional<Invoice> generateNextInvoice(Long planId, LocalDate today) {
        PaymentPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("PaymentPlan", "id", planId));
        if (plan.getAcceptedAt() == null) {
            throw new IllegalStateException("Plan must be accepted before invoices can issue.");
        }
        List<ScheduleItem> items = parseSchedule(plan.getSchedule());
        List<Invoice> existing = invoiceRepository.findByPaymentPlanId(planId);
        if (existing.size() >= items.size()) return Optional.empty();

        LocalDate issued = today == null ? clock.today() : today;
        ScheduleItem next = items.get(existing.size());
        String number = generateInvoiceNumber();
        Invoice inv = Invoice.builder()
                .invoiceNumber(number)
                .userId(plan.getUserId())
                .paymentPlanId(planId)
                .amount(next.amount())
                .dueDate(next.dueDate())
                .issueDate(issued)
                .balance(next.amount())
                .status("UNPAID")
                .build();
        inv.setStatus(statusFor(inv, issued));
        Invoice saved = invoiceRepository.save(inv);

        User user = userRepository.findById(plan.getUserId()).orElse(null);
        if (user != null && !workflowService.isStatusAtLeast(user,
                WorkflowService.Status.INVOICING_ACTIVE)) {
            workflowService.transition(user,
                    WorkflowService.Status.INVOICING_ACTIVE,
                    "first_invoice_issued");
        }
        recordService.logAction(plan.getUserId(), RecordService.Category.PAYMENT,
                "Invoice issued",
                number + " — " + Money.usd(saved.getAmount()),
                Map.of("invoiceId", saved.getId(), "planId", planId));
        if (user != null) {
            try {
                emailTemplateService.sendInvoiceIssuedEmail(user, saved);
            } catch (Exception ignored) {}
        }
        return Optional.of(saved);
    }

    @Transactional
    public List<Invoice> generateAllDue(LocalDate today) {
        List<Invoice> out = new ArrayList<>();
        for (PaymentPlan plan : planRepository.findAll()) {
            if (plan.getAcceptedAt() == null || !"ACTIVE".equals(plan.getStatus())) continue;
            List<ScheduleItem> items = parseSchedule(plan.getSchedule());
            List<Invoice> existing = invoiceRepository.findByPaymentPlanId(plan.getId());
            for (int i = existing.size(); i < items.size(); i++) {
                ScheduleItem next = items.get(i);
                // Only issue when the due-date window is approaching
                // (within 14 days of due) — avoids dumping every
                // future installment on day one.
                if (next.dueDate() != null
                        && next.dueDate().isAfter(today.plusDays(14))) break;
                generateNextInvoice(plan.getId(), today).ifPresent(out::add);
            }
        }
        return out;
    }

    // ─── Ledger (checklist 5.2) ─────────────────────────────────────

    /** A payment received, as before. */
    @Transactional
    public PaymentLedger recordPayment(Long financeUserId, Long invoiceId,
                                       BigDecimal amount, LocalDate receiptDate,
                                       String method, String notes) {
        return recordEntry(financeUserId, invoiceId, ENTRY_PAYMENT, amount, receiptDate, method, notes, null);
    }

    /**
     * Checklist 5.2: one ledger entry on an invoice.
     * <ul>
     *   <li>PAYMENT: money received; at most the balance (no overpaying).</li>
     *   <li>FAILED: an attempt that didn't go through (a declined card, a
     *       check that never cleared); the balance doesn't change.</li>
     *   <li>WAIVER: part or all of the balance written off.</li>
     *   <li>REVERSAL: an earlier payment undone (e.g. a bounced check); its
     *       amount goes back on the balance.</li>
     * </ul>
     * Failed payments, waivers and reversals need a reason. The invoice's
     * status follows its balance and due date: a part-paid invoice past its
     * due date is OVERDUE.
     */
    @Transactional
    public PaymentLedger recordEntry(Long financeUserId, Long invoiceId, String entryType,
                                     BigDecimal amount, LocalDate receiptDate,
                                     String method, String notes, Long reversesLedgerId) {
        Invoice inv = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", "id", invoiceId));
        String type = entryType == null || entryType.isBlank() ? ENTRY_PAYMENT : entryType.trim().toUpperCase();
        if (!ENTRY_TYPES.contains(type)) {
            throw new IllegalArgumentException("Entry type must be PAYMENT, FAILED, WAIVER or REVERSAL.");
        }
        if ("VOID".equals(inv.getStatus())) {
            throw new IllegalStateException("This invoice is void.");
        }
        LocalDate today = clock.today();
        LocalDate date = receiptDate == null ? today : receiptDate;
        if (date.isAfter(today)) throw new IllegalArgumentException("The date can't be in the future.");
        String reason = notes == null ? "" : notes.trim();
        if (!ENTRY_PAYMENT.equals(type) && reason.length() < 3) {
            throw new IllegalArgumentException("Give a reason for the " + type.toLowerCase() + ".");
        }

        BigDecimal balance = balanceOf(inv);
        String how = method == null || method.isBlank() ? "CHEQUE" : method.trim().toUpperCase();
        PaymentLedger reversed = null;
        if (ENTRY_REVERSAL.equals(type)) {
            if (reversesLedgerId == null) throw new IllegalArgumentException("Pick the payment to reverse.");
            reversed = ledgerRepository.findById(reversesLedgerId)
                    .filter(l -> invoiceId.equals(l.getInvoiceId()) && ENTRY_PAYMENT.equals(typeOf(l)))
                    .orElseThrow(() -> new IllegalArgumentException("That isn't a payment on this invoice."));
            Long reversedId = reversed.getId();
            boolean already = ledgerRepository.findByInvoiceIdOrderByCreatedAtAsc(invoiceId).stream()
                    .anyMatch(l -> ENTRY_REVERSAL.equals(typeOf(l)) && reversedId.equals(l.getReversesLedgerId()));
            if (already) throw new IllegalStateException("That payment has already been reversed.");
            amount = reversed.getAmountReceived();
            how = reversed.getMethod();
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        Money.cents(amount);
        if (ENTRY_PAYMENT.equals(type) || ENTRY_WAIVER.equals(type)) {
            if (balance.signum() <= 0) throw new IllegalStateException("This invoice is already paid in full.");
            if (amount.compareTo(balance) > 0) {
                throw new IllegalArgumentException("That's more than the balance of " + Money.usd(balance)
                        + " on invoice " + inv.getInvoiceNumber() + ".");
            }
        }
        if (ENTRY_WAIVER.equals(type)) how = "WAIVER";
        if ((ENTRY_PAYMENT.equals(type) || ENTRY_FAILED.equals(type)) && !METHODS.contains(how)) {
            throw new IllegalArgumentException("Unknown payment method: " + how);
        }

        BigDecimal newBalance = switch (type) {
            case ENTRY_PAYMENT, ENTRY_WAIVER -> balance.subtract(amount);
            case ENTRY_REVERSAL -> balance.add(amount);
            default -> balance;   // FAILED
        };
        String before = inv.getStatus();
        inv.setBalance(newBalance);
        inv.setStatus(statusFor(inv, today));
        inv.setPaidDate("PAID".equals(inv.getStatus()) ? date : null);
        invoiceRepository.save(inv);

        PaymentLedger row = PaymentLedger.builder()
                .invoiceId(invoiceId)
                .userId(inv.getUserId())
                .entryType(type)
                .amountReceived(amount)
                .receiptDate(date)
                .method(how)
                .balance(newBalance)
                .notes(reason.isEmpty() ? null : reason)
                .financeReviewer(financeUserId == null ? "" : financeUserId.toString())
                .reversesLedgerId(reversed == null ? null : reversed.getId())
                .build();
        PaymentLedger saved = ledgerRepository.save(row);

        User user = userRepository.findById(inv.getUserId()).orElse(null);
        if (ENTRY_PAYMENT.equals(type) && user != null && !workflowService.isStatusAtLeast(user,
                WorkflowService.Status.PAYMENTS_TRACKED)) {
            workflowService.transition(user,
                    WorkflowService.Status.PAYMENTS_TRACKED,
                    "first_payment_received");
        }
        completePlanIfPaid(inv.getPaymentPlanId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invoiceId", invoiceId);
        details.put("ledgerId", saved.getId());
        details.put("entryType", type);
        details.put("amount", amount);
        details.put("method", saved.getMethod());
        details.put("invoiceStatus", before + " → " + inv.getStatus());
        details.put("financeUserId", financeUserId == null ? 0 : financeUserId);
        recordService.logAction(inv.getUserId(), RecordService.Category.PAYMENT,
                switch (type) {
                    case ENTRY_PAYMENT -> "Payment received";
                    case ENTRY_FAILED -> "Payment failed";
                    case ENTRY_WAIVER -> "Amount waived";
                    default -> "Payment reversed";
                },
                inv.getInvoiceNumber() + " — " + Money.usd(amount) + (reason.isEmpty() ? "" : " — " + reason),
                details);
        if (user != null) {
            try {
                switch (type) {
                    case ENTRY_PAYMENT -> emailTemplateService.sendPaymentReceivedEmail(user, inv, saved);
                    case ENTRY_FAILED -> emailTemplateService.sendPaymentProblemEmail(user, inv, amount, reason, false);
                    case ENTRY_REVERSAL -> emailTemplateService.sendPaymentProblemEmail(user, inv, amount, reason, true);
                    default -> { }   // a waiver shows on their dashboard
                }
            } catch (Exception e) {
                log.warn("Ledger email failed for user {}: {}", inv.getUserId(), e.getMessage());
            }
        }
        return saved;
    }

    /** Checklist 5.2: a plan whose every instalment is invoiced and paid is COMPLETED. */
    private void completePlanIfPaid(Long planId) {
        if (planId == null) return;
        planRepository.findById(planId).ifPresent(plan -> {
            List<Invoice> invoices = invoiceRepository.findByPaymentPlanId(planId);
            int scheduled = parseSchedule(plan.getSchedule()).size();
            boolean allPaid = invoices.size() >= scheduled && invoices.stream()
                    .allMatch(i -> "PAID".equals(i.getStatus()) || "VOID".equals(i.getStatus()));
            String next = allPaid ? "COMPLETED" : ("COMPLETED".equals(plan.getStatus()) ? "ACTIVE" : plan.getStatus());
            if (!next.equals(plan.getStatus())) {
                plan.setStatus(next);
                planRepository.save(plan);
            }
        });
    }

    /** What an invoice's status should be from its balance and due date. */
    static String statusFor(Invoice inv, LocalDate today) {
        if ("VOID".equals(inv.getStatus())) return "VOID";
        BigDecimal balance = balanceOf(inv);
        if (balance.signum() <= 0) return "PAID";
        if (inv.getDueDate() != null && inv.getDueDate().isBefore(today)) return "OVERDUE";
        return inv.getAmount() != null && balance.compareTo(inv.getAmount()) < 0 ? "PARTIAL" : "UNPAID";
    }

    static BigDecimal balanceOf(Invoice inv) {
        if (inv.getBalance() != null) return inv.getBalance();
        return inv.getAmount() == null ? BigDecimal.ZERO : inv.getAmount();
    }

    /** Rows from before entry types existed are payments. */
    public static String typeOf(PaymentLedger l) {
        return l.getEntryType() == null || l.getEntryType().isBlank() ? ENTRY_PAYMENT : l.getEntryType();
    }

    // ─── Overdue marker (idempotent) ────────────────────────────────

    /**
     * Unpaid and part-paid invoices past their due date become OVERDUE
     * (checklist 5.2: part-paid ones used to stay "PARTIAL" forever). The
     * participant is emailed once, when it happens.
     */
    @Transactional
    public int markOverdueInvoices(LocalDate today) {
        int marked = 0;
        List<Invoice> candidates = new ArrayList<>(invoiceRepository.findByStatusOrderByDueDateAsc("UNPAID"));
        candidates.addAll(invoiceRepository.findByStatusOrderByDueDateAsc("PARTIAL"));
        for (Invoice inv : candidates) {
            if (!"OVERDUE".equals(statusFor(inv, today))) continue;
            inv.setStatus("OVERDUE");
            invoiceRepository.save(inv);
            marked++;
            User user = userRepository.findById(inv.getUserId()).orElse(null);
            if (user != null) {
                try { emailTemplateService.sendInvoiceOverdueEmail(user, inv); }
                catch (Exception ignored) {}
            }
        }
        return marked;
    }

    // ─── Read-side helpers ──────────────────────────────────────────

    /**
     * The participant's money at a glance. Paid = payments minus reversals;
     * waived amounts are shown separately and aren't owed.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> participantSummary(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<PaymentPlan> plan = planRepository.findLatestByUserId(userId);
        List<Invoice> invoices = invoiceRepository.findByUserIdOrderByIssueDateDesc(userId);
        List<PaymentLedger> ledger = ledgerRepository.findByUserIdOrderByCreatedAtDesc(userId);

        BigDecimal totalDue = plan.map(PaymentPlan::getTotalAmount).orElse(BigDecimal.ZERO);
        BigDecimal totalPaid = sum(ledger, ENTRY_PAYMENT).subtract(sum(ledger, ENTRY_REVERSAL));
        BigDecimal totalWaived = sum(ledger, ENTRY_WAIVER);
        BigDecimal balance = totalDue.subtract(totalPaid).subtract(totalWaived);
        BigDecimal overdue = invoices.stream()
                .filter(i -> "OVERDUE".equals(i.getStatus()))
                .map(PaymentService::balanceOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Invoice nextDue = invoices.stream()
                .filter(i -> Set.of("UNPAID", "PARTIAL", "OVERDUE").contains(i.getStatus()))
                .min(Comparator.comparing(Invoice::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);

        out.put("totalDue", totalDue);
        out.put("totalPaid", totalPaid);
        out.put("totalWaived", totalWaived);
        out.put("balance", balance);
        out.put("overdue", overdue);
        if (nextDue != null) {
            out.put("nextDueAmount", balanceOf(nextDue));
            out.put("nextDueDate", nextDue.getDueDate());
            out.put("nextDueInvoice", nextDue.getInvoiceNumber());
        }
        return out;
    }

    /** Money actually collected: payments minus reversals (checklist 5.2). */
    public static BigDecimal collected(List<PaymentLedger> ledger) {
        return sum(ledger, ENTRY_PAYMENT).subtract(sum(ledger, ENTRY_REVERSAL));
    }

    private static BigDecimal sum(List<PaymentLedger> ledger, String type) {
        return ledger.stream()
                .filter(l -> type.equals(typeOf(l)))
                .map(l -> l.getAmountReceived() == null ? BigDecimal.ZERO : l.getAmountReceived())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ─── Helpers ────────────────────────────────────────────────────

    public List<ScheduleItem> parseSchedule(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<ScheduleItem> out = new ArrayList<>();
            for (Map<String, Object> r : raw) {
                LocalDate due = r.get("dueDate") == null ? null
                        : LocalDate.parse(r.get("dueDate").toString());
                BigDecimal amt = r.get("amount") == null ? BigDecimal.ZERO
                        : new BigDecimal(r.get("amount").toString());
                String label = r.get("label") == null ? "" : r.get("label").toString();
                out.add(new ScheduleItem(due, amt, label));
            }
            return out;
        } catch (Exception e) {
            log.warn("Bad schedule JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private String serialiseSchedule(List<ScheduleItem> schedule) {
        try {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (ScheduleItem it : schedule) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("dueDate", it.dueDate() == null ? null : it.dueDate().toString());
                r.put("amount", it.amount() == null ? "0" : it.amount().toPlainString());
                r.put("label", it.label() == null ? "" : it.label());
                raw.add(r);
            }
            return objectMapper.writeValueAsString(raw);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /** PLAN-2026-00001; the next free number (a deleted row can't cause a clash). */
    private String generatePlanNumber() {
        int year = clock.today().getYear();
        long n = planRepository.count() + 1;
        String number;
        do {
            number = String.format("PLAN-%d-%05d", year, n++);
        } while (planRepository.existsByPlanId(number));
        return number;
    }

    private String generateInvoiceNumber() {
        int year = clock.today().getYear();
        long n = invoiceRepository.count() + 1;
        String number;
        do {
            number = String.format("INV-%d-%05d", year, n++);
        } while (invoiceRepository.existsByInvoiceNumber(number));
        return number;
    }
}
