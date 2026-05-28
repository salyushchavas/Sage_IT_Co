package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A "Contact Sales" thread between a student and a course's instructor
 * (or service trainer). One inquiry maps to one course/service. The
 * conversation messages live in {@link SalesMessage}.
 *
 * Status state machine:
 *   NEW → IN_PROGRESS → QUOTED → (CONVERTED | CLOSED | LOST)
 * NEW is the freshly-created state. The first instructor reply moves
 * it to IN_PROGRESS. A price quote message moves it to QUOTED.
 * Acceptance ends in CONVERTED; explicit close ends in CLOSED/LOST.
 */
@Entity
@Table(
    name = "sales_inquiries",
    indexes = {
        @Index(name = "idx_sales_inquiries_user", columnList = "user_id"),
        @Index(name = "idx_sales_inquiries_course", columnList = "course_id"),
        @Index(name = "idx_sales_inquiries_status", columnList = "status")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesInquiry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "NEW";

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(name = "budget_range", length = 50)
    private String budgetRange;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
