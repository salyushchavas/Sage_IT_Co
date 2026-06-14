package com.spire.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Build V — an immutable, numbered snapshot of an APPROVED consultant-version
 * agreement (consultant signatures + Certificate of Completion). One row is
 * created each time the ERM approves the consultant version: V1, V2, … Prior
 * rows are NEVER overwritten or deleted (immutability). The PDF bytes live in
 * S3 under {@code s3Key} — already a unique, timestamped key
 * ({@code agreements/{ermId}/phase-{n}/consultant-{ts}.pdf}), so each version
 * points at its own frozen object. The ERM lists these in the consultant view
 * and selects which version to send for approval.
 *
 * <p>Additive: a brand-new table created by Hibernate ddl-auto=update; it
 * touches no existing schema.
 */
@Entity
@Table(name = "consultant_agreement_version", indexes = {
        @Index(name = "idx_cav_application", columnList = "application_id"),
        @Index(name = "idx_cav_app_version",
                columnList = "application_id,version_number", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsultantAgreementVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@code consultant_applications.id} (the numeric internal id). */
    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    /** 1-based, monotonically increasing per application (V1, V2, …). */
    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    /**
     * Immutable S3 key of the consultant-version PDF snapshot at approval.
     * {@code @JsonIgnore} — the internal storage path is never serialized to
     * the client; previews stream the bytes via authenticated endpoints.
     */
    @JsonIgnore
    @Column(name = "s3_key", length = 512, nullable = false)
    private String s3Key;

    /** SHA-256 of the snapshot bytes (mirrors ConsultantApplication.documentHash at capture). */
    @Column(name = "document_hash", length = 128)
    private String documentHash;

    /** The agreement phase (1/2) at the moment of this approval. */
    @Column(name = "phase")
    private Integer phase;

    @CreationTimestamp
    @Column(name = "approved_at", updatable = false)
    private LocalDateTime approvedAt;
}
