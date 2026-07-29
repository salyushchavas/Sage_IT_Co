package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ERM-to-Consultant engagement application.
 *
 * Public-facing identifier is {@code applicationId} (a UUID); the
 * numeric {@code id} stays internal. Lifecycle:
 *
 *   DRAFT -> SUBMITTED -> [REVISION_REQUESTED -> UPDATED ->]*
 *           VERIFIED -> SIGNED -> COMPLETED
 *
 * Terminal off-ramps: CANCELLED (ERM-initiated) or EXPIRED (cron
 * after {@code expiresAt} crosses).
 *
 * The structured payload (consultant onboarding fields) is stored as
 * a JSON-in-TEXT column rather than native JSONB so the same row
 * shape works on MySQL dev + Postgres prod -- mirrors the convention
 * {@link Acknowledgment}.consentFlags follows. Parse via ObjectMapper
 * in the service layer; do not query inside the JSON from SQL.
 */
@Entity
@Table(name = "consultant_applications", indexes = {
        @Index(name = "idx_consultant_app_id", columnList = "application_id", unique = true),
        @Index(name = "idx_consultant_erm_id", columnList = "erm_user_id"),
        @Index(name = "idx_consultant_email", columnList = "consultant_email"),
        @Index(name = "idx_consultant_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsultantApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUID -- the only identifier exposed to consultants in URLs / emails. */
    @Column(name = "application_id", nullable = false, length = 64, unique = true)
    private String applicationId;

    @Column(name = "erm_user_id", nullable = false)
    private Long ermUserId;

    /**
     * Id of the {@link AgreementUser} (SUPER_ADMIN | ERM) who created
     * this application. Stamped at create time; nullable so legacy rows
     * (and the transient window of pre-multi-user tokens) stay valid.
     *
     * Phase B uses this for per-ERM data isolation: an ERM sees/acts on
     * only their own applications; the super-admin sees all.
     */
    @Column(name = "owner_erm_id", length = 36)
    private String ownerErmId;

    /**
     * Display name of the owning ERM, resolved from {@link AgreementUser}
     * for the super-admin's list view. Not persisted; populated by the
     * list service. Null on detail responses and for ERM lists (an ERM's
     * list is all theirs).
     */
    @Transient
    private String ownerName;

    /**
     * Build O — ERM "All" list approval summary, resolved by the list
     * service onto these transient fields (one batched query per page,
     * never persisted). {@code managerStatus} / {@code accountsStatus}
     * carry the latest gate decision per role ({@code PENDING} /
     * {@code APPROVED} / {@code REVISION_REQUESTED}), or null when no gate
     * exists yet (Phase 1 has no Accounts gate → the UI shows "N/A").
     * {@code sentForApprovalAt} is the ISO timestamp the agreement was
     * first routed to approvers (the {@code SENT_FOR_APPROVAL} event), or
     * null if it has never been sent. Null on detail responses.
     */
    @Transient
    private String managerStatus;
    @Transient
    private String accountsStatus;
    @Transient
    private String sentForApprovalAt;

    /**
     * Build Q — DERIVED consultant-link expiry. True when an awaiting
     * (SUBMITTED) agreement's access link is older than the 7-day TTL
     * (from {@code inviteSentAt}). Computed by the service on consultant +
     * ERM list reads; never persisted and never a substitute for status —
     * the agreement keeps its lifecycle state and stays visible. Drives
     * the "Link expired — resend" indicator (ERM) and the "link expired —
     * contact Sage IT" screen (consultant).
     */
    @Transient
    private Boolean linkExpired;

    /**
     * Build AP — the server's resolved answer to "which sections does the
     * submit gate apply to for this agreement", keyed
     * {@code appendix1..appendix5}, {@code ssn}, {@code ssnDocRequired}.
     * Computed by {@code resolveEffectiveRequirements} — the SAME call the
     * submit validator gates on — and populated on the consultant read so
     * the wizard renders the server's answer rather than re-deriving its
     * own. When the two derivations disagreed, the server demanded fields
     * the wizard had hidden and the agreement became unsubmittable.
     * Never persisted; null on ERM/list responses.
     */
    @Transient
    private java.util.Map<String, Boolean> effectiveRequirements;

    /**
     * Phase C — soft delete (archive). Only the super-admin archives,
     * and only CANCELLED applications. The row stays in the DB
     * (recoverable, audit history + Cloudinary PDF preserved); it's
     * hidden from every console list and 404s on detail/mutation.
     * Mapped as a nullable Boolean (+ DataSeeder backfills existing rows
     * to false) so the {@code deleted = false} filter includes them.
     */
    @Column(name = "deleted")
    @Builder.Default
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by", length = 36)
    private String deletedBy;

    @Column(name = "consultant_email", nullable = false, length = 255)
    private String consultantEmail;

    /**
     * Composed full legal name (First + Middle? + Last), kept as the
     * single source the PDF / clauses / filename read through
     * {@code ${participantFullLegalName}}. Build W splits capture into
     * the three structured columns below; the service composes this
     * column from them on every create / fill so existing render paths
     * stay untouched. Legacy rows keep whatever single value they hold.
     */
    @Column(name = "consultant_name", length = 255)
    private String consultantName;

    // ── Build W: structured name (First required, Middle optional,
    //    Last required). Composed into consultantName on save. ─────────

    @Column(name = "first_name", length = 120)
    private String firstName;

    @Column(name = "middle_name", length = 120)
    private String middleName;

    @Column(name = "last_name", length = 120)
    private String lastName;

    @Column(name = "consultant_phone", length = 32)
    private String consultantPhone;

    /**
     * Free-form JSON payload of consultant onboarding fields. v1 ships
     * with a placeholder schema -- the ERM types raw JSON in a textarea
     * -- so the column is intentionally opaque to the DB. Structured
     * fields land once the schema is locked.
     */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    /** See class doc for the state machine. */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "revision_notes", columnDefinition = "TEXT")
    private String revisionNotes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    // ── Signing audit trail ──────────────────────────────────────────

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "signature_image", columnDefinition = "TEXT")
    private String signatureImage;

    @Column(name = "signed_legal_name", length = 255)
    private String signedLegalName;

    @Column(name = "signed_ip", length = 64)
    private String signedIp;

    @Column(name = "signed_user_agent", columnDefinition = "TEXT")
    private String signedUserAgent;

    /** Cloudinary URL of the generated signed PDF. */
    @Column(name = "signed_pdf_url", columnDefinition = "TEXT")
    private String signedPdfUrl;

    // ── Consultant access record (email-OTP gate) ─────────────────────
    //
    // access* is captured when the consultant passes the OTP gate;
    // signing* when they submit/sign. Real client IP via X-Forwarded-For
    // (first hop). Surfaced to the ERM/admin; NOT embedded in the PDF.

    @Column(name = "access_ip", length = 64)
    private String accessIp;

    @Column(name = "access_at")
    private LocalDateTime accessAt;

    @Column(name = "signing_ip", length = 64)
    private String signingIp;

    @Column(name = "signing_at")
    private LocalDateTime signingAt;

    // ── Consultant OTP state ─────────────────────────────────────────

    @Column(name = "otp_hash", length = 255)
    private String otpHash;

    @Column(name = "otp_expires_at")
    private LocalDateTime otpExpiresAt;

    @Column(name = "otp_failed_attempts", nullable = false)
    @Builder.Default
    private int otpFailedAttempts = 0;

    @Column(name = "otp_locked_until")
    private LocalDateTime otpLockedUntil;

    // ── Two-stage fill workflow ──────────────────────────────────────
    //
    // From the multi-stage agreement workflow: the ERM seeds the row
    // with rate fields + identity at creation; the consultant fills
    // their personal block, Exhibit A (scope), Appendix 1 (employment),
    // and the four optional appendices (ACH, background check, portal
    // access, security check). All columns are nullable and additive --
    // no state machine, endpoint, or service code references them yet.
    // The DDL fixup in DataSeeder backfills them on existing rows so
    // Hibernate's ddl-auto=update sees a no-op on the next boot.

    // ── ERM-filled (rate card, set at creation) ──────────────────────

    @Column(name = "rate_period_1") private String ratePeriod1;
    @Column(name = "rate_amount_1") private String rateAmount1;
    @Column(name = "rate_period_2") private String ratePeriod2;
    @Column(name = "rate_amount_2") private String rateAmount2;

    // Appendix 1 "Schedule 1 – Phase 2 Monthly Deliverables" period — ERM-set,
    // single merged cell; stamps the ${phase2DeliverablePeriod} placeholder.
    @Column(name = "phase2_deliverable_period") private String phase2DeliverablePeriod;

    // ── Consultant-filled: personal (required) ───────────────────────

    @Column(name = "primary_phone") private String primaryPhone;
    @Column(name = "work_authorization_category") private String workAuthorizationCategory;
    /**
     * Build W: free-text custom value used ONLY when
     * {@code workAuthorizationCategory == "Others"}. ERM-set; the
     * render layer substitutes this for the category when Others is
     * chosen so the PDF/clauses show the real status, not the literal
     * word "Others".
     */
    @Column(name = "work_authorization_other") private String workAuthorizationOther;

    /**
     * Build W: legacy single-block residence address. No longer
     * collected from the form — kept (never dropped) so old rows stay
     * readable. New rows leave it null and use the structured columns
     * below; the render layer assembles the US-format address from
     * them and falls back to this column for legacy rows.
     */
    @Column(name = "residence_address", columnDefinition = "TEXT") private String residenceAddress;

    // ── Build W: structured US billing address. Assembled into the
    //    ${residenceAddress} placeholder by the render layer. ──────────

    @Column(name = "address_line1", length = 255) private String addressLine1;
    @Column(name = "address_line2", length = 255) private String addressLine2;
    @Column(name = "address_city", length = 120) private String addressCity;
    @Column(name = "address_state", length = 8) private String addressState;
    @Column(name = "address_zip", length = 16) private String addressZip;

    @Column(name = "effective_date") private LocalDate effectiveDate;

    // ── Exhibit A (scope of engagement) ──────────────────────────────

    @Column(name = "technology_track") private String technologyTrack;
    @Column(name = "custom_scope_notes", columnDefinition = "TEXT") private String customScopeNotes;

    // Build O — optional ERM-authored email message/pre-text used as the
    // intro of the consultant's invitation email. Blank/null → the Sage IT
    // Co default copy. Set at create time only; not surfaced to the consultant.
    @Column(name = "email_pretext", columnDefinition = "TEXT") private String emailPretext;

    // ── Appendix 1: Phase 2 employment ───────────────────────────────

    @Column(name = "employer_payroll_entity") private String employerPayrollEntity;
    @Column(name = "implementation_partner") private String implementationPartner;
    @Column(name = "end_client") private String endClient;
    @Column(name = "role_title") private String roleTitle;
    @Column(name = "verified_start_date") private LocalDate verifiedStartDate;
    @Column(name = "payroll_cycle") private String payrollCycle;

    // ── Appendix 2: ACH (optional) ───────────────────────────────────

    @Column(name = "ach_account_type") private String achAccountType;
    @Column(name = "ach_bank_name") private String achBankName;
    @Column(name = "ach_account_holder_name") private String achAccountHolderName;
    @Column(name = "ach_routing_number") private String achRoutingNumber;
    @Column(name = "ach_account_number") private String achAccountNumber;
    @Column(name = "ach_notice_email") private String achNoticeEmail;
    /**
     * Build Y — the debit schedule is now ERM-filled at create as a
     * repeatable list of (date, amount) rows, stored JSON-in-TEXT
     * (mirrors {@link #cheques}). Example:
     * {@code [{"date":"2026-07-15","amount":"$416.67"}, …]}.
     * The flattened, comma-joined views are kept on the legacy
     * {@code achDebitDates} / {@code achDebitAmounts} columns (dates
     * formatted MM-DD-YYYY) so the existing ${achDebitDates} /
     * ${achDebitAmounts} placeholders render unchanged; read-only to
     * the consultant.
     */
    @Column(name = "ach_debit_schedule", columnDefinition = "TEXT") private String achDebitSchedule;
    @Column(name = "ach_debit_dates") private String achDebitDates;
    @Column(name = "ach_debit_amounts") private String achDebitAmounts;

    // ── Appendix 3: background check (sensitive PII) ─────────────────

    @Column(name = "bg_full_legal_name") private String bgFullLegalName;
    @Column(name = "bg_other_names_used") private String bgOtherNamesUsed;
    // Legacy single free-text current address. Build J keeps it populated
    // (assembled from the structured fields below) so the existing
    // ${bgCurrentAddress} placeholder renders unchanged.
    @Column(name = "bg_current_address", columnDefinition = "TEXT") private String bgCurrentAddress;
    // Build J — structured current address (mirrors the residence address).
    @Column(name = "bg_current_address_line1") private String bgCurrentAddressLine1;
    @Column(name = "bg_current_address_line2") private String bgCurrentAddressLine2;
    @Column(name = "bg_current_address_city") private String bgCurrentAddressCity;
    @Column(name = "bg_current_address_state", length = 8) private String bgCurrentAddressState;
    @Column(name = "bg_current_address_zip", length = 16) private String bgCurrentAddressZip;
    // Build J — "Same as residence address" toggle (UI re-lock on reload).
    @Column(name = "bg_current_same_as_residence") private Boolean bgCurrentSameAsResidence;
    @Column(name = "bg_date_of_birth") private LocalDate bgDateOfBirth;
    @Column(name = "bg_full_ssn", columnDefinition = "TEXT") private String bgFullSsn;
    @Column(name = "bg_driver_license", columnDefinition = "TEXT") private String bgDriverLicense;
    // Build AK — Appendix 3 now accepts a Driver's License AND/OR a State ID.
    // bgDriverLicense is the DL number; bgStateId is the State-ID number.
    @Column(name = "bg_state_id", columnDefinition = "TEXT") private String bgStateId;

    // ── Build J: Background Check document uploads ────────────────────
    // DL doc + State-ID doc (Build AK — at least one required when Appendix 3
    // applies; both required if both are provided) + SSN doc (always optional).
    // Same Cloudinary/append pipeline as Build I.
    @Column(name = "dl_doc_public_id") private String dlDocPublicId;
    @Column(name = "dl_doc_uploaded_at") private LocalDateTime dlDocUploadedAt;
    @Column(name = "dl_doc_content_type", length = 64) private String dlDocContentType;
    // Build AK — State-ID document (mirrors the DL doc columns).
    @Column(name = "state_id_doc_public_id") private String stateIdDocPublicId;
    @Column(name = "state_id_doc_uploaded_at") private LocalDateTime stateIdDocUploadedAt;
    @Column(name = "state_id_doc_content_type", length = 64) private String stateIdDocContentType;
    @Column(name = "ssn_doc_public_id") private String ssnDocPublicId;
    @Column(name = "ssn_doc_uploaded_at") private LocalDateTime ssnDocUploadedAt;
    @Column(name = "ssn_doc_content_type", length = 64) private String ssnDocContentType;

    // ── Appendix 4: portal access (optional) ─────────────────────────

    @Column(name = "portal_platform") private String portalPlatform;
    @Column(name = "portal_username") private String portalUsername;
    // Build J — repeatable platform+username entries (JSON-in-TEXT, mirrors
    // the cheques pattern): [{"platform":"LinkedIn","username":"john.doe"}, …].
    // The legacy portal_platform/portal_username above hold the flattened,
    // comma-joined views for the existing template placeholders.
    @Column(name = "portal_entries", columnDefinition = "TEXT") private String portalEntries;
    @Column(name = "portal_authorized_actions", columnDefinition = "TEXT") private String portalAuthorizedActions;
    @Column(name = "portal_effective_date") private LocalDate portalEffectiveDate;
    @Column(name = "portal_revocation_contact") private String portalRevocationContact;

    // ── Appendix 5: security check (optional) ────────────────────────

    @Column(name = "security_check_count") private String securityCheckCount;
    @Column(name = "security_check_numbers") private String securityCheckNumbers;
    @Column(name = "security_check_bank") private String securityCheckBank;
    @Column(name = "security_check_holder_name") private String securityCheckHolderName;
    @Column(name = "security_check_amount") private String securityCheckAmount;
    @Column(name = "security_check_dates") private String securityCheckDates;

    // ── ERM countersignature (distinct from the consultant signature
    //    captured by signatureImage / signedLegalName / signedAt) ─────

    @Column(name = "erm_name") private String ermName;
    @Column(name = "erm_title") private String ermTitle;
    @Column(name = "erm_signature_url", columnDefinition = "TEXT") private String ermSignatureUrl;
    /**
     * CONSULTANT signing date — set at consultantSubmit and rendered on
     * the consultant "Date / Email:" line of every signature block.
     * (Build W: no longer overwritten by the ERM countersign — that now
     * uses {@link #ermSignatureDate}.)
     */
    @Column(name = "signature_date") private LocalDateTime signatureDate;
    /**
     * Build AL — per-section consultant signing dates, JSON map of section id
     * ("main-agreement", "appendix1".."appendix5") → ISO timestamp. Only the
     * section(s) the consultant actually (re)signs in a given round are
     * re-stamped, so a targeted revision no longer bumps the untouched
     * appendices' signature dates. {@link #signatureDate} above remains the
     * global fallback (and the execution-trace date) for legacy rows.
     */
    @Column(name = "section_signature_dates", columnDefinition = "TEXT")
    private String sectionSignatureDates;
    /**
     * Build W — ERM countersign date. Set ONLY when the ERM signs
     * ({@code ermApproveAndSign}); null until then. Rendered on the ERM
     * "Date:" line via {@code ${ermSignatureDate}}. Stays blank while
     * the consultant signs/views so no date shows under the (still
     * unsigned) ERM signature.
     */
    @Column(name = "erm_signature_date") private LocalDateTime ermSignatureDate;

    // ── Revision tracking ────────────────────────────────────────────
    //
    // revisionNotes (above) holds the latest consultant-side reason;
    // currentRevisionRemarks holds the ERM-side rebuttal / instructions
    // for the next pass. revisionCount is a cheap denormalized counter.

    @Column(name = "current_revision_remarks", columnDefinition = "TEXT")
    private String currentRevisionRemarks;

    @Column(name = "revision_count")
    @Builder.Default
    private Integer revisionCount = 0;

    /**
     * Build Y — ERM section-picker revision scope. JSON-in-TEXT array of
     * {@code [{"key":"appendix4","note":"…"}, …]} written by
     * {@code ermRequestRevision}. While status = REVISION_REQUESTED and
     * this is non-empty, the consultant may see + edit ONLY these
     * sections (+ the final sign step); every other section stays hidden
     * and immutable for that round (enforced in {@code consultantFill}).
     * Each consultant re-submit / non-restricted revision overwrites it.
     */
    @Column(name = "revision_sections", columnDefinition = "TEXT")
    private String revisionSections;

    /**
     * Build P — Phase 2 reopened-section scope. JSON-in-TEXT array of
     * {@code [{"key":"appendix2"}, …]} written by {@code advanceToPhase2}
     * = the ERM-promoted (previously-optional) appendices. While phase ≥ 2
     * and status = SUBMITTED and this is non-null, the consultant may see
     * + edit ONLY these sections (+ the final sign step); every completed
     * Phase-1 section — including its uploads (cheques, work-auth, DL/SSN)
     * — stays hidden + immutable. Distinct from {@code revisionSections}:
     * a Build Y revision round can still scope a Phase-2 agreement on top
     * of this (and takes precedence while status = REVISION_REQUESTED).
     */
    @Column(name = "phase2_reopened_sections", columnDefinition = "TEXT")
    private String phase2ReopenedSections;

    // ── Final countersigned PDF (post-ERM signature) ─────────────────
    //
    // Distinct from signedPdfUrl (which is the consultant-signed
    // intermediate PDF generated immediately after sign()).

    @Column(name = "final_pdf_url", columnDefinition = "TEXT")
    private String finalPdfUrl;

    /**
     * Cloudinary public_id for the final PDF (e.g.
     * {@code agreements/abc12345-...}). Stored separately from the
     * full {@code secure_url} so the signed-URL helper doesn't have
     * to parse it out of an arbitrary Cloudinary URL shape -- the
     * SDK takes a public_id + type + resource_type directly. When
     * Cloudinary delivery is switched to {@code type=authenticated},
     * the raw {@code finalPdfUrl} 401s without a signature; the
     * controller re-signs from this public_id on demand.
     */
    @Column(name = "final_pdf_public_id")
    private String finalPdfPublicId;

    /**
     * Phase 1 (S3) — storage key for the S3-backed FINAL ERM-signed PDF
     * (e.g. {@code agreements/{ermId}/phase-1/final-20260612-101500.pdf}).
     * Non-null on records generated after the S3 cutover; for those the
     * Cloudinary fields above stay null. Downloads branch on this: present
     * → presigned S3 GET; else the Cloudinary path (dual-read).
     */
    @Column(name = "s3_key", length = 512)
    private String s3Key;

    /**
     * Phase 1 (S3) — storage key for the S3-backed consultant-version PDF
     * (consultant copy + Certificate of Completion). Parallel to
     * {@code consultantPdfPublicId} (Cloudinary), which stays null for new
     * records.
     */
    @Column(name = "consultant_pdf_s3_key", length = 512)
    private String consultantPdfS3Key;

    /**
     * Build S — durable snapshot of the PHASE-1 ERM-signed agreement PDF,
     * captured at the Phase-1 countersign (ermApproveAndSign while phase==1).
     * The single {@code s3Key} above is OVERWRITTEN by the Phase-2 countersign,
     * and {@code advanceToPhase2} wipes the live ERM/final-signature columns —
     * so without this key the Phase-1 signed agreement becomes un-previewable
     * once Phase 2 begins. The Manager previews the Phase-1 signed agreement by
     * rasterizing the PDF stored under THIS key (not a live re-render). Null
     * for agreements that never reached a Phase-1 countersign, and for those
     * countersigned before Build S shipped (no historical backfill).
     */
    @Column(name = "phase1_final_pdf_s3_key", length = 512)
    private String phase1FinalPdfS3Key;

    /**
     * Build V — the approved consultant-version number the ERM selected for the
     * CURRENT approval round (set at send-for-approval). The approver reviews
     * THIS version's frozen snapshot. Null before any send-for-approval.
     */
    @Column(name = "approval_version_number")
    private Integer approvalVersionNumber;

    // ── Phase 5 (S3) — consultant-UPLOAD + SIGNATURE storage keys ─────
    //
    // Parallel S3 keys for the two remaining agreements artifact classes
    // that Phases 1–2 left on Cloudinary: consultant document uploads
    // (cheque / work-auth / offer-letter / DL / SSN) and signature images
    // (consultant primary + final, ERM countersign). For NEW records the
    // matching Cloudinary id/url column above stays null; reads branch on
    // the s3_key (present → S3 bytes; else the untouched Cloudinary path).
    // Added additively by DataSeeder; nothing here is hardcoded to a doc
    // type's Cloudinary column being dropped — both coexist (dual-read).

    @Column(name = "cheque_s3_key", length = 512)
    private String chequeS3Key;

    @Column(name = "work_auth_doc_s3_key", length = 512)
    private String workAuthDocS3Key;

    @Column(name = "offer_letter_s3_key", length = 512)
    private String offerLetterS3Key;

    @Column(name = "dl_doc_s3_key", length = 512)
    private String dlDocS3Key;

    @Column(name = "state_id_doc_s3_key", length = 512)
    private String stateIdDocS3Key;

    @Column(name = "ssn_doc_s3_key", length = 512)
    private String ssnDocS3Key;

    @Column(name = "signature_s3_key", length = 512)
    private String signatureS3Key;

    @Column(name = "final_signature_s3_key", length = 512)
    private String finalSignatureS3Key;

    @Column(name = "erm_signature_s3_key", length = 512)
    private String ermSignatureS3Key;

    // ── Section-by-section affirmation flags (guided signing) ────────
    //
    // The consultant ticks an "I understand" checkbox at the end of
    // every signing section. Each maps to one flag here; submit-time
    // validation requires all eight to be true alongside every
    // consultant-fillable field + the signature. Phase 1 (additive
    // foundation) -- the wizard UI that sets them is the next batch.
    //
    // Booleans (object) so the partial-save patch can distinguish
    // "not sent" from "explicitly false". DataSeeder backfills
    // existing rows to false via DEFAULT FALSE on the column.

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

    // ── F-4: per-agreement requirement flags (ERM-configurable) ──────
    //
    // The ERM marks each appendix Required/Not Required at create time;
    // require_ssn flips Appendix 3's SSN field between mandatory and
    // optional. The wizard + the submit validation read these flags
    // off the application -- F-1's blanket "all appendices forced
    // required" is replaced by an effective-requirements model.

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

    // ── F-4: closing / execution signature ───────────────────────────
    //
    // signatureImage / signedLegalName (above) is the PRIMARY signature
    // the consultant draws on the main-agreement step -- stamped on
    // every signature block in the agreement via $signatureImage.
    // finalSignatureImage is the closing execution signature drawn at
    // the review step; stamped on the agreement's last signature block
    // via $finalSignatureImage. final_signed_at / final_signing_ip
    // form the audit trail.

    @Column(name = "final_signature_image", columnDefinition = "TEXT")
    private String finalSignatureImage;

    @Column(name = "final_signed_at")
    private LocalDateTime finalSignedAt;

    @Column(name = "final_signing_ip", length = 64)
    private String finalSigningIp;

    // ── Build G: Appendix 3 ID type toggle ───────────────────────────
    //
    // Build G repurposes the existing bgDriverLicense column as the
    // ID NUMBER (relabeled "Driver's License / State ID number") and
    // adds idType to record which kind of ID. PDF renders the chosen
    // type next to the number. Values: "DL" | "STATE_ID" -- stored
    // as plain VARCHAR (no JPA enum mapping) so DataSeeder's
    // ALTER ... ADD COLUMN IF NOT EXISTS migration shape stays
    // simple.

    @Column(name = "id_type", length = 16)
    private String idType;

    // ── Build G: Appendix 5 security cheque upload ───────────────────
    //
    // The consultant uploads a photo/PDF of the post-dated cheque(s)
    // via POST /consultant/applications/{appId}/cheque. The bytes
    // land in Cloudinary at {@code cheques/<appId>}
    // (resource_type=image, type=authenticated) and the public_id
    // is persisted here so the ERM can stream a re-signed inline
    // view on demand. The cheque is NOT embedded in the generated
    // agreement PDF in this build -- it's an out-of-band artifact
    // surfaced through the ConsultantDetailView.

    @Column(name = "cheque_public_id")
    private String chequePublicId;

    @Column(name = "cheque_uploaded_at")
    private LocalDateTime chequeUploadedAt;

    /** Original Content-Type of the uploaded cheque (image/* | application/pdf). */
    @Column(name = "cheque_content_type", length = 64)
    private String chequeContentType;

    // ── Build W: Appendix 1 work-authorization document upload ────────
    //
    // The consultant uploads a copy of their work-authorization document
    // (image/PDF) in Appendix 1 via
    // POST /consultant/applications/{appId}/workauth. Bytes land in
    // Cloudinary at {@code agreements/<appId>-workauth}
    // (resource_type=auto, type=authenticated); the public_id is
    // persisted here so the ERM can stream a re-signed inline view.
    // REQUIRED whenever Appendix 1 applies to this consultant.

    @Column(name = "work_auth_doc_public_id")
    private String workAuthDocPublicId;

    @Column(name = "work_auth_doc_uploaded_at")
    private LocalDateTime workAuthDocUploadedAt;

    /** Original Content-Type of the work-auth doc (image/* | application/pdf). */
    @Column(name = "work_auth_doc_content_type", length = 64)
    private String workAuthDocContentType;

    // ── Build I: Phase 2 Employment "Offer Letter" upload ─────────────
    //
    // The consultant uploads their offer letter (image/PDF) in the Phase 2
    // Employment section (Appendix 1) via
    // POST /consultant/applications/{appId}/offer-letter. Bytes land in
    // Cloudinary at {@code agreements/<appId>-offerletter}
    // (type=authenticated); REQUIRED whenever Appendix 1 applies. Both this
    // and the work-auth doc are appended to the final agreement PDF.

    @Column(name = "offer_letter_public_id")
    private String offerLetterPublicId;

    @Column(name = "offer_letter_uploaded_at")
    private LocalDateTime offerLetterUploadedAt;

    /** Original Content-Type of the offer letter (image/* | application/pdf). */
    @Column(name = "offer_letter_content_type", length = 64)
    private String offerLetterContentType;

    /**
     * Build U — multiple-cheque support. JSON-in-TEXT array of
     * {@code [{"index": 0, "number": "1001", "date": "2026-09-15",
     * "publicId": "agreements/<appId>-cheque-0",
     * "contentType": "image/jpeg", "uploadedAt": "2026-06-10T…"}]}.
     *
     * The legacy single {@code chequePublicId} stays as a fallback
     * (treated as index 0) for rows from before this migration so
     * existing ERM views and the existing fetchChequeBytes path keep
     * working. New uploads always land here via
     * {@code uploadChequeAt(index, …)}.
     *
     * Stored as TEXT to keep the schema portable across MySQL dev and
     * Postgres prod — same convention as {@code payload} above.
     */
    @Column(name = "cheques", columnDefinition = "TEXT")
    private String cheques;

    // ── Build L: 15-day invite expiry ─────────────────────────────────
    //
    // Timestamp of the LAST time the ERM sent the fill invitation
    // (initial create + every resend-invite). The expiry sweep flips
    // a SUBMITTED row to EXPIRED when (now - inviteSentAt) > 15 days
    // -- a stale invite blocks consultant access. REVISION_REQUESTED,
    // VERIFIED, and COMPLETED are intentionally exempt: the consultant
    // has already engaged or the loop is closed.

    @Column(name = "invite_sent_at")
    private LocalDateTime inviteSentAt;

    // ── Build M: two-phase coaching ───────────────────────────────────
    //
    // Phase 1 = pre-employment coaching package; Phase 2 = post-offer
    // support that activates after the consultant accepts a qualifying
    // job. The ERM can ADVANCE a Phase-1 COMPLETED agreement to Phase 2
    // on the SAME document -- all Phase-1 data stays put, previously-
    // skippable sections (Appendix 1, Appendix 4, etc.) flip to
    // required, signatures + affirmations are cleared, and the
    // consultant resubmits.
    //
    // Stored as a tinyint-style smallint default 1 so existing rows
    // backfill cleanly and a future "Phase 3" addition is one bump.

    @Column(name = "phase", nullable = false)
    @Builder.Default
    private Integer phase = 1;

    // ── Build T: consultant-version release (ERM "Approve consultant
    //    version") + Certificate of Completion bookkeeping ───────────
    //
    // The ERM releases a CONSULTANT-VERSION copy of the agreement
    // (consultant signatures only, NO ERM signature) with an appended
    // Certificate of Completion. The bytes are stored against
    // consultantPdfPublicId and the SHA-256 hash on documentHash. The
    // released flag gates the consultant download path.
    //
    // This is INDEPENDENT of the ERM countersign flow. The COMPLETED
    // (ERM-signed) PDF is never served to the consultant — it lives
    // on finalPdfPublicId / finalPdfUrl as before.

    @Column(name = "consultant_copy_released", nullable = false)
    @Builder.Default
    private Boolean consultantCopyReleased = false;

    @Column(name = "consultant_copy_released_at")
    private LocalDateTime consultantCopyReleasedAt;

    @Column(name = "consultant_copy_released_by", length = 36)
    private String consultantCopyReleasedBy;

    @Column(name = "consultant_pdf_public_id", length = 255)
    private String consultantPdfPublicId;

    /** SHA-256 (hex) of the released consultant-version PDF bytes. */
    @Column(name = "document_hash", length = 128)
    private String documentHash;

    // ── Build T: e-sign consent record ───────────────────────────────
    //
    // Captured at the consent gate BEFORE the consultant starts the
    // wizard. consent_version pins the disclosure text that was shown,
    // so a future copy change can be distinguished in audits.

    @Column(name = "consent_given_at")
    private LocalDateTime consentGivenAt;

    @Column(name = "consent_ip", length = 64)
    private String consentIp;

    @Column(name = "consent_version", length = 32)
    private String consentVersion;

    // ── Build U: consultant download accounting ──────────────────────
    //
    // Incremented on every successful consultant-version PDF download
    // (after the OTP exchange). The ERM detail surfaces both: "downloads:
    // N" as a quick stat outside the collapsed activity log, and
    // CONSULTANT_DOWNLOAD events with timestamps + IPs inside the log.

    @Column(name = "consultant_download_count", nullable = false)
    @Builder.Default
    private Integer consultantDownloadCount = 0;

    @Column(name = "consultant_last_downloaded_at")
    private LocalDateTime consultantLastDownloadedAt;

    // ── Status enum (string-keyed; lives here so the service layer
    //    has a single source of truth). ──────────────────────────────

    public enum Status {
        DRAFT,
        SUBMITTED,
        REVISION_REQUESTED,
        UPDATED,
        VERIFIED,
        // 3B — role-based approval gate (inserted between the consultant-
        // signed VERIFIED state and the ERM countersign):
        //   VERIFIED --(ERM Send for Approval)--> AWAITING_APPROVALS
        //   AWAITING_APPROVALS --(all required approvers approve)--> READY_TO_SIGN
        //   AWAITING_APPROVALS --(any approver requests revision)--> APPROVAL_REVISION_REQUESTED
        //   APPROVAL_REVISION_REQUESTED --(ERM re-sends)--> AWAITING_APPROVALS
        //   READY_TO_SIGN --(ERM countersign)--> COMPLETED
        AWAITING_APPROVALS,
        APPROVAL_REVISION_REQUESTED,
        READY_TO_SIGN,
        SIGNED,
        COMPLETED,
        CANCELLED,
        EXPIRED
    }
}
