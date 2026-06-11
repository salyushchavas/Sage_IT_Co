/**
 * Authoritative ordered list of signing sections for the guided
 * consultant-agreement wizard. Pure data + types only -- no JSX. The
 * wizard UI (next phase) renders directly from this module, and a
 * shared validator can read {@link getRequiredFieldKeys} +
 * {@link AFFIRMATION_FLAGS} to mirror the backend's all-required gate
 * client-side before the consultant clicks submit.
 *
 * IMPORTANT FIELD-NAMING RULE: every {@link SectionField.key} is the
 * EXACT entity column name (camelCase) on
 * {@code com.spire.backend.entity.ConsultantApplication}. The wizard
 * reads + writes against the API using these keys verbatim. Friendly
 * labels live on {@code label}/{@code help} only. The mapping survey
 * (see commit body) catalogues every rename from spec-suggested keys
 * to the real columns.
 *
 * The plain-language {@code summary} + {@code why} copy is a GUIDE.
 * The binding agreement is the generated PDF; nothing here changes
 * legal meaning.
 */

export type FieldType =
  | "text"
  | "email"
  | "tel"
  | "select"
  | "textarea"
  | "date"
  | "ssn"
  | "routing"
  | "account"
  // Build G: a 2-button toggle for "Driver's License" vs "State ID"
  // sitting next to the ID-number input in Appendix 3.
  | "id-type"
  // Build G: file upload tile (image or PDF) for the Appendix 5
  // security cheque. Bytes upload via a separate endpoint; the
  // wizard tracks "uploaded ✓" state, not a value string.
  | "file";

export interface SectionField {
  /**
   * Entity column name (camelCase). The wizard sends this verbatim in
   * the PUT /fill body and reads it back from the GET application
   * response.
   */
  key: string;
  label: string;
  type: FieldType;
  /**
   * Whether the field is intrinsically required at submit time. With
   * F-4, "intrinsic" means: required IF the owning section is being
   * completed (always for CORE sections; conditionally for appendices
   * via {@code effectiveRequirements}). A {@code required: false} field
   * is never required even when the section is required -- e.g.
   * implementationPartner sits inside Appendix 1 but the ERM has
   * said this consultant doesn't need it.
   */
  required: boolean;
  /** For {@code type: "select"} -- option strings. */
  options?: readonly string[];
  /** Mark fields the UI should mask by default (SSN, routing, account). */
  sensitive?: boolean;
  placeholder?: string;
  help?: string;
  /** Some fields (e.g. consultantEmail, workAuthorizationCategory) are
   *  seeded by the ERM and stay read-only in the consultant's view;
   *  flagged so the wizard renders them as confirmations rather than
   *  inputs. */
  readOnly?: boolean;
}

export type AppendixKey =
  | "appendix1"
  | "appendix2"
  | "appendix3"
  | "appendix4"
  | "appendix5";

export interface AgreementSection {
  /** Stable id, also used as the route segment in the wizard. */
  id: string;
  /** 1-based ordinal in the flow (handy for "Step 4 of 10" copy). */
  step: number;
  title: string;
  /** Plain-language: what this section is. */
  summary: string;
  /** "Why we need this." */
  why: string;
  /** Empty array for read-only sections (main agreement, exhibit B, review). */
  fields: readonly SectionField[];
  /**
   * Set on the two real signature-draw steps:
   *   - main-agreement (step 2): the primary signature, reused as
   *     $signatureImage across every intermediate signature block in
   *     the generated PDF.
   *   - review (final step): the final execution signature, stamps
   *     the closing/execution block as $finalSignatureImage.
   * F-4 first-and-last model -- everything between is an affirmation,
   * not a signature redraw.
   */
  requiresSignature: boolean;
  /** TRUE on every step with a section affirmation checkbox. */
  requiresAffirmation: boolean;
  /** Entity column name of the boolean flag this affirmation sets. */
  affirmationFlag?:
    | "affirmedMainAgreement"
    | "affirmedExhibitA"
    | "affirmedExhibitB"
    | "affirmedAppendix1"
    | "affirmedAppendix2"
    | "affirmedAppendix3"
    | "affirmedAppendix4"
    | "affirmedAppendix5";
  /**
   * For the five appendix sections, the {@link AppendixKey} the ERM's
   * require_appendixN flag uses to mark the section required or
   * optional for THIS consultant. Absent on every non-appendix section
   * (cover, exhibits, signing steps, review).
   */
  appendixKey?: AppendixKey;
}

