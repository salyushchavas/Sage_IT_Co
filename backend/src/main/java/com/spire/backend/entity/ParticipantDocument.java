package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Secure document-vault row. One row per uploaded document
 * (ID proof, employment letter, address proof, …). Reviewed by ERM
 * or Operations Admin via {@link PermissionService}.
 *
 * Named {@code ParticipantDocument} (not {@code Document}) to avoid
 * any clash with OpenPDF's {@code com.lowagie.text.Document} that
 * the agreement-PDF generator imports. The DB table stays
 * {@code documents}.
 */
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_doc_user_id", columnList = "user_id"),
        @Index(name = "idx_doc_review_status", columnList = "review_status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParticipantDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** ID_PROOF, ADDRESS_PROOF, EMPLOYMENT_LETTER, OFFER_LETTER, OTHER. */
    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "storage_path", length = 500)
    private String storagePath;

    /** PENDING, APPROVED, REJECTED, NOT_APPLICABLE. */
    @Column(name = "review_status", length = 30)
    @Builder.Default
    private String reviewStatus = "PENDING";

    /**
     * True when the participant explicitly marked this document type
     * as not applicable (e.g. a domestic candidate selecting
     * "Not applicable" for Work Authorization). The row carries no
     * file_url / file_name in that case; the marker just unblocks
     * the completeness check.
     */
    @Column(name = "not_applicable", nullable = false)
    @Builder.Default
    private Boolean notApplicable = false;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "reviewer_notes", columnDefinition = "TEXT")
    private String reviewerNotes;

    @Column(name = "retention_category", length = 50)
    private String retentionCategory;

    @CreationTimestamp
    @Column(name = "uploaded_at", updatable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;
}
