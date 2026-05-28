package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Snapshot of a participant's progressive-profile state.
 * Powers the dashboard's "Complete Your Profile" banner + checklist.
 * Returned by GET /api/participants/profile/completion and embedded
 * in 403 responses from gated endpoints so the frontend can render
 * the same gate modal everywhere.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProfileCompletionDto {

    /** 0–100, derived from the six per-step flags. */
    private int completionPercentage;
    private int completedSteps;
    private int totalSteps;
    /** True once all six per-step flags are set. */
    private boolean isComplete;
    /** Step key the user should tackle next, or "COMPLETE" when done. */
    private String nextStep;

    /** Per-step rows in display order. */
    private List<StepInfo> steps;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StepInfo {
        /** Stable enum-like key — BASIC_INFO, ACKNOWLEDGMENT, … */
        private String key;
        private String title;
        private String description;
        /** Free-text estimate displayed on the card ("1 min", "5 min"). */
        private String estimatedTime;
        private boolean completed;

        public static StepInfo of(String key, String title,
                                  String description, String estimatedTime,
                                  Boolean completed) {
            return StepInfo.builder()
                    .key(key)
                    .title(title)
                    .description(description)
                    .estimatedTime(estimatedTime)
                    .completed(Boolean.TRUE.equals(completed))
                    .build();
        }
    }
}
