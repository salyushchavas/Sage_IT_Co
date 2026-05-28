package com.spire.backend.service;

import com.spire.backend.entity.Enrollment;
import com.spire.backend.entity.Payment;
import com.spire.backend.repository.EnrollmentRepository;
import com.spire.backend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Powers the admin Revenue tab.
 *
 * The Payment entity in this codebase doesn't FK to a course, so the
 * "top courses by revenue" breakdown is derived from
 * Enrollment × Course.price. That's an approximation (no refunds /
 * discounts visible) but keeps the feature shippable without a
 * schema migration. A future enhancement would link payments to
 * courses directly.
 */
@Service
@RequiredArgsConstructor
public class AdminRevenueService {

    private final PaymentRepository paymentRepository;
    private final EnrollmentRepository enrollmentRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> getSummary() {
        List<Payment> completed = paymentRepository.findAll().stream()
                .filter(p -> p.getStatus() == Payment.Status.COMPLETED)
                .toList();

        BigDecimal total = completed.stream()
                .map(Payment::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate today = LocalDate.now();
        LocalDate startOfMonth = today.withDayOfMonth(1);
        LocalDate startOfLastMonth = startOfMonth.minusMonths(1);

        BigDecimal thisMonth = completed.stream()
                .filter(p -> p.getCreatedAt() != null
                        && !p.getCreatedAt().toLocalDate().isBefore(startOfMonth))
                .map(Payment::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal lastMonth = completed.stream()
                .filter(p -> p.getCreatedAt() != null
                        && !p.getCreatedAt().toLocalDate().isBefore(startOfLastMonth)
                        && p.getCreatedAt().toLocalDate().isBefore(startOfMonth))
                .map(Payment::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int count = completed.size();
        BigDecimal avg = count > 0
                ? total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRevenue", total);
        summary.put("revenueThisMonth", thisMonth);
        summary.put("revenueLastMonth", lastMonth);
        summary.put("totalTransactions", count);
        summary.put("avgOrderValue", avg);
        summary.put("topCoursesByRevenue", buildTopCoursesByRevenue());
        return summary;
    }

    private List<Map<String, Object>> buildTopCoursesByRevenue() {
        Map<Long, BigDecimal> revenueByCourse = new HashMap<>();
        Map<Long, Integer> enrollmentsByCourse = new HashMap<>();
        Map<Long, String> titles = new HashMap<>();
        Map<Long, String> types = new HashMap<>();

        for (Enrollment e : enrollmentRepository.findAll()) {
            if (e.getCourse() == null) continue;
            Long courseId = e.getCourse().getId();
            BigDecimal price = e.getCourse().getPrice() != null
                    ? e.getCourse().getPrice() : BigDecimal.ZERO;
            revenueByCourse.merge(courseId, price, BigDecimal::add);
            enrollmentsByCourse.merge(courseId, 1, Integer::sum);
            titles.put(courseId, e.getCourse().getTitle());
            types.put(courseId, e.getCourse().getType());
        }

        return revenueByCourse.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                .limit(10)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("courseId", entry.getKey());
                    row.put("courseTitle", titles.get(entry.getKey()));
                    row.put("type", types.get(entry.getKey()));
                    row.put("enrollments", enrollmentsByCourse.get(entry.getKey()));
                    row.put("revenue", entry.getValue());
                    return row;
                })
                .toList();
    }

    /**
     * Flat list of all payments with student name resolved.
     * Optional date / status filters.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTransactions(LocalDate from, LocalDate to, String status) {
        List<Payment> rows = paymentRepository.findAllByOrderByCreatedAtDesc();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Payment p : rows) {
            if (from != null && (p.getCreatedAt() == null
                    || p.getCreatedAt().toLocalDate().isBefore(from))) continue;
            if (to != null && (p.getCreatedAt() == null
                    || p.getCreatedAt().toLocalDate().isAfter(to))) continue;
            if (status != null && !status.isBlank()
                    && (p.getStatus() == null || !status.equalsIgnoreCase(p.getStatus().name()))) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("studentName", p.getUser() != null ? p.getUser().getFullName() : null);
            row.put("studentEmail", p.getUser() != null ? p.getUser().getEmail() : null);
            row.put("amount", p.getAmount());
            row.put("currency", "INR");
            row.put("status", p.getStatus() != null ? p.getStatus().name() : null);
            row.put("razorpayPaymentId", p.getRazorpayPaymentId());
            row.put("razorpayOrderId", p.getRazorpayOrderId());
            row.put("createdAt", p.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    /**
     * Convenience for CSV export — returns same shape as getTransactions
     * but always unfiltered.
     */
    public List<Map<String, Object>> getAllTransactionsForExport() {
        return getTransactions(null, null, null);
    }

    /** Helper for CSV streaming. */
    public List<Payment> getAllPaymentsRaw() {
        return paymentRepository.findAllByOrderByCreatedAtDesc();
    }

    /** Convenience used by Overview-style aggregates if needed elsewhere. */
    public BigDecimal totalLifetimeRevenue() {
        return paymentRepository.findAll().stream()
                .filter(p -> p.getStatus() == Payment.Status.COMPLETED)
                .map(Payment::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Reserved for future per-course revenue (when payments link to courses). */
    @SuppressWarnings("unused")
    private LocalDateTime startOfDay(LocalDate d) {
        return d.atStartOfDay();
    }
}
