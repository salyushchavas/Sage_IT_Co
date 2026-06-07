package com.spire.backend.dto;

import com.spire.backend.entity.ConsultantApplication;

import java.time.LocalDateTime;

/**
 * Slim projection of {@link ConsultantApplication} for the admin
 * "Agreements by ERM" grouped view. Deliberately omits the consultant
 * PII block (SSN, ACH, etc.) the full entity carries — the grouped
 * overview only needs identity, status, and owner for grouping +
 * drill-in. {@code ownerName} is resolved from agreement_user.
 */
public record AgreementSummaryDto(
        String appId,
        String consultantName,
        String consultantEmail,
        String status,
        String ownerErmId,
        String ownerName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgreementSummaryDto from(ConsultantApplication app) {
        return new AgreementSummaryDto(
                app.getApplicationId(),
                app.getConsultantName(),
                app.getConsultantEmail(),
                app.getStatus(),
                app.getOwnerErmId(),
                app.getOwnerName(),
                app.getCreatedAt(),
                app.getUpdatedAt());
    }
}
