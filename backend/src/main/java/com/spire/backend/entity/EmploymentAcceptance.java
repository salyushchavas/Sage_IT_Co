package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Captured when a participant accepts an employment offer. The ERM
 * verifies the offer letter and flips {@code ermVerified} to true,
 * which is the trigger event for the PHASE_1_COMPLETED workflow
 * transition.
 */
@Entity
@Table(name = "employment_acceptances", indexes = {
        @Index(name = "idx_emp_user_id", columnList = "user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmploymentAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "employer_client", length = 255)
    private String employerClient;

    @Column(name = "job_title", length = 255)
    private String jobTitle;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "offer_document_url", length = 500)
    private String offerDocumentUrl;

    @Column(name = "location", length = 255)
    private String location;

    /** Full-time / Part-time / Contract / etc. */
    @Column(name = "employment_type", length = 50)
    private String employmentType;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "acceptance_date")
    private LocalDateTime acceptanceDate;

    @Column(name = "erm_verified")
    @Builder.Default
    private Boolean ermVerified = false;

    @Column(name = "erm_verified_date")
    private LocalDateTime ermVerifiedDate;

    /** ERM-side note captured at verification time. */
    @Column(name = "erm_notes", columnDefinition = "TEXT")
    private String ermNotes;
}
