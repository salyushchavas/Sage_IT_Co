package com.spire.backend.dto;

import com.spire.backend.entity.CheckDocument;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * View of {@link CheckDocument} returned by the participant's own
 * /api/participants/checks list call. The actual file URL is
 * omitted — file access goes through the Finance-gated view
 * endpoint, not by handing the URL to the participant's browser.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckDocumentDTO {
    private Long id;
    private String checkNumber;
    private BigDecimal amount;
    private LocalDate checkDate;
    private String notes;
    private String reviewStatus;
    private LocalDateTime uploadedAt;

    public static CheckDocumentDTO from(CheckDocument d) {
        return CheckDocumentDTO.builder()
                .id(d.getId())
                .checkNumber(d.getCheckNumber())
                .amount(d.getAmount())
                .checkDate(d.getCheckDate())
                .notes(d.getNotes())
                .reviewStatus(d.getReviewStatus())
                .uploadedAt(d.getUploadedAt())
                .build();
    }
}
