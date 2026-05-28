package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Restricted-access vault row for an image of a physical cheque.
 * Access is gated to Finance + authorised Operations Admin via
 * {@link PermissionService}. {@code maskingStatus} starts at MASKED
 * — the unmasked image is only revealed when the masking pipeline
 * flips it (or a privileged reviewer requests reveal).
 */
@Entity
@Table(name = "check_documents", indexes = {
        @Index(name = "idx_check_user_id", columnList = "user_id"),
        @Index(name = "idx_check_review_status", columnList = "review_status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    @Column(name = "check_number", length = 50)
    private String checkNumber;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "check_date")
    private LocalDate checkDate;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** PENDING, APPROVED, REJECTED. */
    @Column(name = "review_status", length = 30)
    @Builder.Default
    private String reviewStatus = "PENDING";

    /** MASKED, UNMASKED. Defaults to MASKED so an accidental read returns a redacted image. */
    @Column(name = "masking_status", length = 20)
    @Builder.Default
    private String maskingStatus = "MASKED";

    @CreationTimestamp
    @Column(name = "uploaded_at", updatable = false)
    private LocalDateTime uploadedAt;
}
