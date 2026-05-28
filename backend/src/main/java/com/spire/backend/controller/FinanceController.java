package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
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

    private final CheckDocumentRepository checkRepository;
    private final UserRepository userRepository;
    private final RecordService recordService;
    private final PaymentService paymentService;
    private final CheckTrackingService checkTrackingService;
    private final PaymentPlanRepository planRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentLedgerRepository ledgerRepository;

    @GetMapping("/checks")
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
                    r.put("checkNumber", c.getCheckNumber());
                    r.put("amount", c.getAmount());
                    r.put("checkDate", c.getCheckDate());
                    r.put("notes", c.getNotes());
                    r.put("reviewStatus", c.getReviewStatus());
                    r.put("maskingStatus", c.getMaskingStatus());
                    r.put("fileUrl", c.getFileUrl());
                    r.put("uploadedAt", c.getUploadedAt());
                    return r;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    @PutMapping("/checks/{checkId}/review")
    public ResponseEntity<ApiResponse<CheckDocument>> reviewCheck(
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
        check.setReviewStatus(status);
        CheckDocument saved = checkRepository.save(check);

        Object notesRaw = body.get("notes");
        String notes = notesRaw == null ? "" : notesRaw.toString();
        recordService.logAction(check.getUserId(), RecordService.Category.PAYMENT,
                "APPROVED".equals(status) ? "Check approved by finance" : "Check rejected by finance",
                notes,
                Map.of("checkId", checkId, "financeReviewerId", me));
        return ResponseEntity.ok(ApiResponse.success(
                "Check " + status.toLowerCase(), saved));
    }

    // ─── Phase 7: payment plans ─────────────────────────────────────

    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPlans() {
        List<Map<String, Object>> rows = planRepository.findAll().stream()
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
                    return r;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    @PostMapping("/plans")
    public ResponseEntity<ApiResponse<PaymentPlan>> createPlan(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        Long participantId = numberLong(body.get("participantId"));
        java.math.BigDecimal totalAmount = bigDecimal(body.get("totalAmount"));
        Integer installments = body.get("installments") == null ? null
                : Integer.parseInt(body.get("installments").toString());
        List<PaymentService.ScheduleItem> schedule = parseSchedule(body.get("schedule"));
        PaymentPlan saved = paymentService.createPlan(me, participantId, totalAmount,
                installments, schedule);
        return ResponseEntity.ok(ApiResponse.success("Payment plan created", saved));
    }

    @PutMapping("/plans/{planId}")
    public ResponseEntity<ApiResponse<PaymentPlan>> updatePlan(
            @PathVariable Long planId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        java.math.BigDecimal totalAmount = bigDecimal(body.get("totalAmount"));
        Integer installments = body.get("installments") == null ? null
                : Integer.parseInt(body.get("installments").toString());
        List<PaymentService.ScheduleItem> schedule = parseSchedule(body.get("schedule"));
        return ResponseEntity.ok(ApiResponse.success(
                "Plan updated",
                paymentService.updatePlan(me, planId, totalAmount, installments, schedule)));
    }

    // ─── Invoices ───────────────────────────────────────────────────

    @GetMapping("/invoices")
    public ResponseEntity<ApiResponse<List<Invoice>>> listInvoices(
            @RequestParam(value = "status", required = false) String status) {
        List<Invoice> all = invoiceRepository.findAll().stream()
                .filter(i -> status == null || status.isBlank()
                        || status.equalsIgnoreCase(i.getStatus()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(all));
    }

    @PostMapping("/invoices/generate")
    public ResponseEntity<ApiResponse<Invoice>> generateInvoice(
            @RequestBody Map<String, Object> body) {
        Long planId = numberLong(body.get("paymentPlanId"));
        Invoice inv = paymentService.generateNextInvoice(planId, java.time.LocalDate.now())
                .orElseThrow(() -> new IllegalStateException("No more installments to invoice."));
        return ResponseEntity.ok(ApiResponse.success("Invoice generated", inv));
    }

    @PostMapping("/invoices/bulk-generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkGenerate() {
        List<Invoice> issued = paymentService.generateAllDue(java.time.LocalDate.now());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "issued", issued.size(),
                "invoices", issued.stream().map(Invoice::getInvoiceNumber).toList()
        )));
    }

    @PostMapping("/invoices/mark-overdue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markOverdue() {
        int marked = paymentService.markOverdueInvoices(java.time.LocalDate.now());
        return ResponseEntity.ok(ApiResponse.success(Map.of("marked", marked)));
    }

    // ─── Payments (ledger) ─────────────────────────────────────────

    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<List<PaymentLedger>>> ledger() {
        return ResponseEntity.ok(ApiResponse.success(ledgerRepository.findAll()));
    }

    @PutMapping("/payments/receive")
    public ResponseEntity<ApiResponse<PaymentLedger>> receivePayment(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long me = Long.parseLong(auth.getPrincipal().toString());
        Long invoiceId = numberLong(body.get("invoiceId"));
        java.math.BigDecimal amount = bigDecimal(body.get("amountReceived"));
        java.time.LocalDate receiptDate = body.get("receiptDate") == null ? null
                : java.time.LocalDate.parse(body.get("receiptDate").toString());
        String method = body.get("method") == null ? "CHEQUE" : body.get("method").toString();
        String notes = body.get("notes") == null ? "" : body.get("notes").toString();
        PaymentLedger saved = paymentService.recordPayment(me, invoiceId, amount,
                receiptDate, method, notes);
        return ResponseEntity.ok(ApiResponse.success("Payment recorded", saved));
    }

    // ─── Check tracking (finance review) ───────────────────────────

    @GetMapping("/check-tracking")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAllTrackings(
            @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success(
                checkTrackingService.financeAllTrackings(status)));
    }

    @PutMapping("/check-tracking/{trackingId}/update")
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
        java.math.BigDecimal collected = ledgerRepository.findAll().stream()
                .map(l -> l.getAmountReceived() == null ? java.math.BigDecimal.ZERO
                        : l.getAmountReceived())
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
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
        if (o == null) return null;
        if (o instanceof Number n) return new java.math.BigDecimal(n.toString());
        return new java.math.BigDecimal(o.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<PaymentService.ScheduleItem> parseSchedule(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) return List.of();
        List<PaymentService.ScheduleItem> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> row = (Map<String, Object>) m;
            java.time.LocalDate due = row.get("dueDate") == null ? null
                    : java.time.LocalDate.parse(row.get("dueDate").toString());
            java.math.BigDecimal amt = bigDecimal(row.get("amount"));
            String label = row.get("label") == null ? "" : row.get("label").toString();
            out.add(new PaymentService.ScheduleItem(due, amt, label));
        }
        return out;
    }
}
