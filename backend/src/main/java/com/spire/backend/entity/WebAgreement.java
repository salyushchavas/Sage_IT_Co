package com.spire.backend.entity;

import com.spire.backend.security.SensitiveTextConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The website copy of the console's consultant agreement
 * ({@link ConsultantApplication}). An ERM starts it from the ERM dashboard
 * for a participant who said "I'm ready to sign"; the participant fills and
 * signs it inside the website with their normal sign-in (no email code, no
 * link expiry). Kept in its own table so nothing done on the website ever
 * touches the console's rows.
 *
 * Field and column names are IDENTICAL to {@link ConsultantApplication} so
 * the copied wizard, rules and renderer adapter map 1:1. Console-only columns
 * (email-code login, access link, Cloudinary ids and URLs, legacy duplicates)
 * are left out. Every *S3Key column is TEXT: it holds the value
 * {@code DocumentStorageService} returned, and TEXT keeps the row inside
 * MySQL's 65,535-byte row limit locally.
 *
 * Lifecycle (same status names as the console):
 *
 *   SUBMITTED (waiting for the participant) -> VERIFIED (signed; the ERM
 *   checks it, consultantCopyReleased=true once the ERM verifies)
 *   VERIFIED -> REVISION_REQUESTED -> (participant re-signs) -> VERIFIED
 *
 * Off-ramp: CANCELLED (ERM). The approval statuses are defined for later.
 */
