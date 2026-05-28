package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of POST /api/participants/program-selection (final submit)
 * and POST /api/participants/program-selection/draft (partial save).
 *
 * The draft path accepts ANY subset of fields; the final-submit
 * path validates them in {@code ProgramSelectionService.submit}.
 * Keeping a single DTO simplifies the FE contract — the page just
 * sends whichever fields the participant has filled.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProgramSelectionRequest {
    private String program;
    private String phase;
    private String skillset;
    private String targetJobTitle;
    private String coachingPreference;
    private String availability;
    private String servicePackage;
    private String serviceSummaryVersion;
    private String notes;
}
