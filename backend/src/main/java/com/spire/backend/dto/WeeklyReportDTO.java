package com.spire.backend.dto;

import com.spire.backend.entity.WeeklyReport;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * View of {@link WeeklyReport} sent to the participant dashboard.
 * The structured form payload lives in {@code reportData} as a JSON
 * string — the frontend deserialises it back into the form schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeeklyReportDTO {

    private Long id;
    private LocalDate weekStart;
    private LocalDate weekEnd;
    private LocalDate submissionDueDate;
    private LocalDateTime submittedAt;
    private LocalDateTime ermReviewDate;
    private String ermNotes;
    private String reportData;
    /** PENDING, SUBMITTED, REVIEWED, OVERDUE. */
    private String status;

    public static WeeklyReportDTO from(WeeklyReport r) {
        if (r == null) return null;
        return WeeklyReportDTO.builder()
                .id(r.getId())
                .weekStart(r.getWeekStart())
                .weekEnd(r.getWeekEnd())
                .submissionDueDate(r.getSubmissionDueDate())
                .submittedAt(r.getSubmittedAt())
                .ermReviewDate(r.getErmReviewDate())
                .ermNotes(r.getErmNotes())
                .reportData(r.getReportData())
                .status(r.getStatus())
                .build();
    }
}
