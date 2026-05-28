package com.spire.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.entity.Invoice;
import com.spire.backend.entity.PaymentLedger;
import com.spire.backend.entity.PaymentPlan;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 7 — payment plans + invoices + ledger.
 *
 * Finance creates the plan; the participant only reviews + accepts.
 * Acceptance flips the workflow from PHASE_1_COMPLETED →
 * PAYMENT_PLAN_ACCEPTED. Generating the first invoice advances the
 * status to INVOICING_ACTIVE. Each accepted payment advances to
 * PAYMENTS_TRACKED.
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

    private final PaymentPlanRepository planRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentLedgerRepository ledgerRepository;
    private final UserRepository userRepository;
    private final WorkflowService workflowService;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── Finance creates plan ───────────────────────────────────────

    public record ScheduleItem(LocalDate dueDate, BigDecimal amount, String label) {}

    @Transactional
    public PaymentPlan createPlan(Long financeUserId, Long participantId,
                                  BigDecimal totalAmount, Integer installments,
                                  List<ScheduleItem> schedule) {
        User participant = userRepository.findById(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", participantId));
        if (!workflowService.isStatusAtLeast(participant,
                WorkflowService.Status.PHASE_1_COMPLETED)) {
            throw new IllegalStateException(
                    "Participant must complete Phase 1 before a payment plan can be created.");
        }
        if (totalAmount == null || totalAmount.signum() <= 0) {
            throw new IllegalArgumentException("Total amount must be positive.");
        }
        if (schedule == null || schedule.isEmpty()) {
            throw new IllegalArgumentException("Schedule must include at least one installment.");
        }

        String planNumber = generatePlanNumber();
        PaymentPlan plan = PaymentPlan.builder()
                .userId(participantId)
                .planId(planNumber)
                .totalAmount(totalAmount)
                .installments(installments == null ? schedule.size() : installments)
                .schedule(serialiseSchedule(schedule))
                .status("PENDING")
                .build();
        PaymentPlan saved = planRepository.save(plan);

        recordService.logAction(participantId, RecordService.Category.PAYMENT,
                "Payment plan created",
                "Plan " + planNumber + " — " + totalAmount,
                Map.of("planId", saved.getId(), "planNumber", planNumber,
                        "totalAmount", totalAmount,
                        "installments", saved.getInstallments(),
                        "createdByUserId", financeUserId));
        log.info("Payment plan {} created for user {} by finance {}",
                planNumber, participantId, financeUserId);
        return saved;
    }

    @Transactional
    public PaymentPlan updatePlan(Long financeUserId, Long planId,
                                  BigDecimal totalAmount, Integer installments,
                                  List<ScheduleItem> schedule) {
        PaymentPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("PaymentPlan", "id", planId));
        if (plan.getAcceptedAt() != null) {
            throw new IllegalStateException(
                    "Plan already accepted — edits not permitted. Issue an adjustment instead.");
        }
        if (totalAmount != null && totalAmount.signum() > 0) {
            plan.setTotalAmount(totalAmount);
        }
        if (installments != null && installments > 0) {
            plan.setInstallments(installments);
        }
        if (schedule != null && !schedule.isEmpty()) {
            plan.setSchedule(serialiseSchedule(schedule));
        }
        PaymentPlan saved = planRepository.save(plan);
        recordService.logAction(plan.getUserId(), RecordService.Category.PAYMENT,
                "Payment plan updated",
                "Plan " + plan.getPlanId() + " edited",
                Map.of("planId", saved.getId(), "editorUserId", financeUserId));
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
            throw new UnauthorizedException(
                    "Phase 1 must be completed before accepting a payment plan.");
        }

        PaymentPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("PaymentPlan", "id", planId));
        if (!userId.equals(plan.getUserId())) {
            throw new UnauthorizedException("Not your plan.");
        }
        if (plan.getAcceptedAt() != null) {
            return plan;
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

        ScheduleItem next = items.get(existing.size());
        String number = generateInvoiceNumber();
        Invoice inv = Invoice.builder()
                .invoiceNumber(number)
                .userId(plan.getUserId())
                .paymentPlanId(planId)
                .amount(next.amount())
                .dueDate(next.dueDate())
                .issueDate(today == null ? LocalDate.now() : today)
                .balance(next.amount())
                .status("UNPAID")
                .build();
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
                number + " — " + saved.getAmount(),
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
            if (plan.getAcceptedAt() == null) continue;
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

    // ─── Payment receipt ────────────────────────────────────────────

    @Transactional
    public PaymentLedger recordPayment(Long financeUserId, Long invoiceId,
                                       BigDecimal amount, LocalDate receiptDate,
                                       String method, String notes) {
        Invoice inv = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", "id", invoiceId));
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        BigDecimal newBalance = (inv.getBalance() == null
                ? inv.getAmount() : inv.getBalance()).subtract(amount);
        inv.setBalance(newBalance);
        if (newBalance.signum() <= 0) {
            inv.setStatus("PAID");
            inv.setPaidDate(receiptDate == null ? LocalDate.now() : receiptDate);
        } else {
            inv.setStatus("PARTIAL");
        }
        invoiceRepository.save(inv);

        PaymentLedger row = PaymentLedger.builder()
                .invoiceId(invoiceId)
                .userId(inv.getUserId())
                .amountReceived(amount)
                .receiptDate(receiptDate == null ? LocalDate.now() : receiptDate)
                .method(method == null || method.isBlank() ? "CHEQUE" : method.toUpperCase())
                .balance(newBalance)
                .notes(notes)
                .financeReviewer(financeUserId == null ? "" : financeUserId.toString())
                .build();
        PaymentLedger saved = ledgerRepository.save(row);

        User user = userRepository.findById(inv.getUserId()).orElse(null);
        if (user != null && !workflowService.isStatusAtLeast(user,
                WorkflowService.Status.PAYMENTS_TRACKED)) {
            workflowService.transition(user,
                    WorkflowService.Status.PAYMENTS_TRACKED,
                    "first_payment_received");
        }
        recordService.logAction(inv.getUserId(), RecordService.Category.PAYMENT,
                "Payment received",
                inv.getInvoiceNumber() + " — " + amount,
                Map.of("invoiceId", invoiceId, "ledgerId", saved.getId(),
                        "amount", amount, "method", saved.getMethod(),
                        "financeUserId", financeUserId == null ? 0 : financeUserId));
        if (user != null) {
            try { emailTemplateService.sendPaymentReceivedEmail(user, inv, saved); }
            catch (Exception ignored) {}
        }
        return saved;
    }

    // ─── Overdue marker (idempotent) ────────────────────────────────

    @Transactional
    public int markOverdueInvoices(LocalDate today) {
        int marked = 0;
        List<Invoice> all = invoiceRepository.findByStatusOrderByDueDateAsc("UNPAID");
        for (Invoice inv : all) {
            if (inv.getDueDate() != null && inv.getDueDate().isBefore(today)) {
                inv.setStatus("OVERDUE");
                invoiceRepository.save(inv);
                marked++;
                User user = userRepository.findById(inv.getUserId()).orElse(null);
                if (user != null) {
                    try { emailTemplateService.sendInvoiceOverdueEmail(user, inv); }
                    catch (Exception ignored) {}
                }
            }
        }
        return marked;
    }

    // ─── Read-side helpers ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> participantSummary(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<PaymentPlan> plan = planRepository.findLatestByUserId(userId);
        List<Invoice> invoices = invoiceRepository.findByUserIdOrderByIssueDateDesc(userId);
        List<PaymentLedger> ledger = ledgerRepository.findByUserIdOrderByCreatedAtDesc(userId);

        BigDecimal totalDue = plan.map(PaymentPlan::getTotalAmount).orElse(BigDecimal.ZERO);
        BigDecimal totalPaid = ledger.stream()
                .map(l -> l.getAmountReceived() == null ? BigDecimal.ZERO : l.getAmountReceived())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal balance = totalDue.subtract(totalPaid);
        BigDecimal overdue = invoices.stream()
                .filter(i -> "OVERDUE".equals(i.getStatus()))
                .map(i -> i.getBalance() == null ? BigDecimal.ZERO : i.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Invoice nextDue = invoices.stream()
                .filter(i -> "UNPAID".equals(i.getStatus()) || "PARTIAL".equals(i.getStatus()))
                .min((a, b) -> {
                    if (a.getDueDate() == null) return 1;
                    if (b.getDueDate() == null) return -1;
                    return a.getDueDate().compareTo(b.getDueDate());
                })
                .orElse(null);

        out.put("totalDue", totalDue);
        out.put("totalPaid", totalPaid);
        out.put("balance", balance);
        out.put("overdue", overdue);
        if (nextDue != null) {
            out.put("nextDueAmount", nextDue.getAmount());
            out.put("nextDueDate", nextDue.getDueDate());
            out.put("nextDueInvoice", nextDue.getInvoiceNumber());
        }
        return out;
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

    private String generatePlanNumber() {
        int year = java.time.Year.now().getValue();
        long count = planRepository.count() + 1;
        return String.format("PLAN-%d-%05d", year, count);
    }

    private String generateInvoiceNumber() {
        int year = java.time.Year.now().getValue();
        long count = invoiceRepository.count() + 1;
        return String.format("INV-%d-%05d", year, count);
    }
}
