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
  | "account";

export interface SectionField {
  /**
   * Entity column name (camelCase). The wizard sends this verbatim in
   * the PUT /fill body and reads it back from the GET application
   * response.
   */
  key: string;
  label: string;
  type: FieldType;
  /** All fields in this config are required at submit time. */
  required: true;
  /** For {@code type: "select"} -- option strings. */
  options?: readonly string[];
  /** Mark fields the UI should mask by default (SSN, routing, account). */
  sensitive?: boolean;
  placeholder?: string;
  help?: string;
  /** Some fields (e.g. consultantEmail) are seeded by the ERM and stay
   *  read-only in the consultant's view; flagged so the wizard renders
   *  them as confirmations rather than inputs. */
  readOnly?: boolean;
}

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
   * TRUE only on the first signing step where the consultant draws
   * their signature. The wizard reuses the captured image across every
   * downstream signature block via the existing $signatureImage
   * stamping in the generated PDF.
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
}

// ── Option lists ────────────────────────────────────────────────

const WORK_AUTHORIZATION_OPTIONS = [
  "F-1 STEM OPT",
  "F-1 OPT",
  "H-1B",
  "Green Card",
  "U.S. Citizen",
  "Other",
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
      {
        key: "consultantName",
        label: "Full legal name",
        type: "text",
        required: true,
        help: "Prefilled from your invitation; correct it here if the spelling is off.",
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
      },
      {
        key: "residenceAddress",
        label: "Residence address",
        type: "textarea",
        required: true,
        help: "One block — street, unit, city, state, ZIP, country.",
        placeholder:
          "123 Main St, Apt 2\nAustin, TX 78701\nUnited States",
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
      {
        key: "technologyTrack",
        label: "Technology / skill track",
        type: "text",
        required: true,
        placeholder: "e.g. ServiceNow, Salesforce, Data Analytics",
      },
      {
        key: "customScopeNotes",
        label: "Custom scope or notes",
        type: "textarea",
        required: true,
        help: "Any custom scope notes specific to your engagement. Enter 'N/A' if none.",
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
        required: true,
        help: "Enter 'N/A' if none.",
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
    ],
    requiresSignature: false,
    requiresAffirmation: true,
    affirmationFlag: "affirmedAppendix1",
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
      {
        key: "achDebitDates",
        label: "Debit dates",
        type: "text",
        required: true,
        placeholder: "e.g. 15th of each month",
      },
      {
        key: "achDebitAmounts",
        label: "Debit amounts",
        type: "text",
        required: true,
        placeholder: "e.g. $416.67",
      },
    ],
    requiresSignature: false,
    requiresAffirmation: true,
    affirmationFlag: "affirmedAppendix2",
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
      {
        key: "bgCurrentAddress",
        label: "Current address",
        type: "textarea",
        required: true,
      },
      {
        key: "bgDateOfBirth",
        label: "Date of birth",
        type: "date",
        required: true,
        sensitive: true,
      },
      {
        key: "bgFullSsn",
        label: "Social Security Number",
        type: "ssn",
        required: true,
        sensitive: true,
        placeholder: "XXX-XX-XXXX",
      },
      {
        key: "bgDriverLicense",
        label: "Driver's license number",
        type: "text",
        required: true,
        sensitive: true,
        help: "Include the issuing state.",
      },
    ],
    requiresSignature: false,
    requiresAffirmation: true,
    affirmationFlag: "affirmedAppendix3",
  },

  {
    id: "appendix4",
    step: 8,
    title: "Appendix 4 — Limited Portal & Account Access",
    summary:
      "Grants Sage IT limited, revocable access to specified job-search portals so we can help you navigate listings and applications. Scope of access is defined by you, and you can revoke it at any time.",
    why: "Authorizes limited, revocable access to specified job-search portals for navigation support.",
    fields: [
      {
        key: "portalPlatform",
        label: "Authorized platform",
        type: "text",
        required: true,
        placeholder: "e.g. LinkedIn Recruiter, Dice, Indeed",
      },
      {
        key: "portalUsername",
        label: "Username / login ID",
        type: "text",
        required: true,
      },
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
  },

  {
    id: "appendix5",
    step: 9,
    title: "Appendix 5 — Security Check Acknowledgment",
    summary:
      "Acknowledges any post-dated security check(s) you've provided. Sage IT will hold them solely as limited security for matured, undisputed amounts under this agreement.",
    why: "Acknowledges any payment-security check(s) held solely as limited security for matured, undisputed amounts.",
    fields: [
      {
        key: "securityCheckCount",
        label: "Number of checks",
        type: "text",
        required: true,
        placeholder: "e.g. 2",
      },
      {
        key: "securityCheckNumbers",
        label: "Check numbers",
        type: "text",
        required: true,
        sensitive: true,
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
      {
        key: "securityCheckDates",
        label: "Check date(s)",
        type: "text",
        required: true,
      },
    ],
    requiresSignature: false,
    requiresAffirmation: true,
    affirmationFlag: "affirmedAppendix5",
  },

  {
    id: "review",
    step: 10,
    title: "Review & Submit",
    summary:
      "A full read-back of everything you entered, every section you affirmed, and the signature you drew. Submit only after the read-back matches what you intended.",
    why: "Final confirmation before submission — once submitted, your application moves to ERM review.",
    fields: [],
    requiresSignature: false,
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
 * Every required field key across every section. The wizard uses this
 * to build a one-pass "is the whole form complete?" check against its
 * local state, mirroring the backend's submit-time gate. Excludes
 * read-only confirmation fields (e.g. consultantEmail) since the
 * consultant can't blank them.
 */
export function getRequiredFieldKeys(): string[] {
  const keys: string[] = [];
  for (const section of AGREEMENT_SECTIONS) {
    for (const field of section.fields) {
      if (field.readOnly) continue;
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
