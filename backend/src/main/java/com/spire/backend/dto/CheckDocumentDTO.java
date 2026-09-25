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
    /** Finance's reason, when it rejected the copy. */
    private String reviewNotes;
    private LocalDateTime reviewedAt;
    private Long replacesCheckId;

    /**
     * Checklist 2.4: the check number is masked (••••1234) everywhere
     * except Finance's own audited view.
     */
    public static String maskCheckNumber(String number) {
        if (number == null) return null;
        String digits = number.replaceAll("\\D", "");
        if (digits.isEmpty()) return "••••";
        return digits.length() > 4 ? "••••" + digits.substring(digits.length() - 4)
                : "••" + digits.substring(Math.max(0, digits.length() - 2));
    }

    public static CheckDocumentDTO from(CheckDocument d) {
        return CheckDocumentDTO.builder()
                .id(d.getId())
                .checkNumber(maskCheckNumber(d.getCheckNumber()))
                .amount(d.getAmount())
                .checkDate(d.getCheckDate())
                .notes(d.getNotes())
                .reviewStatus(d.getReviewStatus())
                .uploadedAt(d.getUploadedAt())
                .reviewNotes(d.getReviewNotes())
                .reviewedAt(d.getReviewedAt())
                .replacesCheckId(d.getReplacesCheckId())
                .build();
    }
}
