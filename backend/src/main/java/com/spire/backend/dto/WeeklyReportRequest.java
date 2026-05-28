package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Body of POST /api/participants/reports/weekly (final submit) and
 * POST /api/participants/reports/weekly/draft (partial save).
 *
 * The structured fields (week dates, status) live on dedicated
 * {@link com.spire.backend.entity.WeeklyReport} columns; everything
 * the participant fills on the form gets stashed as JSON on the
 * {@code reportData} column. This keeps the schema stable while the
 * form schema can evolve without migrations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeeklyReportRequest {

    private LocalDate weekStart;
    private LocalDate weekEnd;

    /** Array of job-submission rows from the report form. */
    private List<JobSubmission> jobSubmissions;
    /** Free-form resume / portal / LinkedIn activity. */
    private Map<String, String> resumeActivities;
    /** Mock interview + coaching feedback. */
    private Map<String, String> interviewTraining;
    /** Messages acknowledged + questions for the ERM. */
    private Map<String, String> communications;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class JobSubmission {
        private String company;
        private String client;
        private String jobTitle;
        private String technology;
        private String portal;
        private String applicationLink;
        private String submissionDate;
        private String status;
        private String followUpDate;
    }
}
