package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * Tracks a physical cheque shipped from / to a participant against a
 * specific payment plan. Used by Finance to reconcile mailings; the
 * scanned image (if any) lives on {@link CheckDocument} in the
 * restricted vault.
 */
@Entity
@Table(name = "check_tracking", indexes = {
        @Index(name = "idx_check_track_plan", columnList = "payment_plan_id"),
        @Index(name = "idx_check_track_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_plan_id")
    private Long paymentPlanId;

    @Column(name = "check_number", length = 50)
    private String checkNumber;

    @Column(name = "physical_tracking_id", length = 100)
    private String physicalTrackingId;

    @Column(name = "carrier", length = 50)
    private String carrier;

    @Column(name = "mailed_date")
    private LocalDate mailedDate;

    @Column(name = "expected_receipt_date")
    private LocalDate expectedReceiptDate;

    @Column(name = "received_date")
    private LocalDate receivedDate;

    /** PENDING, MAILED, IN_TRANSIT, RECEIVED, RETURNED, LOST. */
    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "PENDING";
}