// ── Option lists ────────────────────────────────────────────────

// Build W — the eight ERM-selectable work-authorization categories.
// "Others" reveals a required free-text field on the ERM create form;
// the custom value is what renders in the agreement/PDF.
export const WORK_AUTHORIZATION_OPTIONS = [
  "H1B",
  "OPT-EAD",
  "STEM-OPT-EAD",
  "H4-EAD",
  "GC",
  "Citizen",
  "EAD",
  "Others",
] as const;

// Build W — US states + DC + territories for the structured billing
// address state dropdown (two-letter USPS codes).
export const US_STATE_OPTIONS = [
  "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA",
  "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA",
  "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY",
  "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX",
  "UT", "VT", "VA", "WA", "WV", "WI", "WY", "PR", "VI", "GU", "AS", "MP",
] as const;

const PAYROLL_CYCLE_OPTIONS = [
  "Weekly",
  "Bi-weekly",
  "Semi-monthly",
  "Monthly",
] as const;

const ACH_ACCOUNT_TYPE_OPTIONS = ["Checking", "Savings"] as const;

// ── Sections (ordered) ──────────────────────────────────────────

export const AGREEMENT_SECTIONS: readonly AgreementSection[] = [
  {
    id: "cover",
    step: 1,
    title: "Your Information",
    summary:
      "A quick read-back of your name, contact details, and work-authorization status so we can address the agreement to you accurately.",
    why: "Confirms who you are, how to reach you, and your work-authorization status — needed for the agreement and any verification.",
    fields: [
      // Build W — structured name (First + Middle? + Last). Composed
      // into the full legal name on the contract.
      {
        key: "firstName",
        label: "First name",
        type: "text",
        required: true,
        help: "Prefilled from your invitation; correct it here if the spelling is off.",
      },
      {
        key: "middleName",
        label: "Middle name (optional)",
        type: "text",
        required: false,
      },
      {
        key: "lastName",
        label: "Last name",
        type: "text",
        required: true,
      },
      {
        key: "consultantEmail",
        label: "Primary email",
        type: "email",
        required: true,
        readOnly: true,
        help: "Prefilled from your invitation. To change this, ask your Sage IT contact.",
      },
      {
        key: "primaryPhone",
        label: "Primary phone",
        type: "tel",
        required: true,
        placeholder: "+1 555 555 5555",
      },
      {
        key: "workAuthorizationCategory",
        label: "Work authorization",
        type: "select",
        required: true,
        options: WORK_AUTHORIZATION_OPTIONS,
        readOnly: true,
        help: "Set by Sage IT when they sent this agreement. To change it, ask your Sage IT contact.",
      },
      // Build W — structured US billing address (Line 1, Line 2,
      // City, State, ZIP). Assembled into the agreement/PDF.
      {
        key: "addressLine1",
        label: "Address line 1",
        type: "text",
        required: true,
        placeholder: "123 Main St",
      },
      {
        key: "addressLine2",
        label: "Address line 2 (optional)",
        type: "text",
        required: false,
        placeholder: "Apt, suite, unit",
      },
      {
        key: "addressCity",
        label: "City",
        type: "text",
        required: true,
        placeholder: "Austin",
      },
      {
        key: "addressState",
        label: "State",
        type: "select",
        required: true,
        options: US_STATE_OPTIONS,
      },
      {
        key: "addressZip",
        label: "ZIP code",
        type: "text",
        required: true,
        placeholder: "78701 or 78701-1234",
      },
      // Build I — work-authorization supporting document, sitting with the
      // (ERM-set) Work Authorization status. Required for EVERY work-auth
      // type. Upload via a dedicated endpoint; tracks "uploaded ✓" state.
      {
        key: "workAuthDoc",
        label: "Work-authorization document",
        type: "file",
        required: true,
        help: "Upload a copy of your current work-authorization document (image or PDF).",
      },
    ],
    requiresSignature: false,
    requiresAffirmation: false,
  },

  {
    id: "main-agreement",
    step: 2,
    title: "The Agreement",
    summary:
      "The two-phase model in plain terms. Phase 1 is the pre-employment coaching package Sage IT provides while you prepare for the job market. Phase 2 is the post-offer support that activates only after you accept a qualifying job and confirms the details in Appendix 1.",
    why: "This is the core agreement you're signing. Read it, then sign once here — your signature is reused on the later sections so you won't re-draw it.",
    fields: [],
    requiresSignature: true,
    requiresAffirmation: true,
    affirmationFlag: "affirmedMainAgreement",
  },

  {
    id: "exhibit-a",
    step: 3,
    title: "Exhibit A — Phase 1 Service & Acknowledgments",
    summary:
      "Records the technology / skill track Sage IT will coach you on, plus any custom scope notes. You also confirm you understand the Phase 1 services described in the main agreement.",
    why: "Records your skill track and confirms you understand the Phase 1 services and key acknowledgments.",
    fields: [
      // Build I — Service Track is now ERM-set at create and locked;
      // the consultant sees both read-only (Custom Scope may be blank).
      {
        key: "technologyTrack",
        label: "Technology / skill track",
        type: "text",
        required: false,
        readOnly: true,
        help: "Set by Sage IT on this agreement.",
      },
      {
        key: "customScopeNotes",
        label: "Custom scope or notes",
        type: "textarea",
        required: false,
        readOnly: true,
        help: "Set by Sage IT on this agreement.",
      },
    ],
    requiresSignature: false,
    requiresAffirmation: true,
    affirmationFlag: "affirmedExhibitA",
  },

  {
    id: "exhibit-b",
    step: 4,
    title: "Exhibit B — Commercial Terms",
    summary:
      "Explains the $5,000 Phase 1 coaching charge, the conditions under which it stays waived, and how billing works if you transition in good faith.",
    why: "Explains the $5,000 Phase 1 coaching charge and how it stays waived if you transition in good faith. No payment is due now.",
    fields: [],
    requiresSignature: false,
    requiresAffirmation: true,
    affirmationFlag: "affirmedExhibitB",
  },

  {
    id: "appendix1",
    step: 5,
    title: "Appendix 1 — Employment Confirmation",
    summary:
      "Once you've accepted a job, this confirms the employer of record, any implementation partner / end client, your role, start date, and payroll cycle. These details activate Phase 2.",
    why: "Once you've accepted a job, this confirms the employer, role, start date, and pay cycle that activate Phase 2.",
    fields: [
      {
        key: "employerPayrollEntity",
        label: "Employer (payroll entity)",
        type: "text",
        required: true,
      },
      {
        key: "implementationPartner",
        label: "Implementation partner",
        type: "text",
        // F-4: implementation partner is NEVER required, even when
        // Appendix 1 is required for this consultant. Leave blank if
        // there's no implementation partner involved.
        required: false,
        help: "Leave blank if there's no implementation partner. Otherwise enter the firm name.",
      },
      {
        key: "endClient",
        label: "End client",
        type: "text",
        required: true,
        help: "Enter 'N/A' if none.",
      },
      {
        key: "roleTitle",
        label: "Role / position",
        type: "text",
        required: true,
      },
      {
        key: "verifiedStartDate",
        label: "Verified start date",
        type: "date",
        required: true,
      },
      {
        key: "payrollCycle",
        label: "Payroll cycle",
        type: "select",
        required: true,
        options: PAYROLL_CYCLE_OPTIONS,
      },
      // Build I — required Offer Letter upload (image/PDF) after the
      // Phase 2 Employment fields. Tracks "uploaded ✓" state, not a value.
      {
        key: "offerLetter",
        label: "Offer letter",
        type: "file",
        required: true,
        help: "Upload your offer letter (image or PDF) once you've accepted the role.",
      },
    ],
    requiresSignature: false,
    requiresAffirmation: true,
    affirmationFlag: "affirmedAppendix1",
    appendixKey: "appendix1",
  },

  {
    id: "appendix2",
    step: 6,
    title: "Appendix 2 — ACH Payment Authorization",
    summary:
      "Authorizes Sage IT to initiate ACH debits to the account you provide, only for amounts properly invoiced and matured under this agreement. You'll receive advance notice at the email you list.",
    why: "Authorizes ACH payment for amounts properly invoiced under the agreement.",
    fields: [
      {
        key: "achAccountType",
        label: "Account type",
        type: "select",
        required: true,
        options: ACH_ACCOUNT_TYPE_OPTIONS,
      },
      {
        key: "achBankName",
        label: "Bank name",
        type: "text",
        required: true,
      },
      {
        key: "achAccountHolderName",
        label: "Account holder name",
        type: "text",
        required: true,
      },
      {
        key: "achRoutingNumber",
        label: "Routing number",
        type: "routing",
        required: true,
        sensitive: true,
      },
      {
        key: "achAccountNumber",
        label: "Account number",
        type: "account",
        required: true,
        sensitive: true,
      },
      {
        key: "achNoticeEmail",
        label: "Email for advance notice",
        type: "email",
        required: true,
      },
      // Build Y — the debit schedule is now ERM-filled at create (a
      // repeatable date/amount schedule); read-only to the consultant.
      {
        key: "achDebitDates",
        label: "Debit date(s)",
        type: "text",
        required: false,
        readOnly: true,
        help: "Set by Sage IT on this agreement.",
      },
      {
        key: "achDebitAmounts",
        label: "Debit amount(s)",
        type: "text",
        required: false,
        readOnly: true,
        help: "Set by Sage IT on this agreement.",
      },
    ],
    requiresSignature: false,
    requiresAffirmation: true,
    affirmationFlag: "affirmedAppendix2",
    appendixKey: "appendix2",
  },

  {
    id: "appendix3",
    step: 7,
    title: "Appendix 3 — Background Check Authorization",
    summary:
      "Authorizes a background screening for employment-related purposes. Sensitive fields (SSN, date of birth, driver's license) are masked in our console and stored encrypted at rest.",
    why: "Authorizes background screening for employment-related purposes.",
    fields: [
      {
        key: "bgFullLegalName",
        label: "Full legal name (as on government ID)",
        type: "text",
        required: true,
      },
      {
        key: "bgOtherNamesUsed",
        label: "Other names used",
        type: "text",
        required: true,
        help: "Maiden name, nicknames, or aliases. Enter 'N/A' if none.",
      },
      // Build J — structured current address (mirrors residence address).
      // The "Same as residence address" toggle is rendered above these
      // in the wizard (copies + locks the residence values).
      {
        key: "bgCurrentAddressLine1",
        label: "Address line 1",
        type: "text",
        required: true,
        placeholder: "Street address",
      },
      {
        key: "bgCurrentAddressLine2",
        label: "Address line 2",
        type: "text",
        required: false,
        placeholder: "Apt, suite, unit (optional)",
      },
      {
        key: "bgCurrentAddressCity",
        label: "City",
        type: "text",
        required: true,
      },
      {
        key: "bgCurrentAddressState",
        label: "State",
        type: "select",
        required: true,
        options: US_STATE_OPTIONS,
      },
      {
        key: "bgCurrentAddressZip",
        label: "ZIP code",
        type: "text",
        required: true,
        placeholder: "78701 or 78701-1234",
      },
      {
        key: "bgDateOfBirth",
        label: "Date of birth",
        type: "date",
        required: true,
        sensitive: true,
      },
      {
        // Build W — SSN is now strictly alphanumeric (letters + digits,
        // no hyphens/spaces/symbols). The "ssn" field type is retained
        // but its mask/validation are alphanumeric (see fill page).
        key: "bgFullSsn",
        label: "Social Security Number",
        type: "ssn",
        required: true,
        sensitive: true,
        placeholder: "Letters and numbers only",
      },
      {
        key: "idType",
        label: "ID type",
        type: "id-type",
        required: true,
      },
      {
        key: "bgDriverLicense",
        label: "Driver's License / State ID number",
        type: "text",
        required: true,
        sensitive: true,
        help: "Include the issuing state.",
      },
      // Build J — DL/State-ID document upload (required) sits with the ID
      // number; SSN document upload (optional) sits with the SSN.
      {
        key: "dlDoc",
        label: "Driver's License / State ID document",
        type: "file",
        required: true,
        help: "Upload a copy of your Driver's License or State ID (image or PDF).",
      },
      {
        key: "ssnDoc",
        label: "SSN document (optional)",
        type: "file",
        required: false,
        help: "Optional — upload a copy of your SSN card or document if you have one (image or PDF). You can submit without it.",
      },
    ],
    requiresSignature: false,
    requiresAffirmation: true,
    affirmationFlag: "affirmedAppendix3",
    appendixKey: "appendix3",
  },

  {
    id: "appendix4",
    step: 8,
    title: "Appendix 4 — Limited Portal & Account Access",
    summary:
      "Grants Sage IT limited, revocable access to specified job-search portals so we can help you navigate listings and applications. Scope of access is defined by you, and you can revoke it at any time.",
    why: "Authorizes limited, revocable access to specified job-search portals for navigation support.",
    fields: [
      // Build J — platform + username are now a REPEATABLE list, rendered by
      // a dedicated add/remove block in the wizard (PortalEntriesBlock), not
      // as single fields here. The remaining fields stay section-level.
      {
        key: "portalAuthorizedActions",
        label: "Authorized actions",
        type: "textarea",
        required: true,
        help: "Describe what we may do on your behalf (search, apply, message recruiters).",
      },
      {
        key: "portalEffectiveDate",
        label: "Access effective date",
        type: "date",
        required: true,
      },
      {
        key: "portalRevocationContact",
        label: "Revocation contact",
        type: "text",
        required: true,
        help: "Who we should notify if you revoke access (usually your Sage IT contact).",
      },
    ],
    requiresSignature: false,
    requiresAffirmation: true,
    affirmationFlag: "affirmedAppendix4",
    appendixKey: "appendix4",
  },

  {
    id: "appendix5",
    step: 9,
    title: "Appendix 5 — Security Cheque Acknowledgment",
    summary:
      "Acknowledges any post-dated security cheque(s) you've provided. Sage IT will hold them solely as limited security for matured, undisputed amounts under this agreement.",
    why: "Acknowledges any payment-security cheque(s) held solely as limited security for matured, undisputed amounts.",
    fields: [
      {
        key: "securityCheckCount",
        label: "Number of cheques",
        type: "text",
        required: true,
        placeholder: "e.g. 2",
      },
      {
        key: "securityCheckBank",
        label: "Issuing bank",
        type: "text",
        required: true,
      },
      {
        key: "securityCheckHolderName",
        label: "Account holder name",
        type: "text",
        required: true,
      },
      {
        key: "securityCheckAmount",
        label: "Amount secured",
        type: "text",
        required: true,
        placeholder: "e.g. $5,000",
      },
      // Build U — Per-cheque number + upload pairs are rendered by
      // ChequeListBlock (one input group per cheque, driven by
      // securityCheckCount). The legacy securityCheckNumbers /
      // securityCheckDates / single chequeUpload fields are no longer
      // surfaced — chequeListReplaced gates them out of the section
      // completeness check.
    ],
    requiresSignature: false,
    requiresAffirmation: true,
    affirmationFlag: "affirmedAppendix5",
    appendixKey: "appendix5",
  },

  {
    id: "review",
    step: 10,
    title: "Review & Submit",
    summary:
      "A full read-back of everything you entered and every section you affirmed. Sign once more here to execute the agreement, then submit.",
    why: "Final confirmation + execution signature before submission — once submitted, your application moves to ERM review.",
    fields: [],
    // F-4 first-and-last: the review step captures the final
    // execution signature (separate draw, stamps the closing block as
    // $finalSignatureImage).
    requiresSignature: true,
    requiresAffirmation: false,
  },
] as const;

