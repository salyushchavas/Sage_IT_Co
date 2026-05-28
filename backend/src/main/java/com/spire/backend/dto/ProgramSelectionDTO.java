package com.spire.backend.dto;

import com.spire.backend.entity.ProgramSelection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * View of {@link ProgramSelection} sent to the frontend. Used both
 * to surface a saved draft (pre-fill the form on return visits) and
 * to confirm a finalised submission.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProgramSelectionDTO {

    private Long id;
    private String program;
    private String phase;
    private String skillset;
    private String targetJobTitle;
    private String coachingPreference;
    private String availability;
    private String servicePackage;
    private String serviceSummaryVersion;
    private String notes;
    private LocalDateTime selectionDate;

    public static ProgramSelectionDTO from(ProgramSelection ps) {
        if (ps == null) return null;
        return ProgramSelectionDTO.builder()
                .id(ps.getId())
                .program(ps.getProgram())
                .phase(ps.getPhase())
                .skillset(ps.getSkillset())
                .targetJobTitle(ps.getTargetJobTitle())
                .coachingPreference(ps.getCoachingPreference())
                .availability(ps.getAvailability())
                .servicePackage(ps.getServicePackage())
                .serviceSummaryVersion(ps.getServiceSummaryVersion())
                .notes(ps.getNotes())
                .selectionDate(ps.getSelectionDate())
                .build();
    }
}
