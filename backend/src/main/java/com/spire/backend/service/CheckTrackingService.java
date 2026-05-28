package com.spire.backend.service;

import com.spire.backend.entity.CheckTracking;
import com.spire.backend.entity.PaymentPlan;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.CheckTrackingRepository;
import com.spire.backend.repository.PaymentPlanRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 7 — physical-check tracking.
 *
 * Participants enter outbound shipping details (check number, carrier,
 * tracking ID, mailed date). Finance later marks the rows
 * RECEIVED / EXCEPTION as physical mail arrives. The first tracking
 * submission flips the workflow to CHECK_TRACKING_ADDED.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckTrackingService {

    private final CheckTrackingRepository trackingRepository;
    private final PaymentPlanRepository planRepository;
    private final UserRepository userRepository;
    private final WorkflowService workflowService;
    private final RecordService recordService;

    // ── Participant submission ────────────────────────────────────

    @Transactional
    public CheckTracking submit(Long userId, CheckTracking in) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.PAYMENT_PLAN_ACCEPTED)) {
            throw new UnauthorizedException(
                    "Payment plan must be accepted before tracking can be submitted.");
        }
        PaymentPlan plan = planRepository.findLatestByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PaymentPlan", "userId", userId));

        if (in.getCheckNumber() == null || in.getCheckNumber().isBlank()
                || in.getCarrier() == null || in.getCarrier().isBlank()
                || in.getPhysicalTrackingId() == null || in.getPhysicalTrackingId().isBlank()
                || in.getMailedDate() == null) {
            throw new IllegalArgumentException(
                    "checkNumber, carrier, trackingId, and mailedDate are required.");
        }

        CheckTracking row = CheckTracking.builder()
                .paymentPlanId(plan.getId())
                .checkNumber(in.getCheckNumber().trim())
                .carrier(in.getCarrier().trim())
                .physicalTrackingId(in.getPhysicalTrackingId().trim())
                .mailedDate(in.getMailedDate())
                .expectedReceiptDate(in.getExpectedReceiptDate())
                .status("IN_TRANSIT")
                .build();
        CheckTracking saved = trackingRepository.save(row);

        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.CHECK_TRACKING_ADDED)) {
            workflowService.transition(user,
                    WorkflowService.Status.CHECK_TRACKING_ADDED,
                    "first_check_tracking_added");
        }
        recordService.logAction(userId, RecordService.Category.PAYMENT,
                "Check tracking submitted",
                saved.getCarrier() + " " + saved.getPhysicalTrackingId(),
                Map.of("trackingId", saved.getId(),
                        "checkNumber", saved.getCheckNumber(),
                        "planId", plan.getId()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> trackingsForUser(Long userId) {
        Optional<PaymentPlan> plan = planRepository.findLatestByUserId(userId);
        if (plan.isEmpty()) return List.of();
        return trackingRepository.findByPaymentPlanId(plan.get().getId()).stream()
                .map(t -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", t.getId());
                    r.put("checkNumber", t.getCheckNumber());
                    r.put("carrier", t.getCarrier());
                    r.put("trackingId", t.getPhysicalTrackingId());
                    r.put("mailedDate", t.getMailedDate());
                    r.put("expectedReceiptDate", t.getExpectedReceiptDate());
                    r.put("receivedDate", t.getReceivedDate());
                    r.put("status", t.getStatus());
                    return r;
                })
                .toList();
    }

    // ── Finance review ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> financeAllTrackings(String status) {
        return trackingRepository.findAll().stream()
                .filter(t -> status == null || status.isBlank()
                        || status.equalsIgnoreCase(t.getStatus()))
                .map(t -> {
                    Long uid = planRepository.findById(t.getPaymentPlanId())
                            .map(PaymentPlan::getUserId).orElse(null);
                    User u = uid == null ? null
                            : userRepository.findById(uid).orElse(null);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", t.getId());
                    r.put("paymentPlanId", t.getPaymentPlanId());
                    r.put("userId", uid);
                    r.put("participantId", u == null ? null : u.getParticipantId());
                    r.put("participantName", u == null ? null : u.getFullName());
                    r.put("checkNumber", t.getCheckNumber());
                    r.put("carrier", t.getCarrier());
                    r.put("trackingId", t.getPhysicalTrackingId());
                    r.put("mailedDate", t.getMailedDate());
                    r.put("expectedReceiptDate", t.getExpectedReceiptDate());
                    r.put("receivedDate", t.getReceivedDate());
                    r.put("status", t.getStatus());
                    return r;
                })
                .toList();
    }

    @Transactional
    public CheckTracking updateTrackingStatus(Long financeUserId, Long trackingId,
                                              String status, LocalDate receivedDate) {
        CheckTracking row = trackingRepository.findById(trackingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CheckTracking", "id", trackingId));
        String normalised = status == null ? "" : status.toUpperCase();
        if (!List.of("RECEIVED", "EXCEPTION", "IN_TRANSIT", "RETURNED", "LOST")
                .contains(normalised)) {
            throw new IllegalArgumentException("Invalid tracking status.");
        }
        row.setStatus(normalised);
        if ("RECEIVED".equals(normalised) && receivedDate != null) {
            row.setReceivedDate(receivedDate);
        }
        CheckTracking saved = trackingRepository.save(row);

        Long uid = planRepository.findById(row.getPaymentPlanId())
                .map(PaymentPlan::getUserId).orElse(null);
        if (uid != null) {
            recordService.logAction(uid, RecordService.Category.PAYMENT,
                    "Check tracking updated by finance",
                    normalised,
                    Map.of("trackingId", saved.getId(),
                            "status", normalised,
                            "financeUserId", financeUserId));
        }
        return saved;
    }
}
