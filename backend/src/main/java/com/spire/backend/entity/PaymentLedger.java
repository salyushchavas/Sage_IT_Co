package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Append-only ledger of payments received and adjustments applied
 * against invoices. Finance is the canonical writer; never mutated
 * once written so reconstruction of the AR position at any point in
 * time is purely a sum of rows up to that timestamp.
 */
@Entity
@Table(name = "payment_ledger", indexes = {
        @Index(name = "idx_ledger_user_id", columnList = "user_id"),
        @Index(name = "idx_ledger_invoice_id", columnList = "invoice_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "amount_received", precision = 12, scale = 2)
    private BigDecimal amountReceived;

    @Column(name = "receipt_date")
    private LocalDate receiptDate;

    /** CHEQUE, BANK_TRANSFER, CARD, CASH, ADJUSTMENT. */
    @Column(name = "method", length = 50)
    private String method;

    @Column(name = "adjustment", precision = 12, scale = 2)
    private BigDecimal adjustment;

    @Column(name = "balance", precision = 12, scale = 2)
    private BigDecimal balance;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "finance_reviewer", length = 255)
    private String financeReviewer;

    /**
     * Checklist 5.2: PAYMENT (money received), FAILED (an attempt that
     * didn't go through; the balance is unchanged), WAIVER (part of the
     * balance written off) or REVERSAL (an earlier payment undone, e.g. a
     * bounced check). Rows from before this column are payments.
     */
    @Column(name = "entry_type", length = 20)
    private String entryType;

    /** For a REVERSAL: the payment it undoes. */
    @Column(name = "reverses_ledger_id")
    private Long reversesLedgerId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
