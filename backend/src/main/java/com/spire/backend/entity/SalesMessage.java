package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single message in a sales conversation. Messages with
 * {@code isQuote=true} represent itemized price offers; the line items
 * are stored as a JSON string in {@code quotedItems} (the DB schema
 * task asks for JSON, but we keep it portable as TEXT here so dev MySQL
 * and prod Postgres behave the same — it's serialized JSON either way).
 *
 * Quotes are superseded rather than edited: an inquiry can have
 * multiple quote messages over time, and only the latest open one is
 * the "active" offer. The QuoteResponse linked to a message records
 * acceptance / decline.
 */
@Entity
@Table(
    name = "sales_messages",
    indexes = {
        @Index(name = "idx_sales_messages_inquiry", columnList = "inquiry_id"),
        @Index(name = "idx_sales_messages_sender", columnList = "sender_id")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private SalesInquiry inquiry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;

    @Column(name = "is_quote", nullable = false)
    @Builder.Default
    private Boolean isQuote = false;

    @Column(name = "quoted_price", precision = 10, scale = 2)
    private BigDecimal quotedPrice;

    /** Serialized JSON array of line items. Format matches QuoteItem on the frontend. */
    @Column(name = "quoted_items", columnDefinition = "TEXT")
    private String quotedItems;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
