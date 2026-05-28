package com.spire.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of POST /api/participants/acknowledgments. The participant
 * fills the consent form on /acknowledgment (Step 4); the page
 * posts the three required checkbox flags plus the typed legal name
 * and a captured signature image.
 *
 * Validation lives in {@code AcknowledgmentService.submit} rather
 * than purely on the DTO so the failure path can return the
 * specific message ("All three consents are required", "Signature
 * is required", …) rather than a generic 400.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcknowledgmentSubmitRequest {

    @NotBlank(message = "Legal name is required")
    private String legalName;

    /** {@code data:image/png;base64,…} or {@code data:image/jpeg;base64,…}. */
    @NotBlank(message = "Signature is required")
    private String signatureImage;

    /** Either {@code "draw"} or {@code "upload"}. */
    private String signatureMethod;

    private Boolean interestAccepted;
    private Boolean documentationConsent;
    private Boolean communicationConsent;

    /** Pinned by the frontend so we record exactly which text version was accepted. */
    @NotBlank(message = "acknowledgmentVersion is required")
    private String acknowledgmentVersion;
}