@Entity
@Table(name = "web_agreements", indexes = {
        @Index(name = "idx_web_agreement_app_id", columnList = "application_id", unique = true),
        @Index(name = "idx_web_agreement_participant", columnList = "participant_user_id"),
        @Index(name = "idx_web_agreement_owner", columnList = "owner_user_id"),
        @Index(name = "idx_web_agreement_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUID -- the only identifier used in ERM URLs. */
    @Column(name = "application_id", nullable = false, length = 64, unique = true)
    private String applicationId;

    /** users.id of the participant who fills and signs it. */
    @Column(name = "participant_user_id", nullable = false)
    private Long participantUserId;

    /** users.id of the website ERM who created it (owner; ERMs see only their own). */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /**
     * Display name of the owning ERM for the admin list view. Not
     * persisted; filled by the list service. Null elsewhere.
     */
    @Transient
    private String ownerName;

    /**
     * The server's resolved answer to "which sections does the submit gate
     * apply to", keyed {@code appendix1..appendix5}, {@code ssn},
     * {@code ssnDocRequired}. Same resolution the submit validator gates on
     * ({@code WebAgreementRules.resolveEffectiveRequirements}); filled on the
     * participant read so the wizard renders the server's answer. Never
     * persisted.
     */
    @Transient
    private java.util.Map<String, Boolean> effectiveRequirements;

    /**
     * Can the ERM still take back the open change request? Resolved on the
     * ERM detail read from {@link #revisionUndoSnapshot}; never persisted,
     * null everywhere else. Same meaning as the console's fields.
     */
    @Transient
    private Boolean revisionRevocable;
    @Transient
    private String revisionRevokeBlockedReason;
    @Transient
    private Boolean revisionConsultantActed;
    @Transient
    private java.util.List<String> revisionRevokeReverts;

    // ── Soft delete (archive) ────────────────────────────────────────

    @Column(name = "deleted")
    @Builder.Default
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** users.id of whoever archived it. */
    @Column(name = "deleted_by")
    private Long deletedBy;

    // ── Identity (ERM-set at create; the participant may correct the name) ──

    /** Lower-cased and trimmed at create. */
    @Column(name = "consultant_email", nullable = false, length = 255)
    private String consultantEmail;

    /** Composed First + Middle? + Last; the render paths read this. */
    @Column(name = "consultant_name", length = 255)
    private String consultantName;

    @Column(name = "first_name", length = 120)
    private String firstName;

    @Column(name = "middle_name", length = 120)
    private String middleName;

    @Column(name = "last_name", length = 120)
    private String lastName;

    @Column(name = "consultant_phone", length = 32)
    private String consultantPhone;

    /** See class doc for the state machine. Holds {@code Status.X.name()}. */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Signing audit trail ──────────────────────────────────────────

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "signed_legal_name", length = 255)
    private String signedLegalName;

    @Column(name = "signed_ip", length = 64)
    private String signedIp;

    @Column(name = "signed_user_agent", columnDefinition = "TEXT")
    private String signedUserAgent;

    @Column(name = "signing_ip", length = 64)
    private String signingIp;

    @Column(name = "signing_at")
    private LocalDateTime signingAt;

    // ── ERM-filled (rate card, set at creation) ──────────────────────

    @Column(name = "rate_period_1") private String ratePeriod1;
    @Column(name = "rate_amount_1") private String rateAmount1;
    @Column(name = "rate_period_2") private String ratePeriod2;
    @Column(name = "rate_amount_2") private String rateAmount2;

    // Appendix 1 "Schedule 1 – Phase 2 Monthly Deliverables" period — ERM-set.
    @Column(name = "phase2_deliverable_period") private String phase2DeliverablePeriod;

    // ── Participant-filled: personal ─────────────────────────────────

    @Column(name = "primary_phone") private String primaryPhone;
    /** ERM-set (the create form's visaStatus). */
    @Column(name = "work_authorization_category") private String workAuthorizationCategory;
    /** ERM-set; used only when the category is "Others". */
    @Column(name = "work_authorization_other") private String workAuthorizationOther;

    // Structured US billing address.
    @Column(name = "address_line1", length = 255) private String addressLine1;
    @Column(name = "address_line2", length = 255) private String addressLine2;
    @Column(name = "address_city", length = 120) private String addressCity;
    @Column(name = "address_state", length = 8) private String addressState;
    @Column(name = "address_zip", length = 16) private String addressZip;

    /** Set to the create date by the system; read-only to the participant. */
    @Column(name = "effective_date") private LocalDate effectiveDate;

    // ── Exhibit A (scope of engagement; ERM-set) ─────────────────────

    @Column(name = "technology_track") private String technologyTrack;
    @Column(name = "custom_scope_notes", columnDefinition = "TEXT") private String customScopeNotes;

    /** Kept from the console's data model; unused (the website agreement sends no email). */
    @Column(name = "email_pretext", columnDefinition = "TEXT") private String emailPretext;

    // ── Appendix 1: Phase 2 employment ───────────────────────────────

    @Column(name = "employer_payroll_entity") private String employerPayrollEntity;
    @Column(name = "implementation_partner") private String implementationPartner;
    @Column(name = "end_client") private String endClient;
    @Column(name = "role_title") private String roleTitle;
    @Column(name = "verified_start_date") private LocalDate verifiedStartDate;
    @Column(name = "payroll_cycle") private String payrollCycle;

    // ── Appendix 2: ACH ──────────────────────────────────────────────

    @Column(name = "ach_account_type") private String achAccountType;
    @Column(name = "ach_bank_name") private String achBankName;
    @Column(name = "ach_account_holder_name") private String achAccountHolderName;
    // Encrypted at rest (FieldEncryptor); TEXT because the stored form is longer.
    @Convert(converter = SensitiveTextConverter.class)
    @Column(name = "ach_routing_number", columnDefinition = "TEXT") private String achRoutingNumber;
    @Convert(converter = SensitiveTextConverter.class)
    @Column(name = "ach_account_number", columnDefinition = "TEXT") private String achAccountNumber;
    @Column(name = "ach_notice_email") private String achNoticeEmail;
    // ERM-set debit schedule, read-only to the participant.
    @Column(name = "ach_debit_dates") private String achDebitDates;
    @Column(name = "ach_debit_amounts") private String achDebitAmounts;

    // ── Appendix 3: background check (sensitive PII) ─────────────────

    @Column(name = "bg_full_legal_name") private String bgFullLegalName;
    @Column(name = "bg_other_names_used") private String bgOtherNamesUsed;
    // Assembled from the structured fields below on every fill.
    @Column(name = "bg_current_address", columnDefinition = "TEXT") private String bgCurrentAddress;
    @Column(name = "bg_current_address_line1") private String bgCurrentAddressLine1;
    @Column(name = "bg_current_address_line2") private String bgCurrentAddressLine2;
    @Column(name = "bg_current_address_city") private String bgCurrentAddressCity;
    @Column(name = "bg_current_address_state", length = 8) private String bgCurrentAddressState;
    @Column(name = "bg_current_address_zip", length = 16) private String bgCurrentAddressZip;
    @Column(name = "bg_current_same_as_residence") private Boolean bgCurrentSameAsResidence;
    @Column(name = "bg_date_of_birth") private LocalDate bgDateOfBirth;
    // SSN, driver's licence and State-ID numbers are encrypted at rest (FieldEncryptor).
    @Convert(converter = SensitiveTextConverter.class)
    @Column(name = "bg_full_ssn", columnDefinition = "TEXT") private String bgFullSsn;
    @Convert(converter = SensitiveTextConverter.class)
    @Column(name = "bg_driver_license", columnDefinition = "TEXT") private String bgDriverLicense;
    @Convert(converter = SensitiveTextConverter.class)
    @Column(name = "bg_state_id", columnDefinition = "TEXT") private String bgStateId;
    /** "DL" | "STATE_ID" (legacy toggle the wizard still sends). */
    @Column(name = "id_type", length = 16) private String idType;

    // ── Appendix 4: portal access ────────────────────────────────────

    // Repeatable entries (JSON-in-TEXT): [{"platform":"LinkedIn","username":"john.doe"}, …].
    @Column(name = "portal_entries", columnDefinition = "TEXT") private String portalEntries;
    // ERM-set, read-only to the participant.
    @Column(name = "portal_authorized_actions", columnDefinition = "TEXT") private String portalAuthorizedActions;
    @Column(name = "portal_effective_date") private LocalDate portalEffectiveDate;
    @Column(name = "portal_revocation_contact") private String portalRevocationContact;

    // ── Appendix 5: security cheque ──────────────────────────────────

    @Column(name = "security_check_count") private String securityCheckCount;
    @Column(name = "security_check_numbers") private String securityCheckNumbers;
    @Column(name = "security_check_bank") private String securityCheckBank;
    @Column(name = "security_check_holder_name") private String securityCheckHolderName;
    @Column(name = "security_check_amount") private String securityCheckAmount;
    @Column(name = "security_check_dates") private String securityCheckDates;

    /**
     * Per-cheque list, JSON-in-TEXT:
     * {@code [{"index":0,"number":"1001","date":"2026-09-15","s3Key":"…",
     * "contentType":"image/jpeg","uploadedAt":"2026-06-10T…"}]}. Same shape
     * as the console's minus {@code publicId}. Index 0 is mirrored into the
     * {@code cheque*} columns below.
     */
    @Column(name = "cheques", columnDefinition = "TEXT")
    private String cheques;

    // ── ERM countersignature (later) ─────────────────────────────────

    @Column(name = "erm_name") private String ermName;
    @Column(name = "erm_title") private String ermTitle;
    @Column(name = "erm_signature_s3_key", columnDefinition = "TEXT") private String ermSignatureS3Key;
    @Column(name = "erm_signature_date") private LocalDateTime ermSignatureDate;

    /** Participant signing date, rendered on the "Date / Email:" lines. */
    @Column(name = "signature_date") private LocalDateTime signatureDate;

    /**
     * Per-section signing dates, JSON map of section id ("main-agreement",
     * "appendix1".."appendix5") → ISO timestamp. Only the sections (re)signed
     * in a round are re-stamped.
     */
    @Column(name = "section_signature_dates", columnDefinition = "TEXT")
    private String sectionSignatureDates;

    // ── Revision tracking ────────────────────────────────────────────

    @Column(name = "current_revision_remarks", columnDefinition = "TEXT")
    private String currentRevisionRemarks;

    @Column(name = "revision_count")
    @Builder.Default
    private Integer revisionCount = 0;

    /**
     * ERM section-picker revision scope, JSON {@code [{"key":"appendix4","note":"…"}]}.
     * While status = REVISION_REQUESTED and this is non-empty, the participant
     * may edit ONLY these sections (+ the sign step).
     */
    @Column(name = "revision_sections", columnDefinition = "TEXT")
    private String revisionSections;

    /**
     * Undo state for the OPEN change request (see the console's
     * {@code captureRevisionUndo}). Plain TEXT on purpose: LONGTEXT is
     * MySQL-only and breaks Postgres.
     */
    @Column(name = "revision_prev_status", length = 40)
    private String revisionPrevStatus;

    @Column(name = "revision_requested_at")
    private LocalDateTime revisionRequestedAt;

    @Column(name = "revision_undo_snapshot", columnDefinition = "TEXT")
    private String revisionUndoSnapshot;

    /** Phase 2 reopened-section scope (later). */
    @Column(name = "phase2_reopened_sections", columnDefinition = "TEXT")
    private String phase2ReopenedSections;

    // ── Final PDFs and approval round (later) ────────────────────────

    @Column(name = "s3_key", columnDefinition = "TEXT")
    private String s3Key;

    @Column(name = "consultant_pdf_s3_key", columnDefinition = "TEXT")
    private String consultantPdfS3Key;

    @Column(name = "phase1_final_pdf_s3_key", columnDefinition = "TEXT")
    private String phase1FinalPdfS3Key;

    @Column(name = "approval_version_number")
    private Integer approvalVersionNumber;

    // ── Uploads + signatures (values from DocumentStorageService) ────

    @Column(name = "cheque_s3_key", columnDefinition = "TEXT")
    private String chequeS3Key;

    @Column(name = "cheque_uploaded_at")
    private LocalDateTime chequeUploadedAt;

    @Column(name = "cheque_content_type", length = 64)
    private String chequeContentType;

    @Column(name = "work_auth_doc_s3_key", columnDefinition = "TEXT")
    private String workAuthDocS3Key;

    @Column(name = "work_auth_doc_uploaded_at")
    private LocalDateTime workAuthDocUploadedAt;

    @Column(name = "work_auth_doc_content_type", length = 64)
    private String workAuthDocContentType;

    @Column(name = "offer_letter_s3_key", columnDefinition = "TEXT")
    private String offerLetterS3Key;

    @Column(name = "offer_letter_uploaded_at")
    private LocalDateTime offerLetterUploadedAt;

    @Column(name = "offer_letter_content_type", length = 64)
    private String offerLetterContentType;

    @Column(name = "dl_doc_s3_key", columnDefinition = "TEXT")
    private String dlDocS3Key;

    @Column(name = "dl_doc_uploaded_at")
    private LocalDateTime dlDocUploadedAt;

    @Column(name = "dl_doc_content_type", length = 64)
    private String dlDocContentType;

    @Column(name = "state_id_doc_s3_key", columnDefinition = "TEXT")
    private String stateIdDocS3Key;

    @Column(name = "state_id_doc_uploaded_at")
    private LocalDateTime stateIdDocUploadedAt;

    @Column(name = "state_id_doc_content_type", length = 64)
    private String stateIdDocContentType;

    @Column(name = "ssn_doc_s3_key", columnDefinition = "TEXT")
    private String ssnDocS3Key;

    @Column(name = "ssn_doc_uploaded_at")
    private LocalDateTime ssnDocUploadedAt;

    @Column(name = "ssn_doc_content_type", length = 64)
    private String ssnDocContentType;

    /** Primary signature (drawn on the main-agreement step). */
    @Column(name = "signature_s3_key", columnDefinition = "TEXT")
    private String signatureS3Key;

    /** Closing execution signature (drawn on the review step). */
    @Column(name = "final_signature_s3_key", columnDefinition = "TEXT")
    private String finalSignatureS3Key;

    @Column(name = "final_signed_at")
    private LocalDateTime finalSignedAt;

    @Column(name = "final_signing_ip", length = 64)
    private String finalSigningIp;

    // ── Section-by-section "I understand" ticks ──────────────────────

    @Column(name = "affirmed_main_agreement", nullable = false)
    @Builder.Default
    private Boolean affirmedMainAgreement = false;

    @Column(name = "affirmed_exhibit_a", nullable = false)
    @Builder.Default
    private Boolean affirmedExhibitA = false;

    @Column(name = "affirmed_exhibit_b", nullable = false)
    @Builder.Default
    private Boolean affirmedExhibitB = false;

    @Column(name = "affirmed_appendix1", nullable = false)
    @Builder.Default
    private Boolean affirmedAppendix1 = false;

    @Column(name = "affirmed_appendix2", nullable = false)
    @Builder.Default
    private Boolean affirmedAppendix2 = false;

    @Column(name = "affirmed_appendix3", nullable = false)
    @Builder.Default
    private Boolean affirmedAppendix3 = false;

    @Column(name = "affirmed_appendix4", nullable = false)
    @Builder.Default
    private Boolean affirmedAppendix4 = false;

    @Column(name = "affirmed_appendix5", nullable = false)
    @Builder.Default
    private Boolean affirmedAppendix5 = false;

    // ── Per-agreement requirement flags (ERM-set at create) ──────────

    @Column(name = "require_appendix1", nullable = false)
    @Builder.Default
    private Boolean requireAppendix1 = false;

    @Column(name = "require_appendix2", nullable = false)
    @Builder.Default
    private Boolean requireAppendix2 = false;

    @Column(name = "require_appendix3", nullable = false)
    @Builder.Default
    private Boolean requireAppendix3 = false;

    @Column(name = "require_appendix4", nullable = false)
    @Builder.Default
    private Boolean requireAppendix4 = false;

    @Column(name = "require_appendix5", nullable = false)
    @Builder.Default
    private Boolean requireAppendix5 = false;

    @Column(name = "require_ssn", nullable = false)
    @Builder.Default
    private Boolean requireSsn = false;

    // ── Two-phase coaching (later) ───────────────────────────────────

    @Column(name = "phase")
    @Builder.Default
    private Integer phase = 1;

    // ── ERM "Verify" (the console's consultant-version release) ──────
    //
    // VERIFIED + released=false means "the ERM is checking it";
    // released=true means "Verified by your ERM". releasedBy holds the
    // ERM's users.id as text.

    @Column(name = "consultant_copy_released", nullable = false)
    @Builder.Default
    private Boolean consultantCopyReleased = false;

    @Column(name = "consultant_copy_released_at")
    private LocalDateTime consultantCopyReleasedAt;

    @Column(name = "consultant_copy_released_by", length = 36)
    private String consultantCopyReleasedBy;

    /** SHA-256 (hex) of a released PDF (later). */
    @Column(name = "document_hash", length = 128)
    private String documentHash;

    // ── E-sign consent record ────────────────────────────────────────

    @Column(name = "consent_given_at")
    private LocalDateTime consentGivenAt;

    @Column(name = "consent_ip", length = 64)
    private String consentIp;

    @Column(name = "consent_version", length = 32)
    private String consentVersion;

    // ── Status enum (string-keyed, same names as the console) ────────

    public enum Status {
        SUBMITTED,
        REVISION_REQUESTED,
        VERIFIED,
        // Later — the role-based approval gate and countersign.
        AWAITING_APPROVALS,
        APPROVAL_REVISION_REQUESTED,
        READY_TO_SIGN,
        COMPLETED,
        CANCELLED
    }
}
