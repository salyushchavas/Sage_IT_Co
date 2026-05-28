package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Marks completion of a discrete phase (PHASE_1, PHASE_2, …) in the
 * participant lifecycle. {@code acknowledgmentVersion} pins the
 * version of the phase-completion text the participant accepted so
 * we can re-display the exact wording years later if needed.
 *
 * The {@code ermApproved} flag is the gate for activating the next
 * phase / payment plan; un-approved phases stay "self-attested" only.
 */
@Entity
@Table(name = "phase_completions", indexes = {
        @Index(name = "idx_phase_user_id", columnList = "user_id"),
        @Index(name = "idx_phase_phase", columnList = "phase")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhaseCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** PHASE_1, PHASE_2, … */
    @Column(name = "phase", nullable = false, length = 20)
    private String phase;

    @Column(name = "acknowledgment_version", length = 20)
    private String acknowledgmentVersion;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "erm_approved")
    @Builder.Default
    private Boolean ermApproved = false;

    @Column(name = "erm_approved_date")
    private LocalDateTime ermApprovedDate;

    @Column(name = "erm_notes", columnDefinition = "TEXT")
    private String ermNotes;
}
