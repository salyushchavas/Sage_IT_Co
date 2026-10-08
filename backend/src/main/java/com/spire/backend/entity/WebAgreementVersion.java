package com.spire.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * An immutable, numbered snapshot of a verified {@link WebAgreement}
 * (participant signatures + Certificate of Completion), the website copy
 * of the console's {@link ConsultantAgreementVersion}. One row is written
 * each time the ERM verifies: V1, V2, … Rows are never updated or
 * deleted; each points at its own stored PDF. The ERM picks which version
 * the approvers review.
 */
@Entity
@Table(name = "web_agreement_versions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_web_agreement_version_number",
                columnNames = {"agreement_id", "version_number"}),
        indexes = {
                @Index(name = "idx_web_agreement_version_agreement", columnList = "agreement_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebAgreementVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@link WebAgreement#getId()} (numeric, not the UUID). */
    @Column(name = "agreement_id", nullable = false)
    private Long agreementId;

    /** 1-based, increasing per agreement (V1, V2, …). */
    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    /**
     * The value {@code DocumentStorageService} returned for this snapshot's
     * PDF. Never sent to a browser; the bytes stream through authenticated
     * endpoints.
     */
    @JsonIgnore
    @Column(name = "s3_key", nullable = false, columnDefinition = "TEXT")
    private String s3Key;

    /** SHA-256 (hex) of the stored bytes (same value as the agreement's documentHash at capture). */
    @Column(name = "document_hash", length = 128)
    private String documentHash;

    /** The agreement's phase (1/2) when this version was made. */
    @Column(name = "phase")
    private Integer phase;

    @CreationTimestamp
    @Column(name = "approved_at", updatable = false)
    private LocalDateTime approvedAt;
}
