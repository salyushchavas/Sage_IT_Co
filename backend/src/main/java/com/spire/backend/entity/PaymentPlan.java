package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Long-lived participant payment plan. {@code schedule} stores the
 * installment timeline as JSON (array of
 * {@code [{dueDate, amount, label}]}) so the form of the plan can
 * evolve without column churn. {@code planId} is the human-readable
 * identifier surfaced on invoices ("PLAN-2026-0042").
 */
@Entity
@Table(name = "payment_plans", indexes = {
        @Index(name = "idx_payplan_user_id", columnList = "user_id"),
        @Index(name = "idx_payplan_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plan_id", unique = true, length = 30)
    private String planId;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "installments")
    private Integer installments;

    @Column(name = "schedule", columnDefinition = "TEXT")
    private String schedule;

    @Column(name = "acceptance_text_version", length = 20)
    private String acceptanceTextVersion;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /** PENDING, ACTIVE, COMPLETED, DEFAULTED, CANCELLED. */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "PENDING";
}
