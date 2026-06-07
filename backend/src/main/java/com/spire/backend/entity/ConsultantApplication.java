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

    @Column(name = "consultant_email", nullable = false, length = 255)
    private String consultantEmail;

    @Column(name = "consultant_name", length = 255)
    private String consultantName;

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

    // ── Consultant-filled: personal (required) ───────────────────────

    @Column(name = "primary_phone") private String primaryPhone;
    @Column(name = "work_authorization_category") private String workAuthorizationCategory;
    @Column(name = "residence_address", columnDefinition = "TEXT") private String residenceAddress;
    @Column(name = "effective_date") private LocalDate effectiveDate;

    // ── Exhibit A (scope of engagement) ──────────────────────────────

    @Column(name = "technology_track") private String technologyTrack;
    @Column(name = "custom_scope_notes", columnDefinition = "TEXT") private String customScopeNotes;

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
    @Column(name = "ach_debit_dates") private String achDebitDates;
    @Column(name = "ach_debit_amounts") private String achDebitAmounts;

    // ── Appendix 3: background check (sensitive PII) ─────────────────

    @Column(name = "bg_full_legal_name") private String bgFullLegalName;
    @Column(name = "bg_other_names_used") private String bgOtherNamesUsed;
    @Column(name = "bg_current_address", columnDefinition = "TEXT") private String bgCurrentAddress;
    @Column(name = "bg_date_of_birth") private LocalDate bgDateOfBirth;
    @Column(name = "bg_full_ssn", columnDefinition = "TEXT") private String bgFullSsn;
    @Column(name = "bg_driver_license", columnDefinition = "TEXT") private String bgDriverLicense;

    // ── Appendix 4: portal access (optional) ─────────────────────────

    @Column(name = "portal_platform") private String portalPlatform;
    @Column(name = "portal_username") private String portalUsername;
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
    @Column(name = "signature_date") private LocalDateTime signatureDate;

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

    // ── Status enum (string-keyed; lives here so the service layer
    //    has a single source of truth). ──────────────────────────────

    public enum Status {
        DRAFT,
        SUBMITTED,
        REVISION_REQUESTED,
        UPDATED,
        VERIFIED,
        SIGNED,
        COMPLETED,
        CANCELLED,
        EXPIRED
    }
}
