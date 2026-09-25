package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.CheckDocumentDTO;
import com.spire.backend.entity.CheckDocument;
import com.spire.backend.entity.Invoice;
import com.spire.backend.entity.PaymentLedger;
import com.spire.backend.entity.PaymentPlan;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.CheckDocumentRepository;
import com.spire.backend.repository.InvoiceRepository;
import com.spire.backend.repository.PaymentLedgerRepository;
import com.spire.backend.repository.PaymentPlanRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.service.CheckTrackingService;
import com.spire.backend.service.PaymentService;
import com.spire.backend.service.RecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 5B Finance dashboard endpoints. Gated to the FINANCE role
 * (system / operations admins can also read check images through the
 * existing admin paths, but the Finance-specific review workflow
 * lives here).
 *
 * Per PRD §13, this is the ONLY role with un-redacted access to
 * check images and check-tracking data. Coaches and ERMs never reach
 * these endpoints.
 */
@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('FINANCE','SYSTEM_ADMIN','OPERATIONS_ADMIN')")
public class FinanceController {

    /**
     * Operations admins may look (to follow up with participants), but only
     * Finance and the System Admin change money: plans, invoices, payments,
     * waivers, reversals and check tracking (roadmap §9.1 / §13).
     */
    static final String MONEY_WRITERS = "hasAnyRole('FINANCE','SYSTEM_ADMIN')";


    private final CheckDocumentRepository checkRepository;
    private final UserRepository userRepository;
    private final RecordService recordService;
    private final PaymentService paymentService;
    private final CheckTrackingService checkTrackingService;
    private final PaymentPlanRepository planRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentLedgerRepository ledgerRepository;
    private final com.spire.backend.service.EmailTemplateService emailTemplateService;
    private final com.spire.backend.service.DocumentStorageService storageService;
    private final com.spire.backend.service.WorkflowService workflowService;
    private final com.spire.backend.service.EmploymentService employmentService;
    private final com.spire.backend.service.InvoicePdfService invoicePdfService;
    private final com.spire.backend.service.BusinessClock clock;

    // ─── Check copies (checklist 2.4) ───────────────────────────────
    // Only Finance (and System Admin, the top role) handles check copies:
    // Operations can open this dashboard's other tabs but not these.