// ── Helpers ────────────────────────────────────────────────────

/**
 * The eight affirmation flag column names (entity field names). The
 * wizard ticks one per section as the consultant affirms; the backend
 * requires all eight to be true at submit time.
 */
export const AFFIRMATION_FLAGS = [
  "affirmedMainAgreement",
  "affirmedExhibitA",
  "affirmedExhibitB",
  "affirmedAppendix1",
  "affirmedAppendix2",
  "affirmedAppendix3",
  "affirmedAppendix4",
  "affirmedAppendix5",
] as const;

export type AffirmationFlag = (typeof AFFIRMATION_FLAGS)[number];

/**
 * Every required field key across every section, INDEPENDENT of any
 * per-agreement flags. Kept for backward compat / debugging. Real
 * submit gating uses {@link effectiveRequirements} on a given app.
 */
export function getRequiredFieldKeys(): string[] {
  const keys: string[] = [];
  for (const section of AGREEMENT_SECTIONS) {
    for (const field of section.fields) {
      if (field.readOnly) continue;
      if (!field.required) continue;
      keys.push(field.key);
    }
  }
  return keys;
}

/** Quick lookup -- the section that owns the given field key, if any. */
export function findSectionForFieldKey(
  fieldKey: string,
): AgreementSection | undefined {
  return AGREEMENT_SECTIONS.find((s) =>
    s.fields.some((f) => f.key === fieldKey),
  );
}

