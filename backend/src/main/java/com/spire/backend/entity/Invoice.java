package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Invoice issued against a payment plan installment. One row per
 * billable line. {@code invoiceNumber} is the participant-visible
 * identifier ("INV-2026-00042"); the autoincrement {@code id} is for
 * internal joins.
 */
@Entity
@Table(name = "invoices", indexes = {
        @Index(name = "idx_invoice_user_id", columnList = "user_id"),
        @Index(name = "idx_invoice_status", columnList = "status"),
        @Index(name = "idx_invoice_payment_plan", columnList = "payment_plan_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number", unique = true, length = 30)
    private String invoiceNumber;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "payment_plan_id")
    private Long paymentPlanId;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Column(name = "balance", precision = 12, scale = 2)
    private BigDecimal balance;

    /** UNPAID, PARTIAL, PAID, VOID, OVERDUE. */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "UNPAID";
}