    /**
     * Check copies for review. The check number is masked (••••1234) and
     * the image isn't linked here: Finance opens both through the audited
     * endpoints below.
     */
    @GetMapping("/checks")
    @PreAuthorize("hasAnyRole('FINANCE','SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listChecks(
            @RequestParam(value = "status", required = false) String status) {
        List<Map<String, Object>> rows = checkRepository.findAll().stream()
                .filter(c -> status == null || status.isBlank()
                        || status.equalsIgnoreCase(c.getReviewStatus()))
                .map(c -> {
                    User u = userRepository.findById(c.getUserId()).orElse(null);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", c.getId());
                    r.put("userId", c.getUserId());
                    r.put("participantId", u == null ? null : u.getParticipantId());
                    r.put("participantName", u == null ? null : u.getFullName());
                    r.put("checkNumber", CheckDocumentDTO.maskCheckNumber(c.getCheckNumber()));
                    r.put("amount", c.getAmount());
                    r.put("checkDate", c.getCheckDate());
                    r.put("notes", c.getNotes());
                    r.put("reviewStatus", c.getReviewStatus());
                    r.put("reviewNotes", c.getReviewNotes());
                    r.put("reviewedAt", c.getReviewedAt());
                    r.put("replacesCheckId", c.getReplacesCheckId());
                    r.put("hasFile", c.getFileUrl() != null && !c.getFileUrl().isBlank());
                    r.put("uploadedAt", c.getUploadedAt());
                    return r;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /**
     * Approve a check copy, or reject it with a reason: the participant
     * is emailed the reason and a link to upload a new copy (the image is
     * never attached). The same decision twice sends nothing new.
     */
    @PutMapping("/checks/{checkId}/review")
    @PreAuthorize("hasAnyRole('FINANCE','SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<CheckDocumentDTO>> reviewCheck(
            @PathVariable Long checkId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        CheckDocument check = checkRepository.findById(checkId)
                .orElseThrow(() -> new ResourceNotFoundException("CheckDocument", "id", checkId));
        Object statusRaw = body.get("status");
        String status = statusRaw == null ? "APPROVED" : statusRaw.toString().toUpperCase();
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            throw new IllegalArgumentException("status must be APPROVED or REJECTED");
        }
        Object notesRaw = body.get("notes");
        String notes = notesRaw == null ? "" : notesRaw.toString().trim();
        if ("REJECTED".equals(status) && notes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Give the participant a reason. It's emailed to them with a link to upload a new copy.");
        }
        if (status.equals(check.getReviewStatus()) && notes.equals(check.getReviewNotes() == null ? "" : check.getReviewNotes())) {
            return ResponseEntity.ok(ApiResponse.success("Check " + status.toLowerCase(), CheckDocumentDTO.from(check)));
        }
        check.setReviewStatus(status);
        check.setReviewNotes(notes.isEmpty() ? null : notes);
        check.setReviewedBy(me);
        check.setReviewedAt(java.time.LocalDateTime.now());
        CheckDocument saved = checkRepository.save(check);

        recordService.logAction(check.getUserId(), RecordService.Category.PAYMENT,
                "APPROVED".equals(status) ? "Check approved by finance" : "Check rejected by finance",
                notes,
                Map.of("checkId", checkId, "financeReviewerId", me));
        if ("REJECTED".equals(status)) {
            userRepository.findById(check.getUserId()).ifPresent(u -> emailTemplateService.sendCheckRejectedEmail(
                    u, CheckDocumentDTO.maskCheckNumber(check.getCheckNumber()), notes, checkId));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Check " + status.toLowerCase(), CheckDocumentDTO.from(saved)));
    }

    /**
     * The check image, for Finance only: a short-lived link (Cloudinary) or
     * the file itself. Each view is recorded on the participant's trail.
     */
    @GetMapping("/checks/{checkId}/image")
    @PreAuthorize("hasAnyRole('FINANCE','SYSTEM_ADMIN')")
    public ResponseEntity<?> checkImage(@PathVariable Long checkId, Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        CheckDocument check = checkRepository.findById(checkId)
                .orElseThrow(() -> new ResourceNotFoundException("CheckDocument", "id", checkId));
        String url = check.getFileUrl();
        if (url == null || url.isBlank()) return ResponseEntity.notFound().build();
        recordService.record(check.getUserId(), "CHECK_IMAGE_VIEWED", RecordService.Category.PAYMENT,
                "Check image viewed by finance", "User #" + me + " opened check copy #" + checkId,
                Map.of("checkId", checkId, "viewerId", me));
        return StoredFileResponse.of(storageService, url, "check-copy-" + checkId);
    }

    /** The full check number, for Finance only; each view is recorded. */
    @GetMapping("/checks/{checkId}/number")
    @PreAuthorize("hasAnyRole('FINANCE','SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkNumber(@PathVariable Long checkId, Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        CheckDocument check = checkRepository.findById(checkId)
                .orElseThrow(() -> new ResourceNotFoundException("CheckDocument", "id", checkId));
        recordService.record(check.getUserId(), "CHECK_NUMBER_VIEWED", RecordService.Category.PAYMENT,
                "Check number viewed by finance", "User #" + me + " revealed the number of check copy #" + checkId,
                Map.of("checkId", checkId, "viewerId", me));
        return ResponseEntity.ok(ApiResponse.success(Map.of("checkNumber",
                check.getCheckNumber() == null ? "" : check.getCheckNumber())));
    }

    // ─── Phase 7: payment plans ─────────────────────────────────────

    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPlans() {
        List<Map<String, Object>> rows = planRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(PaymentPlan::getId).reversed())
                .map(p -> {
                    User u = userRepository.findById(p.getUserId()).orElse(null);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", p.getId());
                    r.put("planNumber", p.getPlanId());
                    r.put("userId", p.getUserId());
                    r.put("participantId", u == null ? null : u.getParticipantId());
                    r.put("participantName", u == null ? null : u.getFullName());
                    r.put("totalAmount", p.getTotalAmount());
                    r.put("installments", p.getInstallments());
                    r.put("status", p.getStatus());
                    r.put("acceptedAt", p.getAcceptedAt());
                    r.put("schedule", paymentService.parseSchedule(p.getSchedule()));
                    // Checklist 5.1: a plan can be changed until its first invoice.
                    r.put("invoiceCount", invoiceRepository.findByPaymentPlanId(p.getId()).size());
                    return r;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /**
     * Checklist 5.1: participants Finance can create a plan for — Phase 1
     * completed and no open plan — so Finance picks a name, not a user number.
     */
    @GetMapping("/plan-candidates")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> planCandidates() {
        java.util.Set<Long> withOpenPlan = new java.util.HashSet<>();
        planRepository.findAll().stream()
                .filter(p -> "PENDING".equals(p.getStatus()) || "ACTIVE".equals(p.getStatus()))
                .forEach(p -> withOpenPlan.add(p.getUserId()));
        List<Map<String, Object>> rows = userRepository.findAll().stream()
                .filter(u -> u.getRole() != null && "PARTICIPANT".equalsIgnoreCase(u.getRole().getName()))
                .filter(u -> !Boolean.FALSE.equals(u.getIsActive()))
                .filter(u -> workflowService.isStatusAtLeast(u, com.spire.backend.service.WorkflowService.Status.PHASE_1_COMPLETED))
                .filter(u -> !withOpenPlan.contains(u.getId()))
                .map(u -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("userId", u.getId());
                    r.put("participantId", u.getParticipantId());
                    r.put("fullName", u.getFullName());
                    employmentService.latestVerifiedEmployment(u.getId()).ifPresent(e -> {
                        r.put("employer", e.getEmployerClient());
                        r.put("startDate", e.getStartDate());
                    });
                    return r;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /** Checklist 5.1: the schedule the server would build, without saving anything. */
    @PostMapping("/plans/preview")
    public ResponseEntity<ApiResponse<List<PaymentService.ScheduleItem>>> previewPlan(
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(paymentService.previewSchedule(
                bigDecimal(body.get("totalAmount")), integer(body.get("installments")), date(body.get("firstDueDate")))));
    }

    @PostMapping("/plans")
    @PreAuthorize(MONEY_WRITERS)
    public ResponseEntity<ApiResponse<PaymentPlan>> createPlan(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        Long participantId = numberLong(body.get("participantId"));
        if (participantId == null) throw new IllegalArgumentException("Pick the participant.");
        PaymentPlan saved = paymentService.createPlan(me, participantId, bigDecimal(body.get("totalAmount")),
                integer(body.get("installments")), date(body.get("firstDueDate")), parseSchedule(body.get("schedule")));
        return ResponseEntity.ok(ApiResponse.success("Payment plan created", saved));
    }

    @PutMapping("/plans/{planId}")
    @PreAuthorize(MONEY_WRITERS)
    public ResponseEntity<ApiResponse<PaymentPlan>> updatePlan(
            @PathVariable Long planId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                "Plan updated",
                paymentService.updatePlan(me, planId, bigDecimal(body.get("totalAmount")),
                        integer(body.get("installments")), date(body.get("firstDueDate")),
                        parseSchedule(body.get("schedule")))));
    }

    // ─── Invoices ───────────────────────────────────────────────────

    /** Invoices with the participant's name and ID and the plan number (checklist 5.2). */
    @GetMapping("/invoices")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listInvoices(
            @RequestParam(value = "status", required = false) String status) {
        Map<Long, User> people = new java.util.HashMap<>();
        Map<Long, String> plans = new java.util.HashMap<>();
        List<Map<String, Object>> rows = invoiceRepository.findAll().stream()
                .filter(i -> status == null || status.isBlank()
                        || status.equalsIgnoreCase(i.getStatus()))
                .sorted(java.util.Comparator.comparing(Invoice::getDueDate,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(i -> {
                    User u = people.computeIfAbsent(i.getUserId(), id -> userRepository.findById(id).orElse(null));
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", i.getId());
                    r.put("invoiceNumber", i.getInvoiceNumber());
                    r.put("userId", i.getUserId());
                    r.put("participantId", u == null ? null : u.getParticipantId());
                    r.put("participantName", u == null ? null : u.getFullName());
                    r.put("paymentPlanId", i.getPaymentPlanId());
                    r.put("planNumber", i.getPaymentPlanId() == null ? null : plans.computeIfAbsent(i.getPaymentPlanId(),
                            id -> planRepository.findById(id).map(PaymentPlan::getPlanId).orElse(null)));
                    r.put("amount", i.getAmount());
                    r.put("balance", i.getBalance());
                    r.put("issueDate", i.getIssueDate());
                    r.put("dueDate", i.getDueDate());
                    r.put("paidDate", i.getPaidDate());
                    r.put("status", i.getStatus());
                    return r;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /** Checklist 5.2: the invoice as a PDF. */
    @GetMapping("/invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable Long invoiceId) {
        Invoice inv = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", "id", invoiceId));
        return invoicePdfService.response(inv);
    }

    @PostMapping("/invoices/generate")
    @PreAuthorize(MONEY_WRITERS)
    public ResponseEntity<ApiResponse<Invoice>> generateInvoice(
            @RequestBody Map<String, Object> body) {
        Long planId = numberLong(body.get("paymentPlanId"));
        Invoice inv = paymentService.generateNextInvoice(planId, clock.today())
                .orElseThrow(() -> new IllegalStateException("No more installments to invoice."));
        return ResponseEntity.ok(ApiResponse.success("Invoice generated", inv));
    }

    @PostMapping("/invoices/bulk-generate")
    @PreAuthorize(MONEY_WRITERS)
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkGenerate() {
        List<Invoice> issued = paymentService.generateAllDue(clock.today());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "issued", issued.size(),
                "invoices", issued.stream().map(Invoice::getInvoiceNumber).toList()
        )));
    }

    @PostMapping("/invoices/mark-overdue")
    @PreAuthorize(MONEY_WRITERS)
    public ResponseEntity<ApiResponse<Map<String, Object>>> markOverdue() {
        int marked = paymentService.markOverdueInvoices(clock.today());
        return ResponseEntity.ok(ApiResponse.success(Map.of("marked", marked)));
    }

    // ─── Payments (ledger) ─────────────────────────────────────────

    /** Ledger entries, newest first, with who and which invoice (checklist 5.2). */
    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> ledger() {
        Map<Long, User> people = new java.util.HashMap<>();
        Map<Long, String> invoiceNumbers = new java.util.HashMap<>();
        java.util.Set<Long> reversed = new java.util.HashSet<>();
        List<PaymentLedger> all = ledgerRepository.findAll();
        all.stream().filter(l -> l.getReversesLedgerId() != null).forEach(l -> reversed.add(l.getReversesLedgerId()));
        List<Map<String, Object>> rows = all.stream()
                .sorted(java.util.Comparator.comparing(PaymentLedger::getId).reversed())
                .map(l -> {
                    User u = people.computeIfAbsent(l.getUserId(), id -> userRepository.findById(id).orElse(null));
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", l.getId());
                    r.put("entryType", PaymentService.typeOf(l));
                    r.put("invoiceId", l.getInvoiceId());
                    r.put("invoiceNumber", l.getInvoiceId() == null ? null : invoiceNumbers.computeIfAbsent(l.getInvoiceId(),
                            id -> invoiceRepository.findById(id).map(Invoice::getInvoiceNumber).orElse(null)));
                    r.put("userId", l.getUserId());
                    r.put("participantId", u == null ? null : u.getParticipantId());
                    r.put("participantName", u == null ? null : u.getFullName());
                    r.put("amountReceived", l.getAmountReceived());
                    r.put("receiptDate", l.getReceiptDate());
                    r.put("method", l.getMethod());
                    r.put("balance", l.getBalance());
                    r.put("notes", l.getNotes());
                    r.put("reversesLedgerId", l.getReversesLedgerId());
                    r.put("reversed", reversed.contains(l.getId()));
                    r.put("createdAt", l.getCreatedAt());
                    return r;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /**
     * Records a ledger entry. Body: invoiceId, amountReceived, receiptDate,
     * method, notes, and (checklist 5.2) entryType — PAYMENT (default),
     * FAILED, WAIVER or REVERSAL with reversesLedgerId.
     */
    @PutMapping("/payments/receive")
    @PreAuthorize(MONEY_WRITERS)
    public ResponseEntity<ApiResponse<PaymentLedger>> receivePayment(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        Long invoiceId = numberLong(body.get("invoiceId"));
        if (invoiceId == null) throw new IllegalArgumentException("Pick the invoice.");
        String type = body.get("entryType") == null ? PaymentService.ENTRY_PAYMENT : body.get("entryType").toString();
        String method = body.get("method") == null ? "CHEQUE" : body.get("method").toString();
        String notes = body.get("notes") == null ? "" : body.get("notes").toString();
        PaymentLedger saved = paymentService.recordEntry(me, invoiceId, type, bigDecimal(body.get("amountReceived")),
                date(body.get("receiptDate")), method, notes, numberLong(body.get("reversesLedgerId")));
        return ResponseEntity.ok(ApiResponse.success(
                PaymentService.ENTRY_PAYMENT.equals(PaymentService.typeOf(saved)) ? "Payment recorded" : "Entry recorded",
                saved));
    }

    // ─── Check tracking (finance review) ───────────────────────────

    @GetMapping("/check-tracking")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAllTrackings(
            @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success(
                checkTrackingService.financeAllTrackings(status)));
    }

    /** Checklist 5.3: the full number of a mailed check, for Finance only; each view is recorded. */
    @GetMapping("/check-tracking/{trackingId}/number")
    @PreAuthorize("hasAnyRole('FINANCE','SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> trackingCheckNumber(
            @PathVariable Long trackingId, Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(Map.of("checkNumber",
                checkTrackingService.revealCheckNumber(me, trackingId))));
    }

    @PutMapping("/check-tracking/{trackingId}/update")
    @PreAuthorize(MONEY_WRITERS)
    public ResponseEntity<ApiResponse<com.spire.backend.entity.CheckTracking>> updateTracking(
            @PathVariable Long trackingId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        String status = body.get("status") == null ? "RECEIVED" : body.get("status").toString();
        java.time.LocalDate receivedDate = body.get("receivedDate") == null ? null
                : java.time.LocalDate.parse(body.get("receivedDate").toString());
        return ResponseEntity.ok(ApiResponse.success(
                "Tracking updated",
                checkTrackingService.updateTrackingStatus(me, trackingId, status, receivedDate)));
    }

    // ─── Finance overview ──────────────────────────────────────────

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dashboard() {
        long totalPlans = planRepository.count();
        long activePlans = planRepository.findAll().stream()
                .filter(p -> "ACTIVE".equals(p.getStatus())).count();
        long unpaid = invoiceRepository.findByStatusOrderByDueDateAsc("UNPAID").size();
        long overdue = invoiceRepository.findByStatusOrderByDueDateAsc("OVERDUE").size();
        // Checklist 5.2: payments minus reversals (failed attempts and waivers aren't money in).
        java.math.BigDecimal collected = PaymentService.collected(ledgerRepository.findAll());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "totalPlans", totalPlans,
                "activePlans", activePlans,
                "unpaidInvoices", unpaid,
                "overdueInvoices", overdue,
                "totalCollected", collected
        )));
    }

    @GetMapping("/participants/{userId}/payments")
    public ResponseEntity<ApiResponse<Map<String, Object>>> participantPayments(
            @PathVariable Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        User u = userRepository.findById(userId).orElse(null);
        out.put("participant", u == null ? null : Map.of(
                "id", u.getId(),
                "participantId", u.getParticipantId() == null ? "" : u.getParticipantId(),
                "fullName", u.getFullName() == null ? "" : u.getFullName(),
                "email", u.getEmail() == null ? "" : u.getEmail()
        ));
        var planOpt = paymentService.latestPlanForUser(userId);
        out.put("plan", planOpt.orElse(null));
        out.put("schedule", planOpt.map(p -> paymentService.parseSchedule(p.getSchedule()))
                .orElse(List.of()));
        out.put("invoices", invoiceRepository.findByUserIdOrderByIssueDateDesc(userId));
        out.put("ledger", ledgerRepository.findByUserIdOrderByCreatedAtDesc(userId));
        out.put("summary", paymentService.participantSummary(userId));
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private static Long numberLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private static java.math.BigDecimal bigDecimal(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            if (o instanceof Number n) return new java.math.BigDecimal(n.toString());
            return new java.math.BigDecimal(o.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("That isn't a valid amount: " + o);
        }
    }

    private static Integer integer(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            return o instanceof Number n ? n.intValue() : Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("That isn't a whole number: " + o);
        }
    }

    private static java.time.LocalDate date(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            return java.time.LocalDate.parse(o.toString().trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("That isn't a valid date: " + o);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<PaymentService.ScheduleItem> parseSchedule(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) return List.of();
        List<PaymentService.ScheduleItem> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> row = (Map<String, Object>) m;
            java.time.LocalDate due = date(row.get("dueDate"));
            java.math.BigDecimal amt = bigDecimal(row.get("amount"));
            String label = row.get("label") == null ? "" : row.get("label").toString();
            out.add(new PaymentService.ScheduleItem(due, amt, label));
        }
        return out;
    }
}