/** Quick lookup -- the section that owns the given affirmation flag. */
export function findSectionForAffirmation(
  flag: AffirmationFlag,
): AgreementSection | undefined {
  return AGREEMENT_SECTIONS.find((s) => s.affirmationFlag === flag);
}

// ── F-4 effective-requirements ──────────────────────────────────

/**
 * The fields of an application the requirement model needs to read.
 * Kept as a structural subset so call sites can pass an
 * {@code ConsultantApplication} from the API without an extra mapping.
 */
export interface RequirementsInput {
  requireAppendix1?: boolean | null;
  requireAppendix2?: boolean | null;
  requireAppendix3?: boolean | null;
  requireAppendix4?: boolean | null;
  requireAppendix5?: boolean | null;
  requireSsn?: boolean | null;
}

export interface EffectiveRequirements {
  /** True iff the corresponding appendix is required for this consultant. */
  appendix1: boolean;
  appendix2: boolean;
  appendix3: boolean;
  appendix4: boolean;
  appendix5: boolean;
  /** True iff SSN inside Appendix 3 must be supplied when Appendix 3 is being completed. */
  ssn: boolean;
}

/**
 * Resolves an application's per-agreement requirement flags into the
 * effective required/optional shape the wizard + client-side validator
 * read from. Mirrors the backend's effective-requirements model so the
 * UX matches what the server will accept at submit time.
 */
export function effectiveRequirements(
  app: RequirementsInput | null | undefined,
): EffectiveRequirements {
  return {
    appendix1: app?.requireAppendix1 === true,
    appendix2: app?.requireAppendix2 === true,
    appendix3: app?.requireAppendix3 === true,
    appendix4: app?.requireAppendix4 === true,
    appendix5: app?.requireAppendix5 === true,
    ssn: app?.requireSsn === true,
  };
}

/**
 * True iff {@code section} is one of the five appendices AND it's
 * required for this consultant per {@link effectiveRequirements}. For
 * non-appendix sections (cover, exhibits, main agreement, review) the
 * caller should treat them as always-required CORE and ignore this.
 */
export function isAppendixRequired(
  section: AgreementSection,
  reqs: EffectiveRequirements,
): boolean {
  if (!section.appendixKey) return false;
  return reqs[section.appendixKey];
}
