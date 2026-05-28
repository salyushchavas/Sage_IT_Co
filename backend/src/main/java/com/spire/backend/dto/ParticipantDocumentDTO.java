package com.spire.backend.dto;

import com.spire.backend.entity.ParticipantDocument;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * View of {@link ParticipantDocument} sent to the frontend. Omits
 * the storage-internal path (Cloudinary public id / disk filename)
 * so a leaked response body can't be used to bypass auth — file
 * fetches always go through the auth'd
 * {@code /api/participants/documents/{id}/view} endpoint.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParticipantDocumentDTO {

    private Long id;
    private String documentType;
    private String fileName;
    private Long fileSize;
    private String reviewStatus;
    private String reviewerNotes;
    private LocalDateTime uploadedAt;
    private LocalDateTime reviewedAt;
    private Boolean notApplicable;

    public static ParticipantDocumentDTO from(ParticipantDocument d) {
        return ParticipantDocumentDTO.builder()
                .id(d.getId())
                .documentType(d.getDocumentType())
                .fileName(d.getFileName())
                .fileSize(d.getFileSize())
                .reviewStatus(d.getReviewStatus())
                .reviewerNotes(d.getReviewerNotes())
                .uploadedAt(d.getUploadedAt())
                .reviewedAt(d.getReviewedAt())
                .notApplicable(Boolean.TRUE.equals(d.getNotApplicable()))
                .build();
    }
}
