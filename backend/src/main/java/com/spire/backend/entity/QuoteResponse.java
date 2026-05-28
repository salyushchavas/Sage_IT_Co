package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Records the student's response to a quote message. One row per
 * accept/decline action; multiple counter-offers create new
 * QuoteResponse rows linked to new SalesMessage rows.
 */
@Entity
@Table(
    name = "quote_responses",
    indexes = @Index(name = "idx_quote_responses_message", columnList = "message_id")
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuoteResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private SalesMessage message;

    /** ACCEPTED, DECLINED, COUNTER_OFFERED */
    @Column(nullable = false, length = 20)
    private String status;

    @CreationTimestamp
    @Column(name = "responded_at", updatable = false)
    private LocalDateTime respondedAt;
}
