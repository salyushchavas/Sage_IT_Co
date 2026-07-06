"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from "react";
import { useParams, useRouter } from "next/navigation";
import {
  AlertCircle,
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  ChevronDown,
  Eye,
  EyeOff,
  FileText,
  Loader2,
  Lock,
  PauseCircle,
  Plus,
  ShieldCheck,
  Sparkles,
  Trash2,
  X,
} from "lucide-react";

import SignaturePad from "@/components/common/SignaturePad";
import AgreementClauseView from "@/components/consultant/AgreementClauseView";
import { wizardFontClass } from "./fonts";
import {
  AGREEMENT_SECTIONS,
  AFFIRMATION_FLAGS,
  effectiveRequirements,
  findSectionForAffirmation,
  findSectionForFieldKey,
  type AffirmationFlag,
  type AgreementSection,
  type EffectiveRequirements,
  type SectionField,
} from "@/lib/agreement-sections";
import {
  fetchAgreementContent,
  fetchAgreementTemplatePdfBlob,
  fetchConsultantPreviewImages,
  getConsultantApplicationView,
  getConsultantToken,
  parseChequeList,
  parseRevisionSections,
  recordConsultantConsent,
  saveConsultantChequeMetadata,
  saveConsultantFill,
  signConsultantApplication,
  uploadConsultantChequeAt,
  uploadConsultantWorkAuthDoc,
  uploadConsultantOfferLetter,
  uploadConsultantDlDoc,
  uploadConsultantSsnDoc,
  parsePortalEntries,
  type PortalEntry,
  type AgreementContent,
  type ChequeEntry,
  type ConsultantApplication,
  type ConsultantFillPayload,
} from "@/lib/api";
import { formatUsDate } from "@/lib/dates";

/**
 * Guided, document-paired signing wizard. Iterates the F-1 section
 * config; every section gets a read pane (plain-language summary +
 * "why we need this" + a "View full agreement" button) and a fields/
 * affirmation pane. The signature is drawn ONCE on the main-agreement
 * step and reused for every downstream signature block via the
 * existing $signatureImage stamping. Auto-save mirrors the Phase 5
 * pattern: 1500ms debounce, AbortController, 429 -> 30s pause.
 *
 * Adding or reordering a section means editing agreement-sections.ts
 * ONLY; this page does not enumerate sections by name anywhere.
 */

// ── State + types ─────────────────────────────────────────────

type SaveStatus =
  | { kind: "idle" }
  | { kind: "saving" }
  | { kind: "saved"; at: number }
  | { kind: "paused" }
  | { kind: "error"; message: string };

interface FormState {
  fields: Record<string, string>;
  affirmations: Record<AffirmationFlag, boolean>;
  /** Primary signature drawn on the main-agreement step (F-4 "first"). */
  signature: string | null;
  /** Final signature drawn on the review step (F-4 "last"). Client-only
   *  until submit -- never autosaved, since both signatures are uploaded
   *  to Cloudinary atomically as part of the submit call. */
  finalSignature: string | null;
  signedLegalName: string;
}

const ALL_FIELD_KEYS: readonly string[] = Array.from(
  new Set(
    AGREEMENT_SECTIONS.flatMap((s) =>
      s.fields.map((f) => f.key),
    ),
  ),
);

// Build J — persisted keys that aren't standard section fields:
//   portalEntries            — JSON list of {platform, username} (appendix4)
//   bgCurrentSameAsResidence — boolean toggle (appendix3)
// They live in form.fields as strings and round-trip through the same
// auto-save delta (the boolean is coerced on the wire — see computeDelta).
const EXTRA_PERSISTED_KEYS = ["portalEntries", "bgCurrentSameAsResidence"] as const;

// Build J — the structured Background Check current-address fields that get
// copied + locked when "Same as residence address" is checked.
const CURRENT_ADDRESS_KEYS = new Set([
  "bgCurrentAddressLine1",
  "bgCurrentAddressLine2",
  "bgCurrentAddressCity",
  "bgCurrentAddressState",
  "bgCurrentAddressZip",
]);

// Build J — current-address field → the residence field it mirrors when
// "Same as residence" is on (used for live display + validation skip).
const RESIDENCE_KEY_FOR: Record<string, string> = {
  bgCurrentAddressLine1: "addressLine1",
  bgCurrentAddressLine2: "addressLine2",
  bgCurrentAddressCity: "addressCity",
  bgCurrentAddressState: "addressState",
  bgCurrentAddressZip: "addressZip",
};
const PERSISTED_FIELD_KEYS: readonly string[] = [
  ...ALL_FIELD_KEYS,
  ...EXTRA_PERSISTED_KEYS,
];

/** Section id that owns an extra persisted key (for revision-scope filtering). */
function sectionIdForPersistedKey(key: string): string | undefined {
  if (key === "portalEntries") return "appendix4";
  if (key === "bgCurrentSameAsResidence") return "appendix3";
  return findSectionForFieldKey(key)?.id;
}

function emptyAffirmations(): Record<AffirmationFlag, boolean> {
  const out = {} as Record<AffirmationFlag, boolean>;
  for (const flag of AFFIRMATION_FLAGS) out[flag] = false;
  return out;
}

function buildInitialState(app: ConsultantApplication | null): FormState {
  const fields: Record<string, string> = {};
  for (const key of PERSISTED_FIELD_KEYS) {
    let v = app?.[key as keyof ConsultantApplication];
    // Build J — migrate pre-Build-J portal data: if there are no JSON
    // entries yet but the legacy single platform/username pair exists,
    // seed one entry so the consultant sees + can edit it (and it isn't
    // lost on the next save).
    if (key === "portalEntries" && (v == null || !String(v).trim())) {
      const platform = (app?.portalPlatform ?? "").trim();
      const username = (app?.portalUsername ?? "").trim();
      if (platform || username) {
        v = JSON.stringify([{ platform, username }]);
      }
    }
    fields[key] = v == null ? "" : String(v);
  }
  const affirmations = emptyAffirmations();
  if (app) {
    for (const flag of AFFIRMATION_FLAGS) {
      affirmations[flag] = Boolean(app[flag as keyof ConsultantApplication]);
    }
  }
  return {
    fields,
    affirmations,
    signature: app?.signatureImage ?? null,
    finalSignature: app?.finalSignatureImage ?? null,
    signedLegalName:
      app?.signedLegalName?.trim() || app?.consultantName?.trim() || "",
  };
}

// ── Section completeness ──────────────────────────────────────

function isFieldValueValid(field: SectionField, value: string): boolean {
  const trimmed = value.trim();
  if (trimmed.length === 0) return false;
  if (field.type === "email") {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed);
  }
  // Build G strict formats. Matched against the digit-only form for
  // routing/account (the input auto-strips non-digits) and against the
  // dashed form for SSN (auto-formatted as the consultant types).
  if (field.type === "routing") {
    return /^\d{9}$/.test(trimmed.replace(/\D/g, ""));
  }
  if (field.type === "account") {
    return /^\d{10}$/.test(trimmed.replace(/\D/g, ""));
  }
  if (field.type === "ssn") {
    // Build W — SSN is strictly alphanumeric (A-Z, a-z, 0-9 only).
    return /^[A-Za-z0-9]+$/.test(trimmed);
  }
  if (field.type === "id-type") {
    return trimmed === "DL" || trimmed === "STATE_ID";
  }
  // Build W/J — US ZIP: 5 digits, optionally ZIP+4 (residence + current).
  if (field.key === "addressZip" || field.key === "bgCurrentAddressZip") {
    return /^\d{5}(-\d{4})?$/.test(trimmed);
  }
  return true;
}

/** Build W — formatUsDate now lives in @/lib/dates (single source). */

/** Build G — digit-only auto-mask for routing/account inputs. */
function digitsOnly(raw: string, maxLen: number): string {
  return raw.replace(/\D/g, "").slice(0, maxLen);
}

// ── Build N — MM-DD-YYYY date mask (stores ISO yyyy-MM-dd) ──────
// Date fields store ISO internally; the input shows/edits MM-DD-YYYY so
// the display is US-format regardless of browser locale (replaces native
// <input type="date">). Conversions are positional re-formats — never a
// day/month swap.
function isoToUsInput(iso: string | null | undefined): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso ?? "").trim());
  return m ? `${m[2]}-${m[3]}-${m[1]}` : "";
}
function maskMmDdYyyy(raw: string): string {
  const d = raw.replace(/\D/g, "").slice(0, 8);
  return [d.slice(0, 2), d.slice(2, 4), d.slice(4, 8)]
    .filter((p) => p.length > 0)
    .join("-");
}
/** MM-DD-YYYY -> ISO yyyy-MM-dd, or "" when incomplete/invalid (incl. bad calendar day). */
function usInputToIso(masked: string): string {
  const m = /^(\d{2})-(\d{2})-(\d{4})$/.exec(masked.trim());
  if (!m) return "";
  const mm = Number(m[1]);
  const dd = Number(m[2]);
  const yyyy = Number(m[3]);
  if (mm < 1 || mm > 12 || dd < 1 || dd > 31 || yyyy < 1900 || yyyy > 2200) return "";
  const dt = new Date(yyyy, mm - 1, dd);
  if (dt.getFullYear() !== yyyy || dt.getMonth() !== mm - 1 || dt.getDate() !== dd) {
    return ""; // rejects e.g. 02-30-2026
  }
  return `${m[3]}-${m[1]}-${m[2]}`;
}

/**
 * True iff any non-readOnly, non-implementationPartner field in this
 * appendix has a value OR the affirmation flag is set. CORE sections
 * (no appendixKey) return false — they're always required, never
 * "touched"-conditional.
 */
function isAppendixTouched(section: AgreementSection, form: FormState): boolean {
  if (!section.appendixKey) return false;
  // Build J — portal platform+username entries live outside section.fields.
  if (section.id === "appendix4") {
    const entries = parsePortalEntries(form.fields["portalEntries"]);
    if (entries.some((e) => e.platform.trim() || e.username.trim())) return true;
  }
  for (const field of section.fields) {
    if (field.readOnly) continue;
    // implementationPartner doesn't count toward "touched" -- the ERM
    // says it's never required, so leaving it blank is the expected
    // path even when Appendix 1 is being completed.
    if (field.key === "implementationPartner") continue;
    if ((form.fields[field.key] ?? "").trim().length > 0) return true;
  }
  if (section.affirmationFlag && form.affirmations[section.affirmationFlag]) {
    return true;
  }
  return false;
}

/**
 * True iff the owning section is "active" -- CORE always, appendix iff
 * required by the ERM OR touched by the consultant.
 */
function isSectionActive(
  section: AgreementSection,
  form: FormState,
  reqs: EffectiveRequirements,
): boolean {
  if (!section.appendixKey) return true;
  if (reqs[section.appendixKey]) return true;
  return isAppendixTouched(section, form);
}

/**
 * Effective per-field required check. Honours:
 *   - field.required (Implementation Partner is required:false)
 *   - field.readOnly (workAuth/consultantEmail are ERM-set)
 *   - SSN gating: bgFullSsn only required when reqs.ssn is true
 *   - Appendix gating: an optional, untouched appendix's fields are
 *     not required.
 */
function isFieldRequired(
  field: SectionField,
  section: AgreementSection,
  form: FormState,
  reqs: EffectiveRequirements,
): boolean {
  if (field.readOnly) return false;
  if (!field.required) return false;
  if (field.key === "bgFullSsn" && !reqs.ssn) return false;
  // Build J — when "Same as residence" is on, the current-address fields
  // are not separately required (the residence address is authoritative).
  if (
    CURRENT_ADDRESS_KEYS.has(field.key)
    && form.fields["bgCurrentSameAsResidence"] === "true"
  ) {
    return false;
  }
  if (!isSectionActive(section, form, reqs)) return false;
  return true;
}

function isSectionComplete(
  section: AgreementSection,
  form: FormState,
  reqs: EffectiveRequirements,
  chequeEntries: ChequeEntry[],
  workAuthUploaded: boolean,
  offerLetterUploaded: boolean,
  dlDocUploaded: boolean,
): boolean {
  for (const field of section.fields) {
    // Build G — chequeUpload has a non-form completion signal.
    // Build U — never rendered after the multi-cheque refactor, but
    // section blueprints from older releases may still ship one;
    // defer to the per-cheque check below.
    if (field.type === "file") continue;
    if (!isFieldRequired(field, section, form, reqs)) continue;
    if (!isFieldValueValid(field, form.fields[field.key] ?? "")) {
      return false;
    }
  }
  // Build I — work-authorization document lives in Personal Information
  // (cover) and is required for every consultant.
  if (section.id === "cover" && !workAuthUploaded) {
    return false;
  }
  // Build I — Appendix 1 (Phase 2 Employment) completeness: when it
  // applies, the Offer Letter must be uploaded.
  if (section.id === "appendix1" && isSectionActive(section, form, reqs)) {
    if (!offerLetterUploaded) return false;
  }
  // Build J — Appendix 3 completeness: when it applies, the DL / State-ID
  // document must be uploaded (the SSN document is always optional).
  if (section.id === "appendix3" && isSectionActive(section, form, reqs)) {
    if (!dlDocUploaded) return false;
  }
  // Build J — Appendix 4 completeness: at least one COMPLETE platform +
  // username entry is required when the section applies.
  if (section.id === "appendix4" && isSectionActive(section, form, reqs)) {
    const entries = parsePortalEntries(form.fields["portalEntries"]);
    const hasComplete = entries.some(
      (e) => e.platform.trim().length > 0 && e.username.trim().length > 0,
    );
    if (!hasComplete) return false;
  }
  // Build U — Appendix 5 completeness: when the appendix applies,
  // every cheque 0..count-1 needs a number AND an upload.
  if (section.id === "appendix5" && isSectionActive(section, form, reqs)) {
    const count = parseChequeCount(form.fields["securityCheckCount"]);
    if (count <= 0) return false;
    for (let i = 0; i < count; i++) {
      const entry = chequeEntries.find((e) => e.index === i);
      if (!entry) return false;
      if (!entry.number || !entry.number.trim()) return false;
      // Phase 5 — uploaded when EITHER the Cloudinary id or the S3 key is set.
      if (!(entry.publicId?.trim() || entry.s3Key?.trim())) return false;
    }
  }
  // Signature gating: main-agreement -> primary; review -> final.
  if (section.requiresSignature) {
    const sig = section.id === "review" ? form.finalSignature : form.signature;
    if (!sig) return false;
  }
  // Affirmation only required when the section is active.
  if (section.requiresAffirmation && section.affirmationFlag) {
    if (
      isSectionActive(section, form, reqs)
      && !form.affirmations[section.affirmationFlag]
    ) {
      return false;
    }
  }
  return true;
}

/**
 * Build U — parse the consultant-entered "Number of cheques" field
 * into a non-negative int, capping at 50 (sanity). Returns 0 when the
 * field is empty or junk.
 */
function parseChequeCount(raw: string | undefined | null): number {
  if (!raw) return 0;
  const n = parseInt(String(raw).trim(), 10);
  if (Number.isNaN(n) || n < 0) return 0;
  return Math.min(50, n);
}

/**
 * Build W — resolve special backend missing-tokens that don't map to
 * a single field key. "cheques" / "chequeUpload" both point at
 * Appendix 5 (the multi-cheque list lives there).
 */
function sectionForSpecialKey(key: string): AgreementSection | undefined {
  if (key === "cheques" || key === "chequeUpload") {
    return AGREEMENT_SECTIONS.find((s) => s.id === "appendix5");
  }
  // Build J — the repeatable portal entries token isn't a config field.
  if (key === "portalEntries") {
    return AGREEMENT_SECTIONS.find((s) => s.id === "appendix4");
  }
  return undefined;
}

function firstIncompleteIndex(
  sections: readonly AgreementSection[],
  form: FormState,
  reqs: EffectiveRequirements,
  chequeEntries: ChequeEntry[],
  workAuthUploaded: boolean,
  offerLetterUploaded: boolean,
  dlDocUploaded: boolean,
): number {
  for (let i = 0; i < sections.length - 1; i++) {
    if (!isSectionComplete(sections[i], form, reqs, chequeEntries,
        workAuthUploaded, offerLetterUploaded, dlDocUploaded)) return i;
  }
  return sections.length - 1;
}

/**
 * Build X — the consultant only sees CORE sections + ERM-required
 * appendices. Any appendix the ERM left as not-required is hidden:
 * absent from the wizard, the section navigator, the progress count,
 * and the submit-error jumps. The filter lives at the SINGLE
 * section-list source so every downstream consumer (Stepper, ReviewStep,
 * MissingItemsPanel, bounds in setCurrentStep, etc.) inherits it for
 * free — driven directly by the server-resolved effectiveRequirements
 * so client + server agree on what's shown.
 */
function filterVisibleSections(
  reqs: EffectiveRequirements,
): readonly AgreementSection[] {
  return AGREEMENT_SECTIONS.filter(
    (s) => !s.appendixKey || reqs[s.appendixKey],
  );
}

/**
 * Build Y — STRICT section-restricted revision view. When the ERM sends a
 * section-picker revision, the consultant sees ONLY the selected
 * section(s) + the final sign step ("review"); every other section is
 * absent from the wizard, navigator, progress, and submit jumps (mirrors
 * the backend write-scope enforcement). Empty selection ⇒ unrestricted.
 */
function restrictedVisibleSections(
  selectedKeys: string[],
  includePrimarySign = false,
): readonly AgreementSection[] {
  const set = new Set(selectedKeys);
  // Build AJ — a signature-only revision (the ERM "signature" scope) re-signs
  // BOTH steps: the primary on main-agreement + the execution on review. The
  // backend cleared both stored signatures, so main-agreement's signature pad
  // is empty and must be re-drawn; content + affirmations stay as-is (read
  // view), never re-armed.
  const reSignBoth = includePrimarySign || set.has("signature");
  return AGREEMENT_SECTIONS.filter(
    (s) =>
      set.has(s.id)
      || s.id === "review"
      // Build R — Phase 2 re-signs BOTH signature steps: the PRIMARY
      // (main-agreement) is always signable too, not just the final
      // execution signature on "review".
      || (reSignBoth && s.id === "main-agreement"),
  );
}

/** The active revision scope (section keys), or [] when unrestricted. */
function revisionScopeKeys(
  app: ConsultantApplication | null,
): string[] {
  if (!app || app.status !== "REVISION_REQUESTED") return [];
  return parseRevisionSections(app.revisionSections).map((r) => r.key);
}

/**
 * Build P — true when this is a Phase-2 fill restricted to the ERM-reopened
 * sections. Triggered by a persisted scope (set at advance-to-Phase-2), so
 * even an empty scope still restricts the wizard to the final sign step —
 * completed Phase 1 content (incl. uploads) is never re-opened.
 */
function isPhase2Restricted(app: ConsultantApplication | null): boolean {
  return (
    !!app
    && (app.phase ?? 1) >= 2
    && app.status === "SUBMITTED"
    && app.phase2ReopenedSections != null
  );
}

/** Build P — the reopened Phase-2 section keys (same JSON shape as the revision scope). */
function phase2ScopeKeys(app: ConsultantApplication | null): string[] {
  if (!isPhase2Restricted(app)) return [];
  return parseRevisionSections(app!.phase2ReopenedSections).map((r) => r.key);
}

// ── Page ───────────────────────────────────────────────────────

export default function ConsultantWizardPage() {
  const router = useRouter();
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";

  const [app, setApp] = useState<ConsultantApplication | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const [form, setForm] = useState<FormState>(() => buildInitialState(null));
  const [currentStep, setCurrentStep] = useState(0);
  const [touched, setTouched] = useState<Set<string>>(() => new Set());
  const [revealed, setRevealed] = useState<Set<string>>(() => new Set());

  const [saveStatus, setSaveStatus] = useState<SaveStatus>({ kind: "idle" });
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState("");
  // Build N — structured incomplete-items returned by consultantSubmit.
  // The wizard renders these grouped by section with one-tap jumps to
  // the offending step (and tries to scroll/focus the missing field).
  const [submitMissing, setSubmitMissing] = useState<{
    missingFields: string[];
    missingAffirmations: string[];
    missingSignature: boolean;
    missingFinalSignature: boolean;
  } | null>(null);

  // Build S — reading progress for the document column. Bound to the
  // section step's internal scroll container (or to 100% when the
  // content fits without scrolling). Shown both as the left-edge rail
  // on the document and as the "X% read" label in the progress strip.
  const [readingProgress, setReadingProgress] = useState(0);
  // Stable setter the SectionStep can pass down without retriggering
  // effects on every parent re-render.
  const handleReadingProgress = useCallback(
    (pct: number) => setReadingProgress(pct),
    [],
  );
  const [templateOpen, setTemplateOpen] = useState(false);
  // Build G — Appendix 5 cheque upload state.
  // Build U — extended to per-cheque list. Each entry maps to
  // index i and tracks {number, date, publicId, uploadedAt}.
  const [chequeEntries, setChequeEntries] = useState<ChequeEntry[]>([]);
  const [chequeUploadError, setChequeUploadError] = useState("");
  const [chequeUploadingIndex, setChequeUploadingIndex] = useState<number | null>(null);
  // Build W — Appendix 1 work-authorization document upload state.
  // "uploaded" is a non-form completion signal (like the cheque list):
  // a non-null work_auth_doc_public_id on the row.
  const [workAuthUploaded, setWorkAuthUploaded] = useState(false);
  const [workAuthUploadError, setWorkAuthUploadError] = useState("");
  const [workAuthUploading, setWorkAuthUploading] = useState(false);
  // Build I — Phase 2 Employment offer-letter upload state.
  const [offerLetterUploaded, setOfferLetterUploaded] = useState(false);
  const [offerLetterUploadError, setOfferLetterUploadError] = useState("");
  const [offerLetterUploading, setOfferLetterUploading] = useState(false);
  // Build J — Background Check upload state (DL/State-ID required; SSN optional).
  const [dlDocUploaded, setDlDocUploaded] = useState(false);
  const [dlDocUploadError, setDlDocUploadError] = useState("");
  const [dlDocUploading, setDlDocUploading] = useState(false);
  const [ssnDocUploaded, setSsnDocUploaded] = useState(false);
  const [ssnDocUploadError, setSsnDocUploadError] = useState("");
  const [ssnDocUploading, setSsnDocUploading] = useState(false);
  // Build G — review-step attestation gate. The consultant must
  // (a) load the generated PDF preview, (b) tick the attestation
  // checkbox, and (c) draw the final signature -- in any order --
  // before submit unlocks. previewSeen flips to true once the
  // backend stream lands.
  const [attestation, setAttestation] = useState(false);
  const [previewSeen, setPreviewSeen] = useState(false);
  // Stable callback so the preview component's effect doesn't refire
  // on every parent re-render (form state churn etc.).
  const markPreviewSeen = useCallback(() => setPreviewSeen(true), []);
  // F-3 — full clause content (parsed from the master template), fetched
  // once. Non-fatal: the read pane falls back to the plain summary if it
  // can't load.
  const [content, setContent] = useState<AgreementContent | null>(null);

  const lastSavedRef = useRef<FormState>(buildInitialState(null));
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const pauseTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Token + load ───────────────────────────────────────────────
  useEffect(() => {
    if (!appId) return;
    if (!getConsultantToken()) {
      // Build V — per-app login. The consultant never types an email;
      // the login page resolves it from this appId server-side.
      router.replace(`/consultant/${encodeURIComponent(appId)}/login`);
      return;
    }
    let cancelled = false;
    setLoading(true);
    // F-3 — load the real clauses once (non-fatal).
    fetchAgreementContent(appId)
      .then((c) => {
        if (!cancelled) setContent(c);
      })
      .catch(() => {
        /* read pane falls back to the plain summary */
      });
    getConsultantApplicationView(appId)
      .then((data) => {
        if (cancelled) return;
        if (data.status === "CANCELLED" || data.status === "EXPIRED") {
          router.replace("/consultant/dashboard");
          return;
        }
        // Build K — VERIFIED / SIGNED / UPDATED / COMPLETED render
        // inline status screens on this route. The wizard scaffolding
        // (auto-save, fields) doesn't run; the consultant sees a
        // calm "sent for verification" / "accepted" copy with no
        // PDF, no download, no edit affordance.
        setApp(data);
        if (data.status === "VERIFIED" || data.status === "SIGNED"
                || data.status === "UPDATED" || data.status === "COMPLETED") {
          return;
        }
        const initial = buildInitialState(data);
        // Build R — a Phase-2 fill makes the main-agreement step signable so
        // the consultant RE-DRAWS the primary. Legacy/migrated records keep a
        // non-null signatureImage that would pre-fill form.signature and let
        // them click through without re-drawing (the backend would then reuse
        // the stale Phase-1 primary). Clear the in-wizard primary for Phase-2
        // fills so a fresh draw is required; the persisted Phase-1 signature
        // stays as the audit/backstop record.
        if (isPhase2Restricted(data)) {
          initial.signature = null;
        }
        setForm(initial);
        lastSavedRef.current = { ...initial };
        const entries = parseChequeList(data.cheques);
        setChequeEntries(entries);
        // Build W/I/J — uploads already on file?
        // Phase 5 — uploaded when EITHER the Cloudinary id or the S3 key is set.
        setWorkAuthUploaded(Boolean(data.workAuthDocPublicId || data.workAuthDocS3Key));
        setOfferLetterUploaded(Boolean(data.offerLetterPublicId || data.offerLetterS3Key));
        setDlDocUploaded(Boolean(data.dlDocPublicId || data.dlDocS3Key));
        setSsnDocUploaded(Boolean(data.ssnDocPublicId || data.ssnDocS3Key));
        // Build X — resume at the first incomplete section across the
        // VISIBLE set (core + ERM-required appendices). Hidden
        // appendices are not counted; if every visible non-review
        // section is already complete, the consultant lands on Review.
        const loadedReqs = effectiveRequirements(data);
        // Build Y / Build P — the seed list MUST match the render-time
        // visibleSections precedence (revision scope → Phase-2 reopened
        // scope → normal visible set); otherwise firstIncompleteIndex
        // seeds an index into a longer list than what renders, leaving
        // currentStep out of range and crashing on first paint (the clamp
        // effect only runs post-commit).
        const loadedScope = revisionScopeKeys(data);
        const loadedVisible = loadedScope.length > 0
            ? restrictedVisibleSections(loadedScope)
            : isPhase2Restricted(data)
              ? restrictedVisibleSections(phase2ScopeKeys(data), true)
              : filterVisibleSections(loadedReqs);
        const loadedWorkAuth = Boolean(data.workAuthDocPublicId || data.workAuthDocS3Key);
        const loadedOffer = Boolean(data.offerLetterPublicId || data.offerLetterS3Key);
        const loadedDl = Boolean(data.dlDocPublicId || data.dlDocS3Key);
        setCurrentStep(firstIncompleteIndex(
            loadedVisible, initial, loadedReqs, entries, loadedWorkAuth, loadedOffer, loadedDl));
      })
      .catch((e) => {
        if (cancelled) return;
        const msg = e instanceof Error ? e.message : "Couldn't load this agreement.";
        if (/404|not found/i.test(msg)) {
          router.replace("/consultant/dashboard");
        } else {
          setLoadError(msg);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [appId, router]);

  // Cleanup ────────────────────────────────────────────────────
  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
      if (pauseTimerRef.current) clearTimeout(pauseTimerRef.current);
      abortRef.current?.abort();
    };
  }, []);

  // Build Y — active revision scope (section ids), empty when unrestricted.
  const restrictedScopeSet = useMemo(
    () => new Set(revisionScopeKeys(app)),
    [app],
  );

  // Auto-save (reuses Phase 5 internals) ───────────────────────
  const computeDelta = useCallback(
    (current: FormState): ConsultantFillPayload => {
      const delta: ConsultantFillPayload = {};
      const snap = lastSavedRef.current;
      // Build Y — during a section-restricted revision, never emit a
      // change for a section outside the scope (the backend would reject
      // it anyway; this keeps out-of-scope values off the wire).
      const restricted = restrictedScopeSet.size > 0;
      for (const key of PERSISTED_FIELD_KEYS) {
        if (current.fields[key] !== snap.fields[key]) {
          if (restricted) {
            const secId = sectionIdForPersistedKey(key);
            if (!secId || !restrictedScopeSet.has(secId)) continue;
          }
          // Build J — bgCurrentSameAsResidence is a boolean on the wire.
          if (key === "bgCurrentSameAsResidence") {
            (delta as Record<string, boolean>)[key] = current.fields[key] === "true";
          } else {
            (delta as Record<string, string>)[key] = current.fields[key];
          }
        }
      }
      for (const flag of AFFIRMATION_FLAGS) {
        if (current.affirmations[flag] !== snap.affirmations[flag]) {
          if (restricted) {
            const sec = findSectionForAffirmation(flag);
            if (!sec || !restrictedScopeSet.has(sec.id)) continue;
          }
          (delta as Record<string, boolean>)[flag] = current.affirmations[flag];
        }
      }
      return delta;
    },
    [restrictedScopeSet],
  );

  const fireSave = useCallback(
    async (current: FormState) => {
      if (!appId) return;
      const patch = computeDelta(current);
      if (Object.keys(patch).length === 0) {
        setSaveStatus({ kind: "saved", at: Date.now() });
        return;
      }
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      setSaveStatus({ kind: "saving" });
      try {
        await saveConsultantFill(appId, patch, controller.signal);
        if (controller.signal.aborted) return;
        const nextFields = { ...lastSavedRef.current.fields };
        const nextAff = { ...lastSavedRef.current.affirmations };
        for (const k of Object.keys(patch)) {
          if (k in nextFields) {
            nextFields[k] =
              (patch as Record<string, string>)[k] ?? "";
          } else if ((AFFIRMATION_FLAGS as readonly string[]).includes(k)) {
            nextAff[k as AffirmationFlag] = Boolean(
              (patch as Record<string, boolean>)[k],
            );
          }
        }
        lastSavedRef.current = {
          ...lastSavedRef.current,
          fields: nextFields,
          affirmations: nextAff,
        };
        setSaveStatus({ kind: "saved", at: Date.now() });
      } catch (e) {
        if (e instanceof DOMException && e.name === "AbortError") return;
        if (controller.signal.aborted) return;
        const msg = e instanceof Error ? e.message : "Couldn't save.";
        if (/too many|429/i.test(msg)) {
          setSaveStatus({ kind: "paused" });
          if (pauseTimerRef.current) clearTimeout(pauseTimerRef.current);
          pauseTimerRef.current = setTimeout(
            () => setSaveStatus({ kind: "idle" }),
            30_000,
          );
        } else {
          setSaveStatus({ kind: "error", message: msg });
        }
      }
    },
    [appId, computeDelta],
  );

  const scheduleSave = useCallback(
    (current: FormState) => {
      if (saveStatus.kind === "paused") return;
      if (debounceRef.current) clearTimeout(debounceRef.current);
      debounceRef.current = setTimeout(() => void fireSave(current), 1500);
    },
    [fireSave, saveStatus.kind],
  );

  // Field / affirmation / signature setters ────────────────────
  const setField = useCallback(
    (key: string, value: string) => {
      setForm((prev) => {
        if (prev.fields[key] === value) return prev;
        const next = {
          ...prev,
          fields: { ...prev.fields, [key]: value },
        };
        scheduleSave(next);
        return next;
      });
    },
    [scheduleSave],
  );

  const markTouched = useCallback((key: string) => {
    setTouched((prev) => {
      if (prev.has(key)) return prev;
      const next = new Set(prev);
      next.add(key);
      return next;
    });
  }, []);

  const setAffirmation = useCallback(
    (flag: AffirmationFlag, value: boolean) => {
      setForm((prev) => {
        if (prev.affirmations[flag] === value) return prev;
        const next = {
          ...prev,
          affirmations: { ...prev.affirmations, [flag]: value },
        };
        scheduleSave(next);
        return next;
      });
    },
    [scheduleSave],
  );

  const setSignature = useCallback(
    (dataUrl: string | null) => {
      setForm((prev) => {
        if (prev.signature === dataUrl) return prev;
        const next = { ...prev, signature: dataUrl };
        scheduleSave(next);
        return next;
      });
    },
    [scheduleSave],
  );

  // F-4 final signature -- client-only until submit; never autosaved.
  // Both the primary + final signatures are uploaded to Cloudinary
  // atomically inside POST /submit, so partial state has nothing
  // meaningful to persist mid-session.
  const setFinalSignature = useCallback((dataUrl: string | null) => {
    setForm((prev) => {
      if (prev.finalSignature === dataUrl) return prev;
      return { ...prev, finalSignature: dataUrl };
    });
  }, []);

  // Build U — per-cheque upload. One call per index; the wizard mirrors
  // the row's cheques JSON so it doesn't have to refetch after every
  // upload. The "uploaded" signal per index is a non-empty publicId on
  // the matching ChequeEntry.
  const handleChequeUploadAt = useCallback(
    async (index: number, file: File) => {
      if (!appId) return;
      setChequeUploadError("");
      setChequeUploadingIndex(index);
      try {
        await uploadConsultantChequeAt(appId, index, file);
        setChequeEntries((prev) => {
          const next = prev.filter((e) => e.index !== index);
          const existing = prev.find((e) => e.index === index);
          next.push({
            index,
            number: existing?.number ?? "",
            date: existing?.date ?? "",
            // In-session "uploaded" marker; the real S3 key arrives on next load.
            // The (publicId || s3Key) checks treat either as uploaded.
            publicId: `agreements/${appId}-cheque-${index}`,
            s3Key: existing?.s3Key ?? "",
            contentType: file.type,
            uploadedAt: new Date().toISOString(),
          });
          next.sort((a, b) => a.index - b.index);
          return next;
        });
      } catch (e) {
        const msg = e instanceof Error ? e.message : "Couldn't upload.";
        setChequeUploadError(msg);
      } finally {
        setChequeUploadingIndex(null);
      }
    },
    [appId],
  );

  // Build U — per-cheque metadata patch. Saves only the number / date
  // for one index; the upload bytes (if any) survive untouched.
  const handleChequeMetadataChange = useCallback(
    async (
      index: number,
      patch: { number?: string; date?: string },
    ) => {
      if (!appId) return;
      // Optimistic update so the input is responsive.
      setChequeEntries((prev) => {
        const next = prev.filter((e) => e.index !== index);
        const existing = prev.find((e) => e.index === index);
        next.push({
          index,
          number: patch.number ?? existing?.number ?? "",
          date: patch.date ?? existing?.date ?? "",
          publicId: existing?.publicId ?? "",
          s3Key: existing?.s3Key ?? "",
          contentType: existing?.contentType ?? "",
          uploadedAt: existing?.uploadedAt ?? "",
        });
        next.sort((a, b) => a.index - b.index);
        return next;
      });
      try {
        await saveConsultantChequeMetadata(appId, index, patch);
      } catch (e) {
        const msg = e instanceof Error ? e.message : "Couldn't save.";
        setChequeUploadError(msg);
      }
    },
    [appId],
  );

  // Build W — Appendix 1 work-authorization document upload.
  const handleWorkAuthUpload = useCallback(
    async (file: File) => {
      if (!appId) return;
      setWorkAuthUploadError("");
      setWorkAuthUploading(true);
      try {
        await uploadConsultantWorkAuthDoc(appId, file);
        setWorkAuthUploaded(true);
      } catch (e) {
        const msg = e instanceof Error ? e.message : "Couldn't upload.";
        setWorkAuthUploadError(msg);
      } finally {
        setWorkAuthUploading(false);
      }
    },
    [appId],
  );

  // Build I — Phase 2 Employment offer-letter upload.
  const handleOfferLetterUpload = useCallback(
    async (file: File) => {
      if (!appId) return;
      setOfferLetterUploadError("");
      setOfferLetterUploading(true);
      try {
        await uploadConsultantOfferLetter(appId, file);
        setOfferLetterUploaded(true);
      } catch (e) {
        const msg = e instanceof Error ? e.message : "Couldn't upload.";
        setOfferLetterUploadError(msg);
      } finally {
        setOfferLetterUploading(false);
      }
    },
    [appId],
  );

  // Build J — Background Check DL/State-ID document upload (required).
  const handleDlDocUpload = useCallback(
    async (file: File) => {
      if (!appId) return;
      setDlDocUploadError("");
      setDlDocUploading(true);
      try {
        await uploadConsultantDlDoc(appId, file);
        setDlDocUploaded(true);
      } catch (e) {
        setDlDocUploadError(e instanceof Error ? e.message : "Couldn't upload.");
      } finally {
        setDlDocUploading(false);
      }
    },
    [appId],
  );

  // Build J — Background Check SSN document upload (optional, never blocks).
  const handleSsnDocUpload = useCallback(
    async (file: File) => {
      if (!appId) return;
      setSsnDocUploadError("");
      setSsnDocUploading(true);
      try {
        await uploadConsultantSsnDoc(appId, file);
        setSsnDocUploaded(true);
      } catch (e) {
        setSsnDocUploadError(e instanceof Error ? e.message : "Couldn't upload.");
      } finally {
        setSsnDocUploading(false);
      }
    },
    [appId],
  );

  const setLegalName = useCallback(
    (value: string) => {
      setForm((prev) => {
        if (prev.signedLegalName === value) return prev;
        return { ...prev, signedLegalName: value };
      });
    },
    [],
  );

  // Effective requirements derived from the ERM's per-agreement flags.
  // Memoised so per-section gating is a single derivation each render.
  const reqs = useMemo<EffectiveRequirements>(() => {
    const base = effectiveRequirements(app);
    // Build Y — in a restricted revision, the ERM-selected appendices are
    // forced required (so the wizard gates them as must-complete +
    // re-affirm), mirroring the backend's revisionForcedSections.
    const scope = revisionScopeKeys(app);
    if (scope.length === 0) return base;
    const o: EffectiveRequirements = { ...base };
    for (const k of scope) {
      if (k === "appendix1") o.appendix1 = true;
      else if (k === "appendix2") o.appendix2 = true;
      else if (k === "appendix3") o.appendix3 = true;
      else if (k === "appendix4") o.appendix4 = true;
      else if (k === "appendix5") o.appendix5 = true;
    }
    return o;
  }, [app]);

  // Build X — visible-section source: core sections + ONLY the
  // appendices the ERM marked required. Everything downstream
  // (wizard step indexing, navigator, progress count, scroll-gate,
  // submit-error jumps, ReviewStep read-back) is derived from this
  // list so a not-required appendix simply doesn't exist for the
  // consultant.
  const visibleSections = useMemo(() => {
    // Build Y — a section-restricted revision round takes precedence.
    const scope = revisionScopeKeys(app);
    if (scope.length > 0) return restrictedVisibleSections(scope);
    // Build P — a Phase-2 fill restricts to the ERM-reopened sections
    // (+ the two signature steps). Completed Phase 1 NON-signature content —
    // including every upload (cheques, work-auth, DL/SSN) — stays hidden +
    // immutable. Build R — the consultant re-signs BOTH the primary
    // (main-agreement) and the final execution signature for the Phase 2
    // execution; the offer-letter and similar sections reuse the freshly
    // re-drawn primary at render time. An empty reopened scope still
    // restricts to just the two signature steps.
    if (isPhase2Restricted(app)) {
      return restrictedVisibleSections(phase2ScopeKeys(app), true);
    }
    return filterVisibleSections(reqs);
  }, [reqs, app]);

  // Build X — if the ERM updates per-agreement requirements mid-flow
  // (or a previously-required section is hidden on next load), clamp
  // currentStep so it never points past the filtered list. Without
  // the clamp the wizard would land on an undefined section and
  // crash on first render after the change.
  useEffect(() => {
    if (currentStep > visibleSections.length - 1) {
      setCurrentStep(Math.max(0, visibleSections.length - 1));
    }
  }, [visibleSections.length, currentStep]);

  // Submit ─────────────────────────────────────────────────────
  const handleSubmit = useCallback(async () => {
    if (!appId) return;
    setSubmitError("");
    setSubmitMissing(null);
    setSubmitting(true);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    abortRef.current?.abort();
    try {
      // Final flush of any pending delta.
      const patch = computeDelta(form);
      if (Object.keys(patch).length > 0) {
        await saveConsultantFill(appId, patch);
        const nextFields = { ...lastSavedRef.current.fields };
        const nextAff = { ...lastSavedRef.current.affirmations };
        for (const k of Object.keys(patch)) {
          if (k in nextFields)
            nextFields[k] = (patch as Record<string, string>)[k] ?? "";
          else if ((AFFIRMATION_FLAGS as readonly string[]).includes(k))
            nextAff[k as AffirmationFlag] = Boolean(
              (patch as Record<string, boolean>)[k],
            );
        }
        lastSavedRef.current = {
          ...lastSavedRef.current,
          fields: nextFields,
          affirmations: nextAff,
        };
      }
      // Build V — the PRIMARY signature is only re-required when the
      // main-agreement step is part of THIS round's visible sections (a first
      // fill, or the Phase-2 re-sign). In a section-restricted revision the
      // main step is hidden and NOT re-required: the persisted primary is
      // reused server-side and the consultant's explicit revision execution
      // signature (the review step, below) covers the revised content.
      const mainStepInScope = visibleSections.some((s) => s.id === "main-agreement");
      if (mainStepInScope && !form.signature) {
        throw new Error("Your primary signature is required before you can submit.");
      }
      if (!form.finalSignature) {
        throw new Error("Please draw your final signature on the review step before submitting.");
      }
      // Build AB-2 — require a non-blank legal name client-side too (backend now
      // requires non-blank, no longer first+last), so a cleared name surfaces
      // here instead of a swallowed backend 400.
      if (!form.signedLegalName || !form.signedLegalName.trim()) {
        throw new Error("Please type your full legal name to execute the agreement.");
      }
      const updated = await signConsultantApplication(
        appId,
        form.signedLegalName.trim(),
        form.signature ?? "",
        form.finalSignature,
      );
      // Build K — stay on this route and re-render as the inline
      // "sent for verification" status screen. Dropping the redirect
      // gives the consultant immediate, in-place feedback that the
      // submit landed.
      setApp(updated);
      setSubmitting(false);
      return;
    } catch (e) {
      // Parse the structured backend payload when present so the
      // wizard can show a section-grouped, jump-navigable list of
      // exactly what's missing.
      let msg = e instanceof Error ? e.message : "Couldn't submit.";
      let structured: {
        missingFields: string[];
        missingAffirmations: string[];
        missingSignature: boolean;
        missingFinalSignature: boolean;
      } | null = null;
      try {
        const obj = JSON.parse(msg) as {
          data?: {
            missingFields?: string[];
            missingAffirmations?: string[];
            missingSignature?: boolean;
            missingFinalSignature?: boolean;
          };
          message?: string;
        };
        if (obj?.data) {
          msg = obj.message
            || "Some items are still needed before you can submit.";
          structured = {
            missingFields: obj.data.missingFields ?? [],
            missingAffirmations: obj.data.missingAffirmations ?? [],
            missingSignature: Boolean(obj.data.missingSignature),
            missingFinalSignature: Boolean(obj.data.missingFinalSignature),
          };
        }
      } catch {
        /* not JSON; fall through with the original message */
      }
      setSubmitMissing(structured);
      setSubmitError(msg);
      setSubmitting(false);
    }
  }, [appId, computeDelta, form, reqs, chequeEntries, router, visibleSections]);

  // Step accessors ──────────────────────────────────────────────
  // Build X — wizard step indexing flows through visibleSections only.
  // A not-required appendix isn't in the list, so the consultant
  // cannot land on it, see it in the navigator, or have it count
  // toward "Section X of N".
  const section = visibleSections[currentStep];
  const sectionStatus = useMemo(
    () =>
      visibleSections.map((s, i) => {
        const complete =
          i === visibleSections.length - 1
            ? visibleSections.slice(0, -1).every((sec) =>
                isSectionComplete(sec, form, reqs, chequeEntries, workAuthUploaded, offerLetterUploaded, dlDocUploaded),
              ) && Boolean(form.finalSignature)
            : isSectionComplete(s, form, reqs, chequeEntries, workAuthUploaded, offerLetterUploaded, dlDocUploaded);
        return { id: s.id, title: s.title, step: s.step, complete };
      }),
    [visibleSections, form, reqs, chequeEntries, workAuthUploaded, offerLetterUploaded, dlDocUploaded],
  );
  const canAdvance = isSectionComplete(section, form, reqs, chequeEntries, workAuthUploaded, offerLetterUploaded, dlDocUploaded);
  const isReviewStep = currentStep === visibleSections.length - 1;
  // Build G — submit is gated on EVERY non-review section being
  // complete, the final signature being drawn, the consultant having
  // SEEN the generated PDF preview, AND the read-confirmation
  // attestation being ticked. Missing any of these keeps the submit
  // button disabled.
  const allComplete = useMemo(
    () =>
      visibleSections.slice(0, -1).every((s) =>
        isSectionComplete(s, form, reqs, chequeEntries, workAuthUploaded, offerLetterUploaded, dlDocUploaded),
      )
      && Boolean(form.finalSignature)
      && attestation
      && previewSeen,
    [visibleSections, form, reqs, chequeEntries, workAuthUploaded, offerLetterUploaded, dlDocUploaded, attestation, previewSeen],
  );

  // Build AB — never let Submit be a silent no-op on mobile. When the agreement
  // is incomplete, surface the FIRST blocker and scroll it into view (the bug
  // was a disabled sticky-footer Submit, so the tap did nothing and the reason
  // sat off-screen). When complete, run the real submit.
  const attemptSubmit = useCallback(() => {
    if (allComplete) {
      void handleSubmit();
      return;
    }
    const scrollToId = (id: string) => {
      if (typeof document === "undefined") return;
      document
        .getElementById(id)
        ?.scrollIntoView({ behavior: "smooth", block: "center" });
    };
    // A required section is incomplete (rare on the review step — the wizard
    // blocks advancing past one — but handle it): jump back to fix it.
    const firstIncomplete = visibleSections.slice(0, -1).findIndex(
      (s) => !isSectionComplete(
        s, form, reqs, chequeEntries, workAuthUploaded, offerLetterUploaded, dlDocUploaded),
    );
    if (firstIncomplete >= 0) {
      setSubmitError("Some sections still need attention before you can submit.");
      setCurrentStep(firstIncomplete);
      return;
    }
    if (!previewSeen) {
      setSubmitError("Please scroll through the full agreement to the end before submitting.");
      scrollToId("agreement-preview");
      return;
    }
    if (!attestation) {
      setSubmitError("Please tick the box confirming you have read the agreement.");
      scrollToId("consultant-review-attestation");
      return;
    }
    if (!form.finalSignature) {
      setSubmitError("Please add your final signature to execute the agreement.");
      scrollToId("final-signature-block");
      return;
    }
    setSubmitError("Some items are still needed before you can submit.");
  }, [allComplete, handleSubmit, visibleSections, form, reqs, chequeEntries,
      workAuthUploaded, offerLetterUploaded, dlDocUploaded, previewSeen, attestation]);

  // Build AB — clear the submit error once everything is satisfied.
  useEffect(() => {
    if (allComplete) setSubmitError("");
  }, [allComplete]);

  // Build W — live re-evaluation of the missing list. The backend
  // payload is the source of truth at the moment of the failed
  // submit, but the consultant fixes items in place; we re-check
  // each one against the CURRENT form/affirmation/signature/cheque
  // state on every render so a field disappears from the panel the
  // instant it's filled, the affirmation clears the moment it's
  // ticked, etc. When everything is fixed, liveMissing is empty and
  // the panel auto-dismisses.
  const liveMissing = useMemo(() => {
    if (!submitMissing) return null;
    const fields = submitMissing.missingFields.filter((k) => {
      if (k === "cheques" || k === "chequeUpload") {
        // Multi-cheque rule: every index 0..count-1 needs number + upload.
        const required = parseChequeCount(form.fields["securityCheckCount"]);
        if (required <= 0) return true;
        for (let i = 0; i < required; i++) {
          const entry = chequeEntries.find((e) => e.index === i);
          if (!entry || !entry.number?.trim()
              || !(entry.publicId?.trim() || entry.s3Key?.trim())) {
            return true;
          }
        }
        return false;
      }
      // Build W/I/J — uploads have a non-form (upload) completion signal.
      if (k === "workAuthDoc") {
        return !workAuthUploaded;
      }
      if (k === "offerLetter") {
        return !offerLetterUploaded;
      }
      if (k === "dlDoc") {
        return !dlDocUploaded;
      }
      // Build J — Appendix 4 needs ≥1 complete platform+username entry.
      if (k === "portalEntries") {
        const entries = parsePortalEntries(form.fields["portalEntries"]);
        return !entries.some(
          (e) => e.platform.trim().length > 0 && e.username.trim().length > 0,
        );
      }
      const value = form.fields[k] ?? "";
      const section = findSectionForFieldKey(k);
      const field = section?.fields.find((f) => f.key === k);
      if (!field) return Boolean(!value?.trim());
      // Use the existing per-field validator so format checks
      // (routing/account/SSN/email) match the backend rules.
      return !isFieldValueValid(field, value);
    });
    const affs = submitMissing.missingAffirmations.filter((flag) => {
      return !form.affirmations[flag as AffirmationFlag];
    });
    const missingSig = submitMissing.missingSignature && !form.signature;
    const missingFinalSig =
      submitMissing.missingFinalSignature && !form.finalSignature;
    if (
      fields.length === 0
      && affs.length === 0
      && !missingSig
      && !missingFinalSig
    ) {
      return null;
    }
    return {
      missingFields: fields,
      missingAffirmations: affs,
      missingSignature: missingSig,
      missingFinalSignature: missingFinalSig,
    };
  }, [submitMissing, form, chequeEntries, workAuthUploaded, offerLetterUploaded, dlDocUploaded]);

  // Build W — auto-dismiss the panel the moment the list empties.
  useEffect(() => {
    if (submitMissing && !liveMissing) {
      setSubmitMissing(null);
      setSubmitError("");
    }
  }, [submitMissing, liveMissing]);

  // Build W — per-section sets of currently-missing items. Used to
  // mark sections on the stepper + flag the exact field/affirmation/
  // signature inside the section render.
  const missingByField = useMemo(() => {
    const s = new Set<string>();
    if (liveMissing) for (const k of liveMissing.missingFields) s.add(k);
    return s;
  }, [liveMissing]);
  const missingByAffirmation = useMemo(() => {
    const s = new Set<string>();
    if (liveMissing) for (const f of liveMissing.missingAffirmations) s.add(f);
    return s;
  }, [liveMissing]);
  const sectionsWithMissing = useMemo(() => {
    const ids = new Set<string>();
    if (!liveMissing) return ids;
    for (const k of liveMissing.missingFields) {
      const sec = findSectionForFieldKey(k) ?? sectionForSpecialKey(k);
      if (sec) ids.add(sec.id);
    }
    for (const f of liveMissing.missingAffirmations) {
      const sec = findSectionForAffirmation(f as AffirmationFlag);
      if (sec) ids.add(sec.id);
    }
    if (liveMissing.missingSignature) ids.add("main-agreement");
    if (liveMissing.missingFinalSignature) ids.add("review");
    return ids;
  }, [liveMissing]);

  // Render ─────────────────────────────────────────────────────

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-stone-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  if (loadError || !app) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-stone-50 px-6">
        <div className="text-center max-w-sm">
          <AlertCircle size={28} className="text-red-600 inline" />
          <p className="text-sm text-red-700 mt-2">
            {loadError || "We couldn't load this agreement."}
          </p>
        </div>
      </div>
    );
  }

  // Build L — no consultant download in any state. VERIFIED rows split on
  // whether the ERM has approved the consultant version: not yet → "sent
  // for verification"; approved → "verified" (+ revision notice).
  if (app.status === "VERIFIED" || app.status === "SIGNED"
          || app.status === "UPDATED") {
    return <ConsultantStatusScreen
      kind={app.consultantCopyReleased ? "verified" : "sent"}
      onSignOut={() => router.replace("/consultant/dashboard")}
    />;
  }
  if (app.status === "COMPLETED") {
    return <ConsultantStatusScreen kind="accepted" onSignOut={() => {
      router.replace("/consultant/dashboard");
    }} />;
  }
  // Build Q — access link lapsed (7-day window) OR a legacy EXPIRED row.
  // The consultant sees a clear "expired" state; the wizard never mounts.
  // They re-engage only after the ERM resends, which restarts the clock.
  // The agreement itself is never expired/hidden — only the link lapses.
  if (app.status === "EXPIRED" || app.linkExpired) {
    return <ConsultantStatusScreen kind="expired" onSignOut={() => {
      router.replace("/consultant/dashboard");
    }} />;
  }

  // Build T — e-sign consent gate. Shown ONCE per agreement on the
  // first wizard load. Records consent_given_at + consent_ip +
  // consent_version server-side; the wizard mounts only after the
  // consultant agrees. The certificate of completion later surfaces
  // this record.
  if (!app.consentGivenAt) {
    return <ConsentGate
      app={app}
      onAccepted={(updated) => setApp(updated)}
      onSignOut={() => router.replace("/consultant/dashboard")}
    />;
  }

  return (
    <main
      className={`${wizardFontClass} sage-wizard min-h-screen pb-24 lg:pb-12 antialiased`}
      style={{
        // Build S — color tokens scoped to the wizard route.
        ["--navy" as string]: "#1B2A5C",
        ["--navy-deep" as string]: "#0F1F44",
        ["--ink" as string]: "#1d2433",
        ["--copper" as string]: "#C87D5C",
        ["--copper-deep" as string]: "#a8623f",
        ["--copper-wash" as string]: "#faf1ea",
        ["--page" as string]: "#eceef3",
        ["--paper" as string]: "#ffffff",
        ["--muted" as string]: "#5c6577",
        ["--line" as string]: "#e4e7ee",
        ["--ok" as string]: "#356b51",
        ["--ok-wash" as string]: "#eef5f0",
        background: "var(--page)",
      } as React.CSSProperties}
    >
      <meta name="robots" content="noindex,nofollow" />
      <style>{`
        .sage-wizard { font-family: var(--font-inter), ui-sans-serif, system-ui, sans-serif; color: var(--ink); }
        .sage-wizard .display { font-family: var(--font-fraunces), ui-serif, Georgia, serif; }
        .sage-wizard .legal { font-family: var(--font-newsreader), ui-serif, Georgia, serif; }
        .sage-wizard .legal p { font-size: 17px; line-height: 1.72; text-wrap: pretty; }
        .sage-wizard :focus-visible { outline: 2px solid var(--copper); outline-offset: 2px; border-radius: 4px; }
      `}</style>

      <header
        className="border-b sticky top-0 z-30"
        style={{ background: "var(--navy-deep)", borderColor: "rgba(255,255,255,0.06)" }}
      >
        <div className="max-w-[1320px] mx-auto px-5 sm:px-10 h-12 flex items-center justify-between">
          <div className="flex items-center gap-3 min-w-0">
            <span
              className="display text-[15px] font-semibold tracking-tight text-white whitespace-nowrap"
              aria-label="Sage IT Co"
            >
              SAGE <span className="text-[var(--copper)]">IT</span> CO
            </span>
            <span
              aria-hidden
              className="hidden sm:inline h-3 w-px"
              style={{ background: "rgba(255,255,255,0.16)" }}
            />
            <span className="hidden sm:inline text-[12.5px] text-white/70 truncate">
              Complete your agreement
            </span>
          </div>
          <span className="text-[11.5px] text-white/65 inline-flex items-center gap-1.5 whitespace-nowrap">
            {saveStatus.kind === "saving" ? (
              <>
                <Loader2 size={11} className="animate-spin" /> Auto-saving
              </>
            ) : saveStatus.kind === "error" ? (
              <>
                <AlertCircle size={11} className="text-red-300" /> Auto-save failed
              </>
            ) : saveStatus.kind === "paused" ? (
              <>
                <PauseCircle size={11} /> Auto-save paused
              </>
            ) : (
              <>
                <CheckCircle2 size={11} className="text-emerald-300" /> Auto-saved
              </>
            )}
          </span>
        </div>
      </header>

      <Stepper
        sections={sectionStatus}
        currentStep={currentStep}
        readPct={readingProgress}
        sectionsWithMissing={sectionsWithMissing}
        onJump={(i) => {
          // Jump-back only. Completed and the current step are
          // navigable; upcoming sections are locked until prior ones
          // are complete. Sections flagged in sectionsWithMissing
          // (from a failed submit) are ALWAYS navigable so the
          // consultant can fix them from any later section.
          const targetId = visibleSections[i]?.id;
          if (targetId && sectionsWithMissing.has(targetId)) {
            setCurrentStep(i);
            return;
          }
          if (i < currentStep) {
            setCurrentStep(i);
            return;
          }
          if (i === currentStep) return;
          for (let j = 0; j <= i; j++) {
            if (j !== visibleSections.length - 1 && !sectionStatus[j].complete) {
              setCurrentStep(j);
              return;
            }
          }
          setCurrentStep(i);
        }}
      />

      <section className="max-w-7xl mx-auto px-4 sm:px-8 pt-6 lg:pt-10 motion-safe:[scroll-behavior:smooth]">
        {(app.phase === 2 || (app.status === "REVISION_REQUESTED" && app.currentRevisionRemarks)) && (
          <div className="mb-6 max-w-[min(100%,860px)]">
            {app.phase === 2 ? (
              <div className="rounded-xl border border-sage-copper/30 bg-[#FBF1E8] px-5 py-4 flex items-start gap-3">
                <Sparkles size={16} className="text-sage-copper-deep shrink-0 mt-0.5" />
                <div>
                  <p className="text-sm font-semibold text-sage-copper-deep">
                    Phase 2 in progress
                  </p>
                  <p className="text-sm text-stone-700 mt-1 leading-relaxed">
                    Sage IT advanced your agreement to Phase 2 on the same
                    document. Everything you completed in Phase 1 — including
                    your uploads — stays locked and unchanged. Just complete
                    the reopened section(s) below, re-sign, and submit.
                  </p>
                </div>
              </div>
            ) : (
              <div className="rounded-xl border border-sage-copper/30 bg-[#FBF1E8] px-5 py-4 flex items-start gap-3">
                <AlertCircle size={16} className="text-sage-copper-deep shrink-0 mt-0.5" />
                <div>
                  <p className="text-sm font-semibold text-sage-copper-deep">
                    Sage IT requested changes
                  </p>
                  <p className="text-sm text-stone-700 mt-1 whitespace-pre-wrap leading-relaxed">
                    {app.currentRevisionRemarks}
                  </p>
                  <p className="text-xs text-stone-600 mt-2">
                    {revisionScopeKeys(app).includes("signature")
                      ? "Re-draw your signature on the pages below and submit to send it back for verification. Your details are unchanged — only your signature needs redoing."
                      : revisionScopeKeys(app).length > 0
                      ? "Only the section(s) Sage IT flagged are shown below. Update them, re-affirm, re-sign, and submit to send it back for verification."
                      : "Update the highlighted fields, then submit to send it back for verification."}
                  </p>
                </div>
              </div>
            )}
          </div>
        )}

        {isReviewStep ? (
          <ReviewStep
            appId={appId}
            form={form}
            reqs={reqs}
            visibleSections={visibleSections}
            onJumpToSection={(idx) => setCurrentStep(idx)}
            onFinalSignature={setFinalSignature}
            onLegalName={setLegalName}
            allComplete={allComplete}
            chequeEntries={chequeEntries}
            workAuthUploaded={workAuthUploaded}
            offerLetterUploaded={offerLetterUploaded}
            dlDocUploaded={dlDocUploaded}
            attestation={attestation}
            onAttestation={setAttestation}
            previewSeen={previewSeen}
            onPreviewSeen={markPreviewSeen}
            onBack={() => setCurrentStep((s) => Math.max(0, s - 1))}
            onSubmit={attemptSubmit}
            submitting={submitting}
            submitError={submitError}
            missingFinalSignature={Boolean(liveMissing?.missingFinalSignature)}
          />
        ) : (
          <SectionStep
            section={section}
            content={content}
            form={form}
            reqs={reqs}
            touched={touched}
            revealed={revealed}
            onField={setField}
            onTouched={markTouched}
            onRevealed={(key, on) => {
              setRevealed((prev) => {
                const next = new Set(prev);
                if (on) next.add(key);
                else next.delete(key);
                return next;
              });
            }}
            onAffirm={setAffirmation}
            onSignature={setSignature}
            onLegalName={setLegalName}
            revision={!visibleSections.some((s) => s.id === "main-agreement")}
            consultantEmail={app.consultantEmail}
            effectiveDateText={formatUsDate(app.effectiveDate)}
            onOpenTemplate={() => setTemplateOpen(true)}
            chequeEntries={chequeEntries}
            chequeUploadingIndex={chequeUploadingIndex}
            chequeUploadError={chequeUploadError}
            onUploadChequeAt={(index, f) => void handleChequeUploadAt(index, f)}
            onChequeMetadataChange={(index, patch) =>
              void handleChequeMetadataChange(index, patch)
            }
            workAuthUploaded={workAuthUploaded}
            workAuthUploading={workAuthUploading}
            workAuthError={workAuthUploadError}
            onUploadWorkAuth={(f) => void handleWorkAuthUpload(f)}
            missingWorkAuth={
              section.id === "cover"
              && missingByField.has("workAuthDoc")
            }
            offerLetterUploaded={offerLetterUploaded}
            offerLetterUploading={offerLetterUploading}
            offerLetterError={offerLetterUploadError}
            onUploadOfferLetter={(f) => void handleOfferLetterUpload(f)}
            missingOfferLetter={
              section.id === "appendix1"
              && missingByField.has("offerLetter")
            }
            dlDocUploaded={dlDocUploaded}
            dlDocUploading={dlDocUploading}
            dlDocError={dlDocUploadError}
            onUploadDlDoc={(f) => void handleDlDocUpload(f)}
            missingDlDoc={
              section.id === "appendix3"
              && missingByField.has("dlDoc")
            }
            ssnDocUploaded={ssnDocUploaded}
            ssnDocUploading={ssnDocUploading}
            ssnDocError={ssnDocUploadError}
            onUploadSsnDoc={(f) => void handleSsnDocUpload(f)}
            missingPortalEntries={
              section.id === "appendix4"
              && missingByField.has("portalEntries")
            }
            phase={app.phase ?? 1}
            onBack={() => setCurrentStep((s) => Math.max(0, s - 1))}
            onNext={() =>
              setCurrentStep((s) =>
                Math.min(visibleSections.length - 1, s + 1),
              )
            }
            canAdvance={canAdvance}
            submitting={submitting}
            isFirstStep={currentStep === 0}
            onReadProgress={handleReadingProgress}
            missingFieldKeys={missingByField}
            missingAffirmation={
              section.affirmationFlag
                ? missingByAffirmation.has(section.affirmationFlag)
                : false
            }
            missingSignature={
              section.id === "main-agreement"
              && Boolean(liveMissing?.missingSignature)
            }
            missingCheques={
              section.id === "appendix5"
              && (missingByField.has("cheques") || missingByField.has("chequeUpload"))
            }
          />
        )}
        {liveMissing && (
          <div className="max-w-[min(100%,860px)] mt-6">
            <MissingItemsPanel
              missing={liveMissing}
              visibleSections={visibleSections}
              onJumpToItem={(idx, anchorId) => {
                setCurrentStep(idx);
                window.setTimeout(() => {
                  if (!anchorId) return;
                  const el = document.getElementById(anchorId);
                  if (!el) return;
                  el.scrollIntoView({ behavior: "smooth", block: "center" });
                  // Briefly pulse the target so the eye finds it after
                  // the section swap + scroll.
                  el.classList.add("ring-2", "ring-sage-copper", "ring-offset-2");
                  window.setTimeout(() => {
                    el.classList.remove("ring-2", "ring-sage-copper", "ring-offset-2");
                  }, 1800);
                  // Focus inputs / checkboxes / buttons; ignore other
                  // wrappers (the .scrollIntoView is enough for those).
                  const tag = el.tagName.toLowerCase();
                  if (tag === "input" || tag === "textarea" || tag === "select"
                      || tag === "button") {
                    (el as HTMLElement).focus();
                  } else {
                    // The FieldInput wrapper is tabindex=-1 + focusable
                    // so the affirmation label / inner input gets the
                    // ring outline without trapping keyboard nav.
                    const focusable = el.querySelector<HTMLElement>(
                      "input, textarea, select, button",
                    );
                    focusable?.focus();
                  }
                }, 80);
              }}
            />
          </div>
        )}
      </section>

      <FooterNav
        currentStep={currentStep}
        total={visibleSections.length}
        canAdvance={isReviewStep ? allComplete : canAdvance}
        saveStatus={saveStatus}
        submitting={submitting}
        onBack={() => setCurrentStep((s) => Math.max(0, s - 1))}
        onNext={() =>
          setCurrentStep((s) =>
            Math.min(visibleSections.length - 1, s + 1),
          )
        }
        onSubmit={attemptSubmit}
        isReviewStep={isReviewStep}
      />

      {templateOpen && (
        <AgreementTemplateModal onClose={() => setTemplateOpen(false)} />
      )}
    </main>
  );
}

// ── Stepper ──────────────────────────────────────────────────

/**
 * Build S — segmented progress + section navigator. A 10-segment
 * progress bar shows the consultant's place (completed = navy,
 * current = copper, upcoming = light grey) and a current-section
 * title button opens a hover-peek + jump-back popover. Upcoming
 * sections are listed but locked; only completed + the current
 * section are clickable.
 */
function Stepper({
  sections,
  currentStep,
  readPct,
  sectionsWithMissing,
  onJump,
}: {
  sections: { id: string; title: string; step: number; complete: boolean }[];
  currentStep: number;
  readPct: number;
  /** Build W — sections that have at least one item missing from the
   *  last failed submit. Renders a copper dot on the segment + menu
   *  row so the consultant sees at a glance which sections need work. */
  sectionsWithMissing: Set<string>;
  onJump: (i: number) => void;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [peek, setPeek] = useState<number | null>(null);
  const current = sections[currentStep];
  const total = sections.length;
  const closeMenu = useCallback(() => setMenuOpen(false), []);

  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") closeMenu();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [menuOpen, closeMenu]);

  return (
    <nav
      aria-label="Agreement sections"
      className="sticky top-12 z-20 border-b backdrop-blur"
      style={{
        background: "rgba(255,255,255,0.92)",
        borderColor: "var(--line)",
      }}
    >
      <div className="max-w-[1320px] mx-auto px-5 sm:px-10 pt-3.5 pb-4">
        <div className="flex items-baseline justify-between gap-4">
          <div className="relative shrink-0 min-w-0">
            <button
              type="button"
              onClick={() => setMenuOpen((v) => !v)}
              aria-haspopup="menu"
              aria-expanded={menuOpen}
              className="group inline-flex items-baseline gap-2 text-left cursor-pointer max-w-full"
            >
              <span
                className="display font-semibold text-[18px] sm:text-[19px] truncate"
                style={{ color: "var(--navy-deep)" }}
              >
                {current?.title ?? ""}
              </span>
              <span
                className="text-[11.5px] uppercase tracking-[0.08em] whitespace-nowrap"
                style={{ color: "var(--muted)" }}
              >
                Section {currentStep + 1} of {total}
              </span>
              <ChevronDown
                size={13}
                className={
                  "shrink-0 motion-safe:transition-transform motion-safe:duration-150 motion-safe:ease-out group-hover:text-[color:var(--copper)] "
                  + (menuOpen ? "rotate-180" : "")
                }
                style={{ color: "var(--muted)" }}
              />
            </button>
            {menuOpen && (
              <>
                <div
                  className="fixed inset-0 z-10"
                  aria-hidden
                  onClick={closeMenu}
                />
                <div
                  role="menu"
                  className="absolute left-0 top-full mt-2 z-20 w-[min(86vw,360px)] rounded-xl bg-white shadow-[0_18px_40px_-18px_rgba(15,31,68,0.28)] overflow-hidden"
                  style={{ border: "1px solid var(--line)" }}
                >
                  <ol className="max-h-[60vh] overflow-y-auto py-1">
                    {sections.map((s, i) => {
                      const active = i === currentStep;
                      const hasMissing = sectionsWithMissing.has(s.id);
                      // Build W — sections flagged from a failed submit
                      // are always navigable so the consultant can fix
                      // them even if other later sections are locked.
                      const locked = i > currentStep && !s.complete && !hasMissing;
                      return (
                        <li key={s.id}>
                          <button
                            type="button"
                            onClick={() => {
                              if (locked) return;
                              closeMenu();
                              onJump(i);
                            }}
                            role="menuitem"
                            disabled={locked}
                            className={
                              "w-full grid grid-cols-[auto_1fr_auto] items-center gap-3 px-4 py-2.5 text-left text-[14px] motion-safe:transition-colors "
                              + (active
                                ? "bg-[color:var(--copper-wash)] text-[color:var(--navy-deep)]"
                                : locked
                                  ? "text-[color:var(--muted)] cursor-not-allowed"
                                  : "text-[color:var(--ink)] hover:bg-[color:var(--page)] cursor-pointer")
                            }
                          >
                            <span
                              aria-hidden
                              className="inline-flex items-center justify-center h-5 w-5"
                            >
                              {hasMissing ? (
                                <AlertCircle size={14} style={{ color: "var(--copper-deep)" }} />
                              ) : s.complete ? (
                                <CheckCircle2 size={14} style={{ color: "var(--navy)" }} />
                              ) : active ? (
                                <span
                                  className="h-2.5 w-2.5 rounded-full"
                                  style={{ background: "var(--copper)" }}
                                />
                              ) : (
                                <Lock size={12} style={{ color: "var(--muted)" }} />
                              )}
                            </span>
                            <span className="display text-[14.5px] leading-snug truncate inline-flex items-center gap-1.5">
                              {s.title}
                              {hasMissing && !active && (
                                <span
                                  className="text-[10px] font-semibold uppercase tracking-wider px-1.5 py-0.5 rounded"
                                  style={{
                                    background: "var(--copper-wash)",
                                    color: "var(--copper-deep)",
                                  }}
                                >
                                  Needs attention
                                </span>
                              )}
                            </span>
                            <span
                              className="text-[11px] tabular-nums"
                              style={{ color: "var(--muted)" }}
                            >
                              {String(s.step).padStart(2, "0")}
                            </span>
                          </button>
                        </li>
                      );
                    })}
                  </ol>
                </div>
              </>
            )}
          </div>
          <span
            className="text-[11.5px] tabular-nums whitespace-nowrap"
            style={{ color: "var(--muted)" }}
            aria-live="polite"
          >
            {readPct >= 100 ? "Fully read, you can continue." : `${Math.round(readPct)}% read`}
          </span>
        </div>
        <div className="mt-3 relative">
          <div
            className="grid gap-1.5"
            style={{ gridTemplateColumns: `repeat(${total}, minmax(0, 1fr))` }}
          >
            {sections.map((s, i) => {
              const active = i === currentStep;
              const past = i < currentStep;
              const hasMissing = sectionsWithMissing.has(s.id);
              return (
                <button
                  key={s.id}
                  type="button"
                  onMouseEnter={() => setPeek(i)}
                  onMouseLeave={() => setPeek((p) => (p === i ? null : p))}
                  onFocus={() => setPeek(i)}
                  onBlur={() => setPeek((p) => (p === i ? null : p))}
                  onClick={() => {
                    // Build W — missing sections are always navigable.
                    if (i > currentStep && !s.complete && !hasMissing) return;
                    onJump(i);
                  }}
                  aria-label={
                    `Section ${s.step}: ${s.title}`
                    + (hasMissing ? " — needs attention" : "")
                  }
                  className="relative h-3 flex items-center cursor-pointer"
                >
                  <span
                    className="h-1.5 w-full rounded-full motion-safe:transition-colors motion-safe:duration-300 motion-safe:ease-out"
                    style={{
                      background: hasMissing
                        ? "var(--copper-deep)"
                        : s.complete
                          ? "var(--navy)"
                          : active
                            ? "var(--copper)"
                            : past
                              ? "rgba(27,42,92,0.30)"
                              : "var(--line)",
                    }}
                  />
                  {hasMissing && (
                    <span
                      aria-hidden
                      className="absolute -top-1.5 left-1/2 -translate-x-1/2 h-2 w-2 rounded-full ring-2 ring-white"
                      style={{ background: "var(--copper-deep)" }}
                    />
                  )}
                </button>
              );
            })}
          </div>
          {peek !== null && (
            <div
              role="tooltip"
              className="pointer-events-none absolute -top-9 z-20 px-2 py-1 rounded-md text-[11.5px] text-white whitespace-nowrap shadow-md motion-safe:transition-opacity"
              style={{
                left: `calc(${(peek + 0.5) * (100 / total)}% )`,
                transform: "translateX(-50%)",
                background: "var(--navy-deep)",
              }}
            >
              {sections[peek].title}
            </div>
          )}
        </div>
      </div>
    </nav>
  );
}

// ── Section step ─────────────────────────────────────────────

function SectionStep({
  section,
  content,
  form,
  reqs,
  touched,
  revealed,
  onField,
  onTouched,
  onRevealed,
  onAffirm,
  onSignature,
  onLegalName,
  consultantEmail,
  effectiveDateText,
  onOpenTemplate,
  chequeEntries,
  chequeUploadingIndex,
  chequeUploadError,
  onUploadChequeAt,
  onChequeMetadataChange,
  workAuthUploaded,
  workAuthUploading,
  workAuthError,
  onUploadWorkAuth,
  missingWorkAuth,
  offerLetterUploaded,
  offerLetterUploading,
  offerLetterError,
  onUploadOfferLetter,
  missingOfferLetter,
  dlDocUploaded,
  dlDocUploading,
  dlDocError,
  onUploadDlDoc,
  missingDlDoc,
  ssnDocUploaded,
  ssnDocUploading,
  ssnDocError,
  onUploadSsnDoc,
  missingPortalEntries,
  phase,
  onBack,
  onNext,
  canAdvance,
  submitting,
  isFirstStep,
  onReadProgress,
  missingFieldKeys,
  missingAffirmation,
  missingSignature,
  missingCheques,
  revision,
}: {
  section: AgreementSection;
  content: AgreementContent | null;
  form: FormState;
  reqs: EffectiveRequirements;
  touched: Set<string>;
  revealed: Set<string>;
  onField: (key: string, value: string) => void;
  onTouched: (key: string) => void;
  onRevealed: (key: string, on: boolean) => void;
  onAffirm: (flag: AffirmationFlag, value: boolean) => void;
  onSignature: (dataUrl: string | null) => void;
  onLegalName: (value: string) => void;
  consultantEmail: string;
  effectiveDateText: string;
  onOpenTemplate: () => void;
  chequeEntries: ChequeEntry[];
  chequeUploadingIndex: number | null;
  chequeUploadError: string;
  onUploadChequeAt: (index: number, file: File) => void;
  onChequeMetadataChange: (
    index: number,
    patch: { number?: string; date?: string },
  ) => void;
  /** Build I — work-authorization document upload (Personal Information). */
  workAuthUploaded: boolean;
  workAuthUploading: boolean;
  workAuthError: string;
  onUploadWorkAuth: (file: File) => void;
  missingWorkAuth: boolean;
  /** Build I — Phase 2 Employment offer-letter upload. */
  offerLetterUploaded: boolean;
  offerLetterUploading: boolean;
  offerLetterError: string;
  onUploadOfferLetter: (file: File) => void;
  missingOfferLetter: boolean;
  /** Build J — Background Check DL/State-ID (required) + SSN (optional) uploads. */
  dlDocUploaded: boolean;
  dlDocUploading: boolean;
  dlDocError: string;
  onUploadDlDoc: (file: File) => void;
  missingDlDoc: boolean;
  ssnDocUploaded: boolean;
  ssnDocUploading: boolean;
  ssnDocError: string;
  onUploadSsnDoc: (file: File) => void;
  /** Build J — Appendix 4 needs ≥1 complete portal entry (flag on submit). */
  missingPortalEntries: boolean;
  phase: number;
  onBack: () => void;
  onNext: () => void;
  canAdvance: boolean;
  /** Build W — submit-time gaps the wizard should flag in place. */
  missingFieldKeys: Set<string>;
  missingAffirmation: boolean;
  missingSignature: boolean;
  missingCheques: boolean;
  /** Build V — main-agreement step hidden this round (section-restricted
   *  revision); the per-section signature preview is covered by the
   *  review-step execution signature, not the (absent) main-step primary. */
  revision: boolean;
  submitting: boolean;
  isFirstStep: boolean;
  onReadProgress: (pct: number) => void;
}) {
  const isCoverStep = section.id === "cover";
  const appendixOptional = Boolean(
    section.appendixKey && !reqs[section.appendixKey],
  );
  const appendixTouched = isAppendixTouched(section, form);
  const showOptionalBadge = appendixOptional && !appendixTouched;
  const showAllOrNothingNotice = appendixOptional && appendixTouched;
  const showPhase2RequiredBadge =
    phase === 2 && Boolean(section.appendixKey) && !appendixOptional;
  const hasFields = section.fields.length > 0;
  const blocks = content?.sections?.[section.id];

  // Build J — "Same as residence address" toggle for the Background Check
  // current address. When on, the residence values are copied into the
  // current-address fields and those fields are locked.
  const sameAsResidence = form.fields["bgCurrentSameAsResidence"] === "true";
  const onToggleSameAsResidence = (checked: boolean) => {
    if (checked) {
      onField("bgCurrentAddressLine1", form.fields["addressLine1"] ?? "");
      onField("bgCurrentAddressLine2", form.fields["addressLine2"] ?? "");
      onField("bgCurrentAddressCity", form.fields["addressCity"] ?? "");
      onField("bgCurrentAddressState", form.fields["addressState"] ?? "");
      onField("bgCurrentAddressZip", form.fields["addressZip"] ?? "");
    }
    onField("bgCurrentSameAsResidence", checked ? "true" : "false");
  };

  // Build S — track scroll progress inside the document column so the
  // header's "% read" label + the left-edge rail fill stay in sync.
  // Build U — sectionScrolled re-arms the per-section affirmation gate:
  // false on every section change, true once the consultant has read
  // ~95% of the section's content.
  const scrollerRef = useRef<HTMLDivElement | null>(null);
  const [sectionScrolled, setSectionScrolled] = useState(false);
  const recomputeProgress = useCallback(() => {
    const el = scrollerRef.current;
    if (!el) return;
    const scrollable = el.scrollHeight - el.clientHeight;
    if (scrollable <= 1) {
      // Content fits without scrolling — treat as "fully read" so the
      // affirmation isn't permanently locked on short sections.
      setSectionScrolled(true);
      onReadProgress(100);
      return;
    }
    const pct = Math.min(100, (el.scrollTop / scrollable) * 100);
    if (pct >= 95) setSectionScrolled(true);
    onReadProgress(pct);
  }, [onReadProgress]);

  // Build U — every section change resets the reading panel to the
  // top and re-arms the scroll-gated affirmation. setTimeout lets the
  // new section's content paint before we measure.
  useEffect(() => {
    const el = scrollerRef.current;
    if (el) {
      el.scrollTop = 0;
      el.style.setProperty("--read", "0%");
    }
    setSectionScrolled(false);
    onReadProgress(0);
    const t = window.setTimeout(recomputeProgress, 50);
    return () => window.clearTimeout(t);
  }, [section.id, onReadProgress, recomputeProgress]);

  useEffect(() => {
    const el = scrollerRef.current;
    if (!el) return;
    const obs = new ResizeObserver(() => recomputeProgress());
    obs.observe(el);
    Array.from(el.querySelectorAll("p, h1, h2, h3, h4")).forEach((node) =>
      obs.observe(node),
    );
    return () => obs.disconnect();
  }, [recomputeProgress, blocks]);

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1.62fr_0.92fr] gap-6 lg:gap-10 items-start max-w-[1320px] mx-auto">
      {/* ── Reading column (dominant) ─────────────────────────── */}
      <article
        className="relative bg-[var(--paper)] rounded-[14px] motion-safe:transition-opacity select-none"
        aria-labelledby="section-title"
        // Build Y — read-only agreement text is non-copyable (deterrent
        // only). The inputs/affirmation/signature/nav live in the
        // separate sticky rail, so this does not affect form controls.
        onCopy={(e) => e.preventDefault()}
        onCut={(e) => e.preventDefault()}
        onContextMenu={(e) => e.preventDefault()}
        onDragStart={(e) => e.preventDefault()}
        style={{
          border: "1px solid var(--line)",
          boxShadow: "0 18px 36px -28px rgba(15,31,68,0.18)",
          userSelect: "none",
          WebkitUserSelect: "none",
          MozUserSelect: "none",
        }}
      >
        {/* Build S — slim left-edge reading-progress rail. Sits inside
            the rounded border; copper→navy gradient on the filled
            portion communicates "you've read this far". */}
        <div
          aria-hidden
          className="absolute left-0 top-0 h-full w-[3px] overflow-hidden rounded-l-[14px]"
          style={{ background: "var(--line)" }}
        >
          <div
            className="w-full motion-safe:transition-[height] motion-safe:duration-200 motion-safe:ease-out"
            style={{
              height: "var(--read, 0%)",
              background:
                "linear-gradient(180deg, var(--copper) 0%, var(--navy) 100%)",
            }}
          />
        </div>

        <div
          ref={scrollerRef}
          onScroll={recomputeProgress}
          className="overflow-y-auto px-7 sm:px-12 lg:px-14 py-7 sm:py-10 lg:py-12 lg:max-h-[min(74vh,820px)]"
          style={{ ["--read" as string]: `0%` }}
          // Wire the rail fill height to the scroll position. We can't
          // set this as React state without thrashing render cost; the
          // onScroll handler updates the CSS variable directly.
          onScrollCapture={(e) => {
            const el = e.currentTarget;
            const max = el.scrollHeight - el.clientHeight;
            const pct = max <= 1 ? 100 : Math.min(100, (el.scrollTop / max) * 100);
            el.style.setProperty("--read", `${pct}%`);
          }}
        >
          <p
            className="text-[11px] font-medium uppercase tracking-[0.16em]"
            style={{ color: "var(--muted)" }}
          >
            Section {section.step}
          </p>
          <h1
            id="section-title"
            className="display font-semibold text-[30px] sm:text-[36px] leading-[1.12] mt-2 [text-wrap:balance]"
            style={{ color: "var(--navy-deep)" }}
          >
            {section.title}
          </h1>
          {(showOptionalBadge || showPhase2RequiredBadge) && (
            <div className="mt-3 flex gap-2 flex-wrap">
              {showOptionalBadge && (
                <span
                  className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-medium"
                  style={{ background: "var(--line)", color: "var(--muted)" }}
                >
                  Optional. You can skip this section.
                </span>
              )}
              {showPhase2RequiredBadge && (
                <span
                  className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-medium"
                  style={{
                    background: "var(--copper-wash)",
                    color: "var(--copper-deep)",
                  }}
                >
                  Required for Phase 2
                </span>
              )}
            </div>
          )}

          {/* Plain-English summary (copper-wash aside) */}
          <aside
            className="mt-7 rounded-[12px] px-5 sm:px-6 py-4 sm:py-5 max-w-[68ch]"
            style={{ background: "var(--copper-wash)" }}
          >
            <p
              className="text-[11px] font-semibold tracking-[0.12em] uppercase"
              style={{ color: "var(--copper-deep)" }}
            >
              In plain terms
            </p>
            <p
              className="mt-2 display text-[16.5px] leading-[1.55] [text-wrap:pretty]"
              style={{ color: "var(--ink)" }}
            >
              {section.summary}
            </p>
            <p
              className="mt-3 text-[13px] leading-[1.55] [text-wrap:pretty]"
              style={{ color: "var(--muted)" }}
            >
              <span className="font-semibold" style={{ color: "var(--ink)" }}>
                Why this matters.{" "}
              </span>
              {section.why}
            </p>
          </aside>

          {isCoverStep && (
            <div
              className="mt-6 inline-flex items-start gap-2 text-[13px]"
              style={{ color: "var(--muted)" }}
            >
              <Lock size={13} className="mt-0.5 shrink-0" />
              <span>
                Effective date{" "}
                <span
                  className="font-semibold tabular-nums"
                  style={{ color: "var(--navy)" }}
                >
                  {effectiveDateText || "to be set"}
                </span>
                . Set by Sage IT.
              </span>
            </div>
          )}

          {blocks && blocks.length > 0 ? (
            <div className="mt-8 max-w-[68ch] legal">
              <div
                className="text-[10px] font-medium tracking-[0.18em] uppercase"
                style={{ color: "var(--muted)" }}
              >
                The contract
              </div>
              <div
                className="mt-3 pt-5"
                style={{ borderTop: "1px solid var(--line)" }}
              >
                <AgreementClauseView
                  blocks={blocks}
                  values={content.values}
                  fields={form.fields}
                  signature={form.signature}
                />
              </div>
              <button
                type="button"
                onClick={onOpenTemplate}
                className="mt-6 inline-flex items-center gap-1.5 text-[12px] font-medium motion-safe:transition-colors underline underline-offset-[3px]"
                style={{ color: "var(--muted)" }}
              >
                <FileText size={12} /> View the formatted PDF
              </button>
            </div>
          ) : (
            <div className="mt-8 max-w-[68ch] legal">
              <p
                className="text-[16px] leading-[1.7]"
                style={{ color: "var(--ink)" }}
              >
                {section.summary}
              </p>
              <button
                type="button"
                onClick={onOpenTemplate}
                className="mt-6 inline-flex items-center gap-1.5 text-[12px] font-medium underline underline-offset-[3px]"
                style={{ color: "var(--muted)" }}
              >
                <FileText size={12} /> View the formatted PDF
              </button>
            </div>
          )}
        </div>
      </article>

      {/* ── Sticky action rail ─────────────────────────────────── */}
      <aside
        className="lg:sticky lg:top-[145px] lg:self-start min-w-0"
        style={{ ["--rail-min" as string]: "330px" }}
      >
        <div
          className="bg-white rounded-[14px] px-5 sm:px-6 py-5 space-y-5"
          style={{ border: "1px solid var(--line)" }}
        >
          {showAllOrNothingNotice && (
            <div className="rounded-md bg-[#FBF1E8] border border-sage-copper/30 px-3 py-2.5 text-[12px] text-sage-copper-deep flex items-start gap-2">
              <AlertCircle size={13} className="mt-0.5 shrink-0" />
              <span>
                This section is optional, but you have started filling it.
                Complete every required field and tick the affirmation, or
                clear all fields to skip.
              </span>
            </div>
          )}

          {section.id === "appendix3" && (
            <label className="flex items-start gap-2.5 rounded-md border border-stone-200 bg-stone-50 px-3 py-2.5 cursor-pointer">
              <input
                type="checkbox"
                checked={sameAsResidence}
                onChange={(e) => onToggleSameAsResidence(e.target.checked)}
                className="mt-0.5 h-4 w-4 accent-sage-navy"
              />
              <span className="text-[12px] text-stone-700">
                <span className="font-semibold text-sage-navy">
                  Current address is the same as my residence address
                </span>
                <span className="block text-stone-500">
                  Uses your residence address as your current address (kept in
                  sync). Uncheck to enter a different address.
                </span>
              </span>
            </label>
          )}

          {hasFields && (
            <div>
              <h3 className="font-serif text-base text-sage-navy">
                Your details
              </h3>
              <p className="mt-1 text-[12px] text-stone-500">
                They flow into the contract as you type.
              </p>
              <div className="mt-4 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2 gap-x-4 gap-y-4">
                {section.fields.map((field) => (
                  <FieldInput
                    key={field.key}
                    field={field}
                    effectivelyRequired={isFieldRequired(field, section, form, reqs)}
                    value={
                      field.key === "consultantEmail"
                        ? consultantEmail
                        : sameAsResidence && CURRENT_ADDRESS_KEYS.has(field.key)
                          ? form.fields[RESIDENCE_KEY_FOR[field.key]] ?? ""
                          : form.fields[field.key] ?? ""
                    }
                    onChange={(v) => onField(field.key, v)}
                    onBlur={() => onTouched(field.key)}
                    touched={touched.has(field.key)}
                    revealed={revealed.has(field.key)}
                    onRevealToggle={(on) => onRevealed(field.key, on)}
                    needsAttention={missingFieldKeys.has(field.key)}
                    locked={sameAsResidence && CURRENT_ADDRESS_KEYS.has(field.key)}
                  />
                ))}
              </div>
            </div>
          )}

          {section.id === "cover" && (
            <div id="field-workAuthDoc">
              <DocUploadBlock
                title="Work-authorization document"
                description="Upload a copy of your current work-authorization document — an image or PDF (≤10 MB)."
                inputId="workauth-file"
                uploaded={workAuthUploaded}
                uploading={workAuthUploading}
                error={workAuthError}
                onUpload={onUploadWorkAuth}
                needsAttention={missingWorkAuth}
              />
            </div>
          )}

          {section.id === "appendix1" && (
            <div id="field-offerLetter">
              <DocUploadBlock
                title="Offer letter"
                description="Upload your offer letter — an image or PDF (≤10 MB)."
                inputId="offerletter-file"
                uploaded={offerLetterUploaded}
                uploading={offerLetterUploading}
                error={offerLetterError}
                onUpload={onUploadOfferLetter}
                needsAttention={missingOfferLetter}
              />
            </div>
          )}

          {section.id === "appendix3" && (
            <div className="space-y-4">
              <div id="field-dlDoc">
                <DocUploadBlock
                  title="Driver's License / State ID document"
                  description="Upload a copy of your Driver's License or State ID — an image or PDF (≤10 MB)."
                  inputId="dldoc-file"
                  uploaded={dlDocUploaded}
                  uploading={dlDocUploading}
                  error={dlDocError}
                  onUpload={onUploadDlDoc}
                  needsAttention={missingDlDoc}
                />
              </div>
              <div id="field-ssnDoc">
                <DocUploadBlock
                  title="SSN document"
                  description="Optional — upload a copy of your SSN card or document if you have one (≤10 MB). You can submit without it."
                  inputId="ssndoc-file"
                  uploaded={ssnDocUploaded}
                  uploading={ssnDocUploading}
                  error={ssnDocError}
                  onUpload={onUploadSsnDoc}
                  needsAttention={false}
                  optional
                />
              </div>
            </div>
          )}

          {section.id === "appendix4" && (
            <div id="field-portalEntries">
              <PortalEntriesBlock
                value={form.fields["portalEntries"] ?? ""}
                onChange={(json) => onField("portalEntries", json)}
                needsAttention={missingPortalEntries}
              />
            </div>
          )}

          {section.id === "appendix5" && (
            <div id="field-cheques">
              <ChequeListBlock
                count={parseChequeCount(form.fields["securityCheckCount"])}
                entries={chequeEntries}
                uploadingIndex={chequeUploadingIndex}
                error={chequeUploadError}
                onUploadAt={onUploadChequeAt}
                onMetadataChange={onChequeMetadataChange}
                needsAttention={missingCheques}
              />
            </div>
          )}

          {section.requiresSignature && (
            <div id="signature-block">
              <SignatureBlock
                signature={form.signature}
                legalName={form.signedLegalName}
                onSignature={onSignature}
                onLegalName={onLegalName}
                needsAttention={missingSignature}
              />
            </div>
          )}

          {section.requiresAffirmation && !section.requiresSignature && (
            <SignaturePreviewBlock signature={form.signature} revision={revision} />
          )}

          {section.requiresAffirmation && section.affirmationFlag && (
            <AffirmationBlock
              flag={section.affirmationFlag}
              checked={form.affirmations[section.affirmationFlag]}
              onChange={(v) => onAffirm(section.affirmationFlag!, v)}
              scrollGated={!sectionScrolled}
              needsAttention={missingAffirmation}
            />
          )}

          {/* Desktop Back / Continue. Mobile uses the sticky FooterNav. */}
          <div className="hidden lg:flex items-center justify-between pt-2 border-t border-stone-100">
            <button
              type="button"
              onClick={onBack}
              disabled={isFirstStep || submitting}
              className="text-[13px] font-medium text-stone-500 hover:text-sage-navy motion-safe:transition-colors disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer inline-flex items-center gap-1"
            >
              <ArrowLeft size={13} /> Back
            </button>
            <button
              type="button"
              onClick={onNext}
              disabled={!canAdvance || submitting}
              className={
                "inline-flex items-center gap-1.5 px-4 py-2.5 rounded-md text-sm font-semibold motion-safe:transition-colors cursor-pointer "
                + (canAdvance
                  ? "bg-sage-navy text-white hover:bg-sage-navy-deep"
                  : "bg-stone-100 text-stone-400 cursor-not-allowed")
              }
            >
              Continue <ArrowRight size={13} />
            </button>
          </div>
        </div>
      </aside>
    </div>
  );
}

// Build N — MM-DD-YYYY masked date input. The form value is ISO
// (yyyy-MM-dd); the field renders + accepts MM-DD-YYYY and converts at the
// edges. Emits "" until a complete, valid calendar date is typed.
function DateMaskInput({
  value,
  onChange,
  onBlur,
  readOnly,
  className,
}: {
  value: string;
  onChange: (iso: string) => void;
  onBlur: () => void;
  readOnly?: boolean;
  className?: string;
}) {
  const [text, setText] = useState(() => isoToUsInput(value));
  // The ISO we last emitted, so an external value change (e.g. a reset)
  // re-syncs the display but our own keystroke echo does not loop.
  const emittedRef = useRef(value);
  useEffect(() => {
    if (value !== emittedRef.current) {
      emittedRef.current = value;
      setText(isoToUsInput(value));
    }
  }, [value]);
  return (
    <input
      type="text"
      inputMode="numeric"
      value={text}
      placeholder="MM-DD-YYYY"
      maxLength={10}
      readOnly={readOnly}
      onChange={(e) => {
        const masked = maskMmDdYyyy(e.target.value);
        setText(masked);
        const iso = usInputToIso(masked);
        emittedRef.current = iso;
        onChange(iso);
      }}
      onBlur={onBlur}
      className={className}
    />
  );
}

// ── Field input ──────────────────────────────────────────────

function FieldInput({
  field,
  effectivelyRequired,
  value,
  onChange,
  onBlur,
  touched,
  revealed,
  onRevealToggle,
  needsAttention = false,
  locked = false,
}: {
  field: SectionField;
  /** Per-app required (honours readOnly, field.required, SSN gate, optional-section gate). */
  effectivelyRequired: boolean;
  value: string;
  onChange: (value: string) => void;
  onBlur: () => void;
  touched: boolean;
  revealed: boolean;
  onRevealToggle: (on: boolean) => void;
  /** Build W — flagged by a failed submit; render the copper "needs attention" border. */
  needsAttention?: boolean;
  /** Build J — dynamically disabled (e.g. "Same as residence" lock). */
  locked?: boolean;
}) {
  // Build J — effective read-only = statically ERM-set OR dynamically locked.
  const ro = field.readOnly || locked;
  const wide =
    field.type === "textarea" ||
    field.key === "residenceAddress" ||
    field.key === "bgCurrentAddress" ||
    field.key === "portalAuthorizedActions" ||
    field.key === "customScopeNotes";

  const invalid =
    touched && effectivelyRequired && !isFieldValueValid(field, value);
  const errorMsg = invalid
    ? field.type === "email"
      ? "Enter a valid email address."
      : field.type === "routing"
        ? "Routing number must be exactly 9 digits."
        : field.type === "account"
          ? "Account number must be exactly 10 digits."
          : field.type === "ssn"
            ? "Use letters and numbers only (no spaces or symbols)."
            : field.key === "addressZip" || field.key === "bgCurrentAddressZip"
              ? "Enter a 5-digit ZIP (optionally ZIP+4)."
            : field.type === "id-type"
              ? "Pick one."
              : "This field is required."
    : "";

  const baseInputClasses =
    "w-full px-3 py-2.5 text-[14px] rounded-md border bg-white text-stone-900 placeholder:text-stone-400 focus:outline-none focus:ring-2 focus:ring-sage-copper/40 focus:border-sage-copper motion-safe:transition-colors " +
    (invalid
      ? "border-red-300 bg-red-50/40"
      : needsAttention
        ? "border-[var(--copper)] bg-[color:var(--copper-wash)]/60"
        : ro
          ? "border-stone-200 bg-stone-100 text-stone-600 cursor-not-allowed"
          : "border-stone-300");

  let control: ReactNode;
  // Build G — special types rendered outside the standard input grid
  // (the wizard renders ChequeUploadBlock as a sibling for type=file).
  if (field.type === "file") {
    return null;
  }
  if (field.type === "textarea") {
    control = (
      <textarea
        rows={3}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        placeholder={field.placeholder}
        readOnly={ro}
        className={baseInputClasses + " min-h-[80px]"}
      />
    );
  } else if (field.type === "id-type") {
    const current = value === "DL" || value === "STATE_ID" ? value : "";
    const pillBase =
      "flex-1 px-3 py-2 text-xs font-semibold rounded-md border transition-colors cursor-pointer ";
    control = (
      <div className="flex gap-2">
        {(
          [
            ["DL", "Driver's License"],
            ["STATE_ID", "State ID"],
          ] as const
        ).map(([code, label]) => {
          const selected = current === code;
          return (
            <button
              key={code}
              type="button"
              disabled={ro}
              onClick={() => {
                if (ro) return;
                onChange(code);
                onBlur();
              }}
              className={
                pillBase
                + (ro ? "opacity-60 cursor-not-allowed " : "")
                + (selected
                  ? "bg-sage-navy text-white border-sage-navy"
                  : "bg-white text-gray-700 border-stone-300 hover:border-sage-navy/50")
              }
            >
              {label}
            </button>
          );
        })}
      </div>
    );
  } else if (field.type === "select") {
    control = (
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        disabled={ro}
        className={baseInputClasses}
      >
        <option value="">Select…</option>
        {field.options?.map((opt) => (
          <option key={opt} value={opt}>
            {opt}
          </option>
        ))}
      </select>
    );
  } else if (field.type === "date") {
    // Build N — MM-DD-YYYY masked input (stores ISO), not a native picker,
    // so the input always shows US format regardless of browser locale.
    control = (
      <DateMaskInput
        value={value}
        onChange={onChange}
        onBlur={onBlur}
        readOnly={ro}
        className={baseInputClasses}
      />
    );
  } else if (field.sensitive) {
    const inputType = revealed ? "text" : "password";
    // Build G — auto-mask sensitive numeric inputs to the strict
    // format the backend now enforces. The mask strips characters
    // that would otherwise produce a "wrong length" error after
    // pasting (e.g. spaces, dashes copied from a bank statement).
    const handleSensitiveChange = (raw: string) => {
      if (field.type === "routing") return onChange(digitsOnly(raw, 9));
      if (field.type === "account") return onChange(digitsOnly(raw, 10));
      // Build W — SSN is strictly alphanumeric; strip everything else.
      if (field.type === "ssn") return onChange(raw.replace(/[^A-Za-z0-9]/g, ""));
      return onChange(raw);
    };
    const ph =
      field.placeholder
      ?? (field.type === "routing"
        ? "9 digits"
        : field.type === "account"
          ? "10 digits"
          : field.type === "ssn"
            ? "Letters and numbers only"
            : undefined);
    control = (
      <div className="relative">
        <input
          type={inputType}
          inputMode={
            field.type === "routing"
            || field.type === "account"
              ? "numeric"
              : undefined
          }
          autoComplete="off"
          value={value}
          onChange={(e) => handleSensitiveChange(e.target.value)}
          onBlur={onBlur}
          placeholder={ph}
          readOnly={ro}
          className={baseInputClasses + " pr-9"}
        />
        <button
          type="button"
          onClick={() => onRevealToggle(!revealed)}
          aria-label={revealed ? "Hide value" : "Reveal value"}
          className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-500 hover:text-sage-navy"
        >
          {revealed ? <EyeOff size={14} /> : <Eye size={14} />}
        </button>
      </div>
    );
  } else {
    control = (
      <input
        type={
          field.type === "email"
            ? "email"
            : field.type === "tel"
              ? "tel"
              : "text"
        }
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        placeholder={field.placeholder}
        readOnly={ro}
        className={baseInputClasses}
      />
    );
  }

  return (
    <div
      id={`field-${field.key}`}
      tabIndex={-1}
      className={
        "outline-none "
        + (wide ? "md:col-span-2 lg:col-span-1 xl:col-span-2" : "")
      }
    >
      <label className="block text-[12px] font-medium text-stone-700 mb-1.5">
        {field.label}
        {effectivelyRequired && (
          <span className="text-sage-copper-deep ml-1" aria-hidden>
            *
          </span>
        )}
        {field.readOnly && (
          <span className="ml-1.5 inline-flex items-center gap-0.5 text-[10px] font-medium text-stone-500">
            <Lock size={9} /> Set by Sage IT
          </span>
        )}
        {field.sensitive && (
          <span className="ml-1 inline-flex items-center gap-0.5 text-[10px] font-medium normal-case text-sage-copper-deep">
            <ShieldCheck size={10} /> sensitive
          </span>
        )}
      </label>
      {control}
      {field.help && !errorMsg && !needsAttention && (
        <p className="mt-1 text-[11px] text-gray-500">{field.help}</p>
      )}
      {!errorMsg && needsAttention && (
        <p
          className="mt-1 text-[11px] inline-flex items-center gap-1 font-medium"
          style={{ color: "var(--copper-deep)" }}
        >
          <AlertCircle size={11} /> Needs attention before you submit.
        </p>
      )}
      {errorMsg && (
        <p className="mt-1 text-[11px] text-red-600 inline-flex items-center gap-1">
          <AlertCircle size={11} /> {errorMsg}
        </p>
      )}
    </div>
  );
}

// ── Signature blocks ─────────────────────────────────────────

function SignatureBlock({
  signature,
  legalName,
  onSignature,
  onLegalName,
  needsAttention = false,
}: {
  signature: string | null;
  legalName: string;
  onSignature: (dataUrl: string | null) => void;
  onLegalName: (value: string) => void;
  /** Build W — flagged by a failed submit; render the copper "needs attention" border. */
  needsAttention?: boolean;
}) {
  const [redrawing, setRedrawing] = useState(false);
  const captured = Boolean(signature);
  const shouldShowPad = !captured || redrawing;

  return (
    <div
      className={"space-y-4 motion-safe:transition-colors " + (needsAttention ? "rounded-xl p-4" : "")}
      style={
        needsAttention
          ? {
              border: "1px solid var(--copper)",
              background: "var(--copper-wash)",
            }
          : undefined
      }
    >
      <div>
        <h3
          className="display text-[18px] font-semibold leading-tight"
          style={{ color: "var(--navy)" }}
        >
          Sign once, applied throughout
        </h3>
        <p
          className="text-[12.5px] mt-1 leading-snug"
          style={{ color: "var(--muted)" }}
        >
          Upload an image of your signature, or draw it instead. The same
          signature applies to every signature block in the agreement.
        </p>
        {needsAttention && (
          <p
            className="mt-1.5 text-[11.5px] inline-flex items-center gap-1 font-medium"
            style={{ color: "var(--copper-deep)" }}
          >
            <AlertCircle size={11} /> Your signature is still required.
          </p>
        )}
      </div>

      <div>
        <label
          className="block text-[12px] font-medium mb-1.5"
          style={{ color: "var(--ink)" }}
        >
          Your full legal name{" "}
          <span style={{ color: "var(--copper-deep)" }} aria-hidden>
            *
          </span>
        </label>
        <input
          type="text"
          value={legalName}
          onChange={(e) => onLegalName(e.target.value)}
          className="w-full px-3 py-2.5 text-[14px] rounded-md bg-white motion-safe:transition-colors focus:outline-none"
          style={{
            border: "1px solid var(--line)",
            color: "var(--ink)",
          }}
          onFocus={(e) => {
            e.currentTarget.style.borderColor = "var(--copper)";
            e.currentTarget.style.boxShadow = "0 0 0 3px rgba(200,125,92,0.18)";
          }}
          onBlur={(e) => {
            e.currentTarget.style.borderColor = "var(--line)";
            e.currentTarget.style.boxShadow = "none";
          }}
          placeholder="First Middle Last"
        />
      </div>

      {shouldShowPad ? (
        <div>
          <SignaturePad
            onChange={(data) => {
              onSignature(data);
              if (data) setRedrawing(false);
            }}
            fileInputId="consultant-wizard-sig"
          />
          {redrawing && (
            <button
              type="button"
              onClick={() => setRedrawing(false)}
              className="mt-2 text-[11px] font-semibold motion-safe:transition-colors"
              style={{ color: "var(--muted)" }}
            >
              Cancel re-draw
            </button>
          )}
        </div>
      ) : (
        <div>
          <p
            className="text-[12px] font-medium mb-1.5"
            style={{ color: "var(--muted)" }}
          >
            Your captured signature
          </p>
          <div className="inline-flex items-center gap-3 flex-wrap">
            <div
              className="inline-block rounded-md p-2"
              style={{
                border: "1px dashed var(--line)",
                background: "var(--copper-wash)",
              }}
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={signature!}
                alt="Captured signature"
                style={{ maxHeight: 60, maxWidth: 200 }}
              />
            </div>
            <button
              type="button"
              onClick={() => {
                setRedrawing(true);
                onSignature(null);
              }}
              className="text-[12px] font-medium motion-safe:transition-colors underline underline-offset-[3px]"
              style={{ color: "var(--copper-deep)" }}
            >
              Replace signature
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function SignaturePreviewBlock({
  signature,
  revision = false,
}: {
  signature: string | null;
  revision?: boolean;
}) {
  if (!signature) {
    return (
      <div className="rounded-xl bg-[#FBF1E8] border border-sage-copper/30 px-4 py-3 text-[12.5px] text-sage-copper-deep inline-flex items-start gap-2">
        <AlertCircle size={14} className="mt-0.5 shrink-0" />
        <span>
          {revision
            ? "Your revision signature on the review step will apply to this section."
            : "Add your signature on the main agreement step first. We will apply it here automatically."}
        </span>
      </div>
    );
  }
  return (
    <div className="rounded-xl bg-stone-50 border border-stone-200 px-4 py-3">
      <p className="text-[12px] font-medium text-stone-600">
        Your signature applies to this section
      </p>
      <div className="mt-2 inline-block rounded-md border border-dashed border-stone-300 bg-white p-2">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={signature}
          alt="Signature preview"
          style={{ maxHeight: 44, maxWidth: 160 }}
        />
      </div>
    </div>
  );
}

// ── Build K: Consultant status-only post-submit screens ──────

/**
 * Build K — replaces the post-submit redirect to /dashboard with an
 * inline status screen. The consultant sees only state-driven copy
 * (no PDF, no download) after submitting. Two variants:
 *   - sent: VERIFIED / SIGNED / UPDATED
 *   - accepted: COMPLETED
 */
function ConsultantStatusScreen({
  kind,
  onSignOut,
}: {
  kind: "sent" | "verified" | "accepted" | "expired";
  onSignOut: () => void;
}) {
  const copy = kind === "sent"
    ? {
        eyebrow: "Submitted",
        title: "Your agreement has been sent for verification.",
        body: "We'll review it and update you here once it's accepted. There's nothing else you need to do right now.",
      }
    : kind === "verified"
    ? {
        // Build L — ERM has approved the consultant version. No download;
        // the consultant is simply told it's verified + will hear about
        // any revision.
        eyebrow: "Verified",
        title: "Your agreement is verified.",
        body: "Sage IT has verified your agreement. You'll be notified here if there's any revision. There's nothing else you need to do right now.",
      }
    : kind === "accepted"
      ? {
          eyebrow: "Accepted",
          title: "Your agreement has been accepted.",
          body: "Thank you. Your engagement is now in motion. Sage IT will be in touch with the next steps.",
        }
      : {
          eyebrow: "Expired",
          title: "This invitation has expired.",
          body: "The 15-day window to complete this agreement has passed. Please contact Sage IT and ask them to resend the invitation -- the clock will restart once they do.",
        };
  return (
    <main className="min-h-screen bg-stone-50">
      <meta name="robots" content="noindex,nofollow" />
      <header className="bg-sage-navy text-white">
        <div className="max-w-3xl mx-auto px-6 py-10 flex items-start justify-between gap-4">
          <div>
            <p className="text-xs font-bold uppercase tracking-widest text-sage-copper">
              Sage IT Consultant Portal
            </p>
            <h1 className="font-serif text-3xl mt-2">Your agreement</h1>
          </div>
          <button
            type="button"
            onClick={onSignOut}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-md border border-white/30 hover:bg-white/10 transition-colors"
          >
            Back to dashboard
          </button>
        </div>
      </header>
      <section className="max-w-3xl mx-auto px-6 py-16">
        <div className="bg-white rounded-2xl border border-stone-200 shadow-sm p-8">
          <p className="text-[10px] font-bold uppercase tracking-widest text-sage-copper">
            {copy.eyebrow}
          </p>
          <h2 className="font-serif text-2xl sm:text-3xl text-sage-navy mt-2">
            {copy.title}
          </h2>
          <p className="text-sm text-gray-700 mt-4 leading-relaxed">
            {copy.body}
          </p>
          {kind === "sent" && (
            <p className="text-[11px] text-gray-500 mt-6 inline-flex items-center gap-1">
              <Lock size={12} /> For your security, the signed agreement is held
              in Sage IT's records and is not downloadable from this portal.
            </p>
          )}
        </div>
      </section>
    </main>
  );
}

// ── Build T: e-sign consent gate (shown before the wizard mounts) ──

/**
 * Build T — disclosure + checkbox gate that captures the consultant's
 * formal e-sign consent before they touch the wizard. Records
 * consent_given_at + consent_ip + consent_version on the backend.
 * The certificate of completion later surfaces this record.
 */
function ConsentGate({
  app,
  onAccepted,
  onSignOut,
}: {
  app: ConsultantApplication;
  onAccepted: (updated: ConsultantApplication) => void;
  onSignOut: () => void;
}) {
  const [agreed, setAgreed] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const accept = async () => {
    if (!agreed || submitting) return;
    setSubmitting(true);
    setErr(null);
    try {
      const updated = await recordConsultantConsent(app.applicationId);
      onAccepted(updated);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Couldn't record consent.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="min-h-screen bg-stone-50">
      <meta name="robots" content="noindex,nofollow" />
      <header className="bg-sage-navy text-white">
        <div className="max-w-3xl mx-auto px-6 py-10 flex items-start justify-between gap-4">
          <div>
            <p className="text-xs font-bold uppercase tracking-widest text-sage-copper">
              Sage IT Consultant Portal
            </p>
            <h1 className="font-serif text-3xl mt-2">Before you begin</h1>
          </div>
          <button
            type="button"
            onClick={onSignOut}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-md border border-white/30 hover:bg-white/10 transition-colors"
          >
            Back to dashboard
          </button>
        </div>
      </header>
      <section className="max-w-3xl mx-auto px-6 py-10 sm:py-14">
        <div className="bg-white rounded-2xl border border-stone-200 shadow-sm p-7 sm:p-9">
          <p className="text-[10px] font-bold uppercase tracking-widest text-sage-copper">
            Electronic Records &amp; Signatures Consent
          </p>
          <h2 className="font-serif text-2xl sm:text-3xl text-sage-navy mt-2 leading-snug [text-wrap:balance]">
            This agreement will be signed electronically.
          </h2>
          <div className="text-[14px] text-stone-700 mt-5 leading-relaxed space-y-3 max-w-[64ch]">
            <p>
              By proceeding you agree that electronic records and
              electronic signatures are legally binding under the U.S.
              E-SIGN Act and applicable state UETA equivalents, and that
              your electronic signature on this agreement has the same
              legal effect as a hand-written one.
            </p>
            <p>
              You may withdraw this consent or request a paper copy at
              any time by contacting Sage IT Co. To use this portal you
              will need a modern web browser, an email address you can
              receive verification codes at, and a device able to view
              and save PDF files.
            </p>
          </div>
          <label
            htmlFor="consent-agree"
            className={
              "mt-7 flex items-start gap-3 p-4 rounded-xl border cursor-pointer motion-safe:transition-colors "
              + (agreed
                ? "bg-sage-navy/5 border-sage-navy/40"
                : "bg-white border-stone-300 hover:border-sage-navy/40")
            }
          >
            <input
              id="consent-agree"
              type="checkbox"
              checked={agreed}
              onChange={(e) => setAgreed(e.target.checked)}
              className="mt-0.5 h-4 w-4 accent-sage-navy"
            />
            <span className="text-[14px] leading-snug text-stone-800">
              I agree to transact electronically with Sage IT Co and
              that my electronic signature on this agreement is
              legally binding.
            </span>
          </label>
          {err && (
            <p className="mt-3 text-[12.5px] text-red-700 inline-flex items-center gap-1.5">
              <AlertCircle size={12} /> {err}
            </p>
          )}
          <div className="mt-7 flex items-center justify-between gap-3">
            <p className="text-[11px] text-stone-500">
              Application ID: <span className="font-mono">{app.applicationId.slice(0, 8)}</span>
            </p>
            <button
              type="button"
              onClick={() => void accept()}
              disabled={!agreed || submitting}
              className={
                "inline-flex items-center gap-1.5 px-5 py-2.5 rounded-md text-sm font-semibold motion-safe:transition-colors cursor-pointer "
                + (agreed && !submitting
                  ? "bg-sage-navy text-white hover:bg-sage-navy-deep"
                  : "bg-stone-100 text-stone-400 cursor-not-allowed")
              }
            >
              {submitting ? (
                <Loader2 size={13} className="animate-spin" />
              ) : (
                <CheckCircle2 size={13} />
              )}
              Agree &amp; continue
            </button>
          </div>
        </div>
      </section>
    </main>
  );
}

// Build L — the consultant-version download was retired entirely; no PDF
// is served to the consultant in any state. The old OTP-download component
// was removed; VERIFIED/COMPLETED now render a status-only screen.

// ── Build N: submit-error panel grouped by section ────────────

/**
 * Build N — descriptive, section-grouped, jump-navigable rendering of
 * the structured incomplete-submission payload the backend returns
 * on a failed consultantSubmit. Replaces the old "Some items are
 * still missing." red banner.
 *
 * The map groups missingFields by their owning section (via
 * findSectionForFieldKey), missingAffirmations by their owning
 * section (via findSectionForAffirmation), and the two signature
 * flags by the step they belong to. Each group renders a clickable
 * "Go to section" button that routes the wizard to that step and
 * tries to scroll/focus the first offending field.
 */
const FIELD_LABELS: Record<string, string> = (() => {
  const out: Record<string, string> = {};
  for (const s of AGREEMENT_SECTIONS) {
    for (const f of s.fields) {
      out[f.key] = f.label;
    }
  }
  // Build U — multi-cheque list. The section blueprint no longer
  // carries a chequeUpload field key, but the submit-error panel may
  // surface "cheques" as a missing token, so keep a friendly label.
  out["chequeUpload"] = "Security cheque upload";
  out["cheques"] = "Security cheques";
  return out;
})();

function fieldFriendlyLabel(key: string): string {
  return FIELD_LABELS[key] ?? key;
}

/**
 * Build W — calm, plain-language, per-item navigable missing-items
 * summary. Each row is its own clickable link that routes the wizard
 * to the section AND scrolls to + focuses the exact missing field,
 * affirmation checkbox, or signature pad. The panel auto-dismisses
 * the instant every item is fixed (driven by the live re-evaluation
 * in `liveMissing` above).
 */
function MissingItemsPanel({
  missing,
  visibleSections,
  onJumpToItem,
}: {
  missing: {
    missingFields: string[];
    missingAffirmations: string[];
    missingSignature: boolean;
    missingFinalSignature: boolean;
  };
  /** Build X — the wizard's filtered section list. Jump targets are
   *  indices into this list so they pair with setCurrentStep. A hidden
   *  appendix would never produce a missing token (backend skips
   *  not-required + untouched sections), but findIndex against this
   *  list keeps the indexing consistent end-to-end. */
  visibleSections: readonly AgreementSection[];
  /** Routes to {sectionIdx} and scroll/focuses {anchorId} if present. */
  onJumpToItem: (sectionIdx: number, anchorId?: string) => void;
}) {
  type Item = { label: string; anchorId?: string };
  type Group = { sectionIdx: number; sectionTitle: string; items: Item[] };
  const groups = new Map<number, Group>();
  const upsert = (
    sectionIdx: number,
    sectionTitle: string,
    label: string,
    anchorId?: string,
  ) => {
    const existing = groups.get(sectionIdx);
    if (existing) {
      existing.items.push({ label, anchorId });
    } else {
      groups.set(sectionIdx, {
        sectionIdx,
        sectionTitle,
        items: [{ label, anchorId }],
      });
    }
  };

  for (const key of missing.missingFields) {
    const section =
      findSectionForFieldKey(key) ?? sectionForSpecialKey(key);
    if (!section) continue;
    const idx = visibleSections.findIndex((s) => s.id === section.id);
    if (idx < 0) continue;
    upsert(
      idx,
      section.title,
      fieldFriendlyLabel(key),
      key === "cheques" || key === "chequeUpload"
        ? "field-cheques"
        : `field-${key}`,
    );
  }
  for (const flag of missing.missingAffirmations) {
    const section = findSectionForAffirmation(flag as AffirmationFlag);
    if (!section) continue;
    const idx = visibleSections.findIndex((s) => s.id === section.id);
    if (idx < 0) continue;
    upsert(
      idx,
      section.title,
      "Confirm you've read and agree to this section",
      `affirm-${flag}`,
    );
  }
  if (missing.missingSignature) {
    const idx = visibleSections.findIndex((s) => s.id === "main-agreement");
    if (idx >= 0) {
      upsert(idx, "The Agreement", "Your signature", "signature-block");
    }
  }
  if (missing.missingFinalSignature) {
    const idx = visibleSections.findIndex((s) => s.id === "review");
    if (idx >= 0) {
      upsert(
        idx,
        "Review & Submit",
        "Your final signature",
        "final-signature-block",
      );
    }
  }

  const ordered = Array.from(groups.values()).sort(
    (a, b) => a.sectionIdx - b.sectionIdx,
  );
  const total = ordered.reduce((n, g) => n + g.items.length, 0);

  return (
    <div
      role="status"
      className="rounded-2xl p-4 sm:p-5"
      style={{
        background: "var(--copper-wash, #faf1ea)",
        border: "1px solid rgba(168,98,63,0.35)",
        boxShadow: "0 8px 24px -20px rgba(168,98,63,0.45)",
      }}
    >
      <div className="flex items-start gap-2.5">
        <AlertCircle
          size={18}
          className="mt-0.5 shrink-0"
          style={{ color: "var(--copper-deep, #a8623f)" }}
        />
        <div>
          <p
            className="text-sm font-semibold"
            style={{ color: "var(--navy, #1B2A5C)" }}
          >
            Almost there — {total} item{total === 1 ? "" : "s"} left to finish
          </p>
          <p
            className="text-[12px] mt-0.5"
            style={{ color: "var(--muted, #5c6577)" }}
          >
            Click any item to jump straight to it. It&apos;ll disappear from
            this list as soon as you fix it.
          </p>
        </div>
      </div>
      <ul className="mt-3 space-y-3">
        {ordered.map((g) => (
          <li
            key={g.sectionIdx}
            className="rounded-xl bg-white p-3"
            style={{ border: "1px solid var(--line, #e4e7ee)" }}
          >
            <p
              className="text-[10px] font-bold uppercase tracking-widest"
              style={{ color: "var(--copper-deep, #a8623f)" }}
            >
              {g.sectionTitle}
            </p>
            <ul className="mt-2 -mx-1">
              {g.items.map((item, i) => (
                <li key={i}>
                  <button
                    type="button"
                    onClick={() => onJumpToItem(g.sectionIdx, item.anchorId)}
                    className="w-full text-left inline-flex items-center justify-between gap-3 px-2 py-1.5 rounded-md hover:bg-stone-50 motion-safe:transition-colors cursor-pointer group"
                  >
                    <span className="inline-flex items-center gap-2 text-[13px]">
                      <span
                        aria-hidden
                        className="block h-1.5 w-1.5 rounded-full shrink-0"
                        style={{ background: "var(--copper, #C87D5C)" }}
                      />
                      <span style={{ color: "var(--ink, #1d2433)" }}>
                        {item.label}
                      </span>
                    </span>
                    <span
                      className="text-[11px] font-semibold inline-flex items-center gap-0.5 motion-safe:transition-transform group-hover:translate-x-0.5"
                      style={{ color: "var(--copper-deep, #a8623f)" }}
                    >
                      Jump to it <ArrowRight size={11} />
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </div>
  );
}

// ── Build I: Consultant locked-down preview (watermarked images) ──

/**
 * Build I — replaces the Build G PDF iframe with a scrollable view of
 * watermarked PNGs, one per page. The backend renders the agreement to
 * PDF in memory, rasterises each page via PDFBox, bakes a watermark
 * (CONFIDENTIAL + viewer email + UTC timestamp) on every page, and
 * sends back base64 PNGs. No downloadable PDF leaves the server.
 *
 * UI lockdown (best-effort: screenshots can't be blocked in a
 * browser, but every capture carries the viewer's identity):
 *   - context menu disabled (right-click does nothing)
 *   - text selection disabled (user-select:none)
 *   - image drag disabled (draggable={false} + onDragStart prevented)
 *
 * Re-fetches whenever the primary signature changes (the preview
 * embeds the latest drawn signature).
 */
function ConsultantImagesPreview({
  appId,
  primarySignature,
  onScrolledToEnd,
}: {
  appId: string;
  primarySignature: string | null;
  /**
   * Build N — fires once the consultant has scrolled through the
   * whole preview. Drives the gated attestation checkbox in the
   * parent: until this fires, the attestation stays disabled. If
   * the rendered images fit without scrolling, the helper fires
   * immediately on layout so a short agreement isn't blocked.
   */
  onScrolledToEnd: () => void;
}) {
  const [pages, setPages] = useState<string[] | null>(null);
  const [viewerEmail, setViewerEmail] = useState<string>("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [reachedEnd, setReachedEnd] = useState(false);
  const scrollerRef = useRef<HTMLDivElement | null>(null);
  const endSentinelRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError("");
    (async () => {
      try {
        const data = await fetchConsultantPreviewImages(appId, primarySignature);
        if (cancelled) return;
        setPages(data.pages);
        setViewerEmail(data.viewerEmail || "");
      } catch (e) {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : "Couldn't render the preview.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [appId, primarySignature]);

  // Build N — scroll-to-end detection. Three triggers fire onScrolledToEnd:
  //   1. The scroll handler when the scrollTop+clientHeight reaches the
  //      bottom (with a 24px slack so a short-overflow user doesn't
  //      need pixel-perfect scrolling).
  //   2. A post-render layout check: if scrollHeight <= clientHeight
  //      (the preview fits without scrolling), the consultant has
  //      "seen the whole agreement" by default -- unlock immediately.
  //   3. ResizeObserver re-runs the layout check when the page images
  //      finish decoding (image height changes scrollHeight).
  const markEnd = useCallback(() => {
    if (reachedEnd) return;
    setReachedEnd(true);
    onScrolledToEnd();
  }, [reachedEnd, onScrolledToEnd]);

  const handleScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const el = e.currentTarget;
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 24) {
      markEnd();
    }
  };

  // Build AB — "read to end" detection that works on TOUCH + page scroll. The
  // old logic only fired when the INNER scroll box reached its bottom (desktop)
  // OR the doc fit without scrolling; on mobile the consultant scrolls the PAGE
  // (not the inner box), so previewSeen never flipped → the attestation stayed
  // locked → the sticky-footer Submit was disabled and the tap looked dead.
  // An end-sentinel observed against the VIEWPORT fires once the bottom of the
  // preview is reached, whether the inner box (desktop) or the page (mobile) is
  // scrolled — and immediately when a short agreement already fits on screen.
  useEffect(() => {
    if (!pages || reachedEnd) return;
    const sentinel = endSentinelRef.current;
    if (!sentinel) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) markEnd();
      },
      { root: null, threshold: 0 },
    );
    io.observe(sentinel);
    return () => io.disconnect();
  }, [pages, reachedEnd, markEnd]);

  return (
    <section id="agreement-preview" className="bg-white rounded-2xl border border-stone-200 shadow-sm overflow-hidden">
      <header className="px-5 sm:px-6 pt-5 pb-3 border-b border-stone-100">
        <p className="text-[10px] font-bold uppercase tracking-widest text-sage-copper">
          Your agreement (read-only preview)
        </p>
        <h3 className="font-serif text-lg text-sage-navy mt-0.5">
          This is the document that will be filed
        </h3>
        <p className="text-[11px] text-gray-500 mt-1 inline-flex items-center gap-1">
          <Lock size={11} /> This document is confidential and watermarked to you
          {viewerEmail ? ` (${viewerEmail})` : ""}.
        </p>
      </header>
      <div
        className="bg-stone-100 p-4 min-h-[480px] select-none"
        style={{ userSelect: "none", WebkitUserSelect: "none" }}
        onContextMenu={(e) => e.preventDefault()}
      >
        {loading && !pages && (
          <div className="flex items-center justify-center text-xs text-gray-500 py-12">
            <Loader2 size={16} className="animate-spin mr-2" />
            Generating preview…
          </div>
        )}
        {error && (
          <div className="flex items-center justify-center text-xs text-red-700 py-12">
            <AlertCircle size={14} className="mr-1.5" /> {error}
          </div>
        )}
        {pages && !error && (
          <div
            ref={scrollerRef}
            onScroll={handleScroll}
            // Build AB — inner scroll box on desktop only; on mobile the pages
            // flow in the PAGE so the consultant scrolls naturally (the
            // end-sentinel below detects "read to end" either way).
            className="lg:max-h-[640px] lg:overflow-y-auto space-y-3 pr-2"
          >
            {pages.map((b64, idx) => (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                key={idx}
                src={`data:image/png;base64,${b64}`}
                alt={`Agreement preview page ${idx + 1}`}
                draggable={false}
                onDragStart={(e) => e.preventDefault()}
                onContextMenu={(e) => e.preventDefault()}
                className="block w-full rounded-md border border-stone-300 bg-white shadow-sm pointer-events-auto"
                style={{ userSelect: "none", WebkitUserDrag: "none" } as CSSProperties}
              />
            ))}
            {/* Build AB — read-to-end sentinel (drives previewSeen on touch). */}
            <div ref={endSentinelRef} aria-hidden className="h-2 w-full" />
          </div>
        )}
      </div>
    </section>
  );
}

// ── Build U: Appendix 5 multi-cheque list ────────────────────

/**
 * Build W/I — reusable required document-upload tile (image/PDF ≤10 MB);
 * "uploaded ✓" once stored. Used for the work-authorization document
 * (Personal Information) and the offer letter (Phase 2 Employment).
 */
function DocUploadBlock({
  title,
  description,
  inputId,
  uploaded,
  uploading,
  error,
  onUpload,
  needsAttention,
  optional = false,
}: {
  title: string;
  description: string;
  inputId: string;
  uploaded: boolean;
  uploading: boolean;
  error: string;
  onUpload: (file: File) => void;
  needsAttention: boolean;
  // When true, omit the required-style "*" mark (the field is optional).
  // Cosmetic only — validation lives in the section's required-items logic.
  optional?: boolean;
}) {
  return (
    <section
      className={
        "rounded-xl border p-4 space-y-3 "
        + (uploaded
          ? "bg-emerald-50/40 border-emerald-300"
          : needsAttention
            ? "border-[var(--copper)] bg-[color:var(--copper-wash)]/60"
            : "bg-white border-stone-200")
      }
    >
      <div>
        <p className="text-[11px] font-semibold uppercase tracking-wider text-sage-navy">
          {title}
          {!optional && (
            <>
              {" "}
              <span className="text-sage-copper-deep" aria-hidden>*</span>
            </>
          )}
        </p>
        <p className="mt-1 text-[12px] text-stone-500">
          {description}
        </p>
      </div>
      <div className="flex items-center gap-3 flex-wrap">
        <label
          htmlFor={inputId}
          className={
            "inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold border cursor-pointer motion-safe:transition-colors "
            + (uploading
              ? "bg-stone-100 text-gray-400 border-stone-200 cursor-wait"
              : uploaded
                ? "bg-white text-sage-navy border-stone-300 hover:bg-stone-50"
                : "bg-sage-navy text-white border-sage-navy hover:bg-sage-navy-deep")
          }
        >
          {uploading ? (
            <>
              <Loader2 size={12} className="animate-spin" /> Uploading…
            </>
          ) : (
            <>
              <FileText size={12} />
              {uploaded ? "Replace file" : "Choose file"}
            </>
          )}
        </label>
        {uploaded && !uploading && (
          <span className="inline-flex items-center gap-1 text-xs font-semibold text-emerald-800">
            <CheckCircle2 size={13} /> Uploaded
          </span>
        )}
        <input
          id={inputId}
          type="file"
          accept="image/*,application/pdf"
          disabled={uploading}
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) onUpload(f);
            e.currentTarget.value = "";
          }}
          className="hidden"
        />
      </div>
      {error && (
        <p className="text-[11px] text-red-600 inline-flex items-center gap-1">
          <AlertCircle size={11} /> {error}
        </p>
      )}
    </section>
  );
}

/**
 * Build J — repeatable Portal Access platform+username entries. Stores a
 * JSON array string via {@code onChange}; rows include partials while the
 * consultant types (the backend renders only complete platform+username
 * pairs). At least one complete entry is required when Appendix 4 applies.
 */
const PORTAL_ENTRY_CAP = 10;
function PortalEntriesBlock({
  value,
  onChange,
  needsAttention,
}: {
  value: string;
  onChange: (json: string) => void;
  needsAttention: boolean;
}) {
  const rows: PortalEntry[] = (() => {
    if (!value || !value.trim()) return [{ platform: "", username: "" }];
    try {
      const arr = JSON.parse(value);
      if (Array.isArray(arr) && arr.length > 0) {
        return arr.map((e) => ({
          platform: typeof e?.platform === "string" ? e.platform : "",
          username: typeof e?.username === "string" ? e.username : "",
        }));
      }
    } catch {
      /* fall through */
    }
    return [{ platform: "", username: "" }];
  })();

  const commit = (next: PortalEntry[]) =>
    onChange(JSON.stringify(next.length > 0 ? next : [{ platform: "", username: "" }]));
  const update = (i: number, field: "platform" | "username", v: string) =>
    commit(rows.map((r, idx) => (idx === i ? { ...r, [field]: v } : r)));
  const add = () => {
    if (rows.length < PORTAL_ENTRY_CAP) commit([...rows, { platform: "", username: "" }]);
  };
  const remove = (i: number) => commit(rows.filter((_, idx) => idx !== i));

  const inputClass =
    "w-full px-3 py-2.5 text-[14px] rounded-md border bg-white text-stone-900 placeholder:text-stone-400 focus:outline-none focus:ring-2 focus:ring-sage-copper/40 focus:border-sage-copper " +
    (needsAttention ? "border-[var(--copper)] bg-[color:var(--copper-wash)]/60" : "border-stone-300");

  return (
    <section
      className={
        "rounded-xl border p-4 space-y-3 "
        + (needsAttention
          ? "border-[var(--copper)] bg-[color:var(--copper-wash)]/60"
          : "bg-white border-stone-200")
      }
    >
      <div>
        <p className="text-[11px] font-semibold uppercase tracking-wider text-sage-navy">
          Authorized platforms{" "}
          <span className="text-sage-copper-deep" aria-hidden>*</span>
        </p>
        <p className="mt-1 text-[12px] text-stone-500">
          Add each platform we may access on your behalf, with the username /
          login ID for that platform. At least one is required.
        </p>
      </div>
      <div className="space-y-3">
        {rows.map((row, i) => (
          <div key={i} className="flex items-start gap-2">
            <div className="flex-1 grid grid-cols-1 sm:grid-cols-2 gap-2">
              <input
                type="text"
                value={row.platform}
                onChange={(e) => update(i, "platform", e.target.value)}
                placeholder="Platform (e.g. LinkedIn, Dice)"
                className={inputClass}
              />
              <input
                type="text"
                value={row.username}
                onChange={(e) => update(i, "username", e.target.value)}
                placeholder="Username / login ID"
                className={inputClass}
              />
            </div>
            {rows.length > 1 && (
              <button
                type="button"
                onClick={() => remove(i)}
                aria-label="Remove platform"
                className="mt-1 shrink-0 inline-flex items-center justify-center w-9 h-9 rounded-md border border-stone-200 text-stone-500 hover:text-red-600 hover:border-red-200 cursor-pointer"
              >
                <Trash2 size={14} />
              </button>
            )}
          </div>
        ))}
      </div>
      {rows.length < PORTAL_ENTRY_CAP && (
        <button
          type="button"
          onClick={add}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold border border-stone-200 bg-white hover:bg-stone-50 text-sage-navy cursor-pointer"
        >
          <Plus size={13} /> Add platform
        </button>
      )}
    </section>
  );
}

/**
 * Build U — renders one input group per cheque (driven by the
 * consultant's "Number of cheques" entry). Each group has its own
 * cheque-number and file upload. Replaces the previous single
 * ChequeUploadBlock + free-text "Check numbers" inputs. (Build W
 * removed the per-cheque date field.)
 */
function ChequeListBlock({
  count,
  entries,
  uploadingIndex,
  error,
  onUploadAt,
  onMetadataChange,
  needsAttention = false,
}: {
  count: number;
  entries: ChequeEntry[];
  uploadingIndex: number | null;
  error: string;
  onUploadAt: (index: number, file: File) => void;
  onMetadataChange: (
    index: number,
    patch: { number?: string; date?: string },
  ) => void;
  /** Build W — flagged by a failed submit; surfaces an inline attention banner. */
  needsAttention?: boolean;
}) {
  const attentionBanner = needsAttention ? (
    <p
      className="text-[11.5px] inline-flex items-center gap-1 font-medium"
      style={{ color: "var(--copper-deep)" }}
    >
      <AlertCircle size={11} /> Each cheque needs both a number and an upload.
    </p>
  ) : null;
  if (count <= 0) {
    return (
      <section className="rounded-2xl border border-stone-200 bg-stone-50/60 px-5 py-4 text-[12.5px] text-stone-600 space-y-2">
        <span className="inline-flex items-start gap-2">
          <AlertCircle size={13} className="mt-0.5 shrink-0" />
          <span>
            Enter the number of cheques above to add upload slots for each
            one.
          </span>
        </span>
        {attentionBanner}
      </section>
    );
  }
  const indices = Array.from({ length: count }, (_, i) => i);
  return (
    <section
      className="space-y-3"
      style={
        needsAttention
          ? {
              border: "1px solid var(--copper)",
              background: "var(--copper-wash)",
              borderRadius: "16px",
              padding: "16px",
            }
          : undefined
      }
    >
      <header>
        <p className="text-[10px] font-bold uppercase tracking-widest text-sage-copper">
          Security cheques
        </p>
        <h3 className="font-serif text-lg text-sage-navy mt-0.5">
          One entry per cheque
        </h3>
        <p className="text-xs text-gray-700 mt-1 leading-relaxed max-w-[64ch]">
          Required to complete Appendix 5. For each cheque, enter the
          cheque number and date, then upload a photo or PDF (≤10 MB).
          The files are stored privately and visible only to Sage IT.
        </p>
        {attentionBanner}
      </header>
      {indices.map((i) => {
        const entry = entries.find((e) => e.index === i);
        const uploaded = Boolean(entry?.publicId || entry?.s3Key);
        const uploading = uploadingIndex === i;
        const inputId = `cheque-${i}-file`;
        return (
          <div
            key={i}
            className={
              "rounded-xl border p-4 space-y-3 "
              + (uploaded
                ? "bg-emerald-50/40 border-emerald-300"
                : "bg-white border-stone-200")
            }
          >
            <p className="text-[11px] font-semibold uppercase tracking-wider text-sage-navy">
              Cheque {i + 1}
            </p>
            {/* Build W — cheque DATE field removed; only number + upload. */}
            <div className="grid grid-cols-1 gap-3">
              <label className="block text-[12px]">
                <span className="font-medium text-stone-700">
                  Cheque number <span className="text-sage-copper-deep" aria-hidden>*</span>
                </span>
                <input
                  type="text"
                  value={entry?.number ?? ""}
                  onChange={(e) =>
                    onMetadataChange(i, { number: e.target.value })
                  }
                  placeholder="e.g. 1001"
                  className="mt-1 w-full px-3 py-2 text-[14px] rounded-md border border-stone-300 bg-white text-stone-900 focus:outline-none focus:ring-2 focus:ring-sage-copper/40 focus:border-sage-copper"
                />
              </label>
            </div>
            <div className="flex items-center gap-3 flex-wrap">
              <label
                htmlFor={inputId}
                className={
                  "inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold border cursor-pointer motion-safe:transition-colors "
                  + (uploading
                    ? "bg-stone-100 text-gray-400 border-stone-200 cursor-wait"
                    : uploaded
                      ? "bg-white text-sage-navy border-stone-300 hover:bg-stone-50"
                      : "bg-sage-navy text-white border-sage-navy hover:bg-sage-navy-deep")
                }
              >
                {uploading ? (
                  <>
                    <Loader2 size={12} className="animate-spin" /> Uploading…
                  </>
                ) : (
                  <>
                    <FileText size={12} />
                    {uploaded ? "Replace file" : "Choose file"}
                  </>
                )}
              </label>
              {uploaded && !uploading && (
                <span className="inline-flex items-center gap-1 text-xs font-semibold text-emerald-800">
                  <CheckCircle2 size={13} /> Uploaded
                </span>
              )}
              <input
                id={inputId}
                type="file"
                accept="image/*,application/pdf"
                disabled={uploading}
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f) onUploadAt(i, f);
                  e.currentTarget.value = "";
                }}
                className="hidden"
              />
            </div>
          </div>
        );
      })}
      {error && (
        <p className="text-[11px] text-red-600 inline-flex items-center gap-1">
          <AlertCircle size={11} /> {error}
        </p>
      )}
    </section>
  );
}

// ── Affirmation ──────────────────────────────────────────────

function AffirmationBlock({
  flag,
  checked,
  onChange,
  scrollGated = false,
  needsAttention = false,
}: {
  flag: AffirmationFlag;
  checked: boolean;
  onChange: (value: boolean) => void;
  /** Build U — true until the consultant has scrolled this section. */
  scrollGated?: boolean;
  /** Build W — flagged by a failed submit; render the copper "needs attention" border. */
  needsAttention?: boolean;
}) {
  const locked = scrollGated && !checked;
  const borderColor = checked
    ? "border-[var(--ok)]/55"
    : needsAttention
      ? "border-[var(--copper)]"
      : "bg-[var(--paper)] border-[var(--line)] hover:border-[var(--ok)]/40";
  return (
    <label
      htmlFor={`affirm-${flag}`}
      className={
        "flex items-start gap-3 p-4 rounded-xl border motion-safe:transition-colors "
        + (locked ? "cursor-not-allowed opacity-65 " : "cursor-pointer ")
        + borderColor
      }
      style={
        checked
          ? { background: "var(--ok-wash)" }
          : needsAttention
            ? { background: "var(--copper-wash)" }
            : undefined
      }
      title={locked ? "Scroll the section to read it first." : undefined}
    >
      <input
        id={`affirm-${flag}`}
        type="checkbox"
        checked={checked}
        disabled={locked}
        onChange={(e) => onChange(e.target.checked)}
        className="sr-only peer"
      />
      <span
        aria-hidden="true"
        className={
          "mt-0.5 inline-flex items-center justify-center h-[18px] w-[18px] rounded-[4px] border motion-safe:transition-colors shrink-0 "
          + (checked
            ? "border-[var(--ok)] text-white"
            : "border-stone-400 bg-white text-transparent")
        }
        style={checked ? { background: "var(--ok)" } : undefined}
      >
        <svg
          viewBox="0 0 14 14"
          className="h-[11px] w-[11px]"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <polyline points="2.5,7.5 5.8,10.5 11.5,3.8" />
        </svg>
      </span>
      <span
        className="text-[13.5px] leading-snug"
        style={{ color: checked ? "var(--ok)" : "var(--ink)" }}
      >
        I have read and understood this section and agree to its terms.
        {locked && (
          <span
            className="block mt-1 text-[11.5px] font-medium"
            style={{ color: "var(--muted)" }}
          >
            Scroll the section to the end to confirm.
          </span>
        )}
      </span>
    </label>
  );
}

// ── Review step ──────────────────────────────────────────────

function ReviewStep({
  appId,
  form,
  reqs,
  visibleSections,
  onJumpToSection,
  onFinalSignature,
  onLegalName,
  allComplete,
  chequeEntries,
  workAuthUploaded,
  offerLetterUploaded,
  dlDocUploaded,
  attestation,
  onAttestation,
  previewSeen,
  onPreviewSeen,
  onBack,
  onSubmit,
  submitting,
  submitError,
  missingFinalSignature,
}: {
  appId: string;
  form: FormState;
  reqs: EffectiveRequirements;
  /** Build X — only the consultant's visible sections show in the
   *  Review read-back. Hidden appendices never render here either. */
  visibleSections: readonly AgreementSection[];
  onJumpToSection: (idx: number) => void;
  onFinalSignature: (dataUrl: string | null) => void;
  onLegalName: (value: string) => void;
  allComplete: boolean;
  chequeEntries: ChequeEntry[];
  /** Build W/I/J — uploads present? (non-form completion signals). */
  workAuthUploaded: boolean;
  offerLetterUploaded: boolean;
  dlDocUploaded: boolean;
  attestation: boolean;
  onAttestation: (value: boolean) => void;
  previewSeen: boolean;
  onPreviewSeen: () => void;
  onBack: () => void;
  onSubmit: () => void;
  submitting: boolean;
  /** Build AB — visible reason a Submit attempt was blocked (in-viewport). */
  submitError: string;
  /** Build W — final signature missing on the last failed submit. */
  missingFinalSignature: boolean;
}) {
  // Build V — in a section-restricted revision the main-agreement step is NOT
  // shown, so the consultant can't (re)draw the primary here; it's reused from
  // their original signing. Drives the review-step primary-signature copy so it
  // doesn't tell them to "go back to the main agreement step" that isn't shown.
  const mainStepInScope = visibleSections.some((s) => s.id === "main-agreement");
  return (
    <div className="space-y-6">
      <div>
        <p
          className="text-[11px] font-medium uppercase tracking-[0.18em]"
          style={{ color: "var(--copper-deep)" }}
        >
          Final step
        </p>
        <h1
          className="display text-[30px] sm:text-[36px] leading-[1.1] font-semibold mt-2 [text-wrap:balance]"
          style={{ color: "var(--navy)" }}
        >
          Read the agreement, then sign and submit
        </h1>
        <p
          className="text-[15px] mt-3 max-w-[68ch] leading-[1.6]"
          style={{ color: "var(--ink)" }}
        >
          Below is the actual agreement that will be filed once you submit.
          Your details and your primary signature are already in it. Read it
          carefully, tick the attestation, then sign and submit.
        </p>
      </div>

      <ConsultantImagesPreview
        appId={appId}
        primarySignature={form.signature}
        onScrolledToEnd={onPreviewSeen}
      />

      <div className="max-w-[min(100%,860px)]">
        <label
          htmlFor="consultant-review-attestation"
          className={
            "flex items-start gap-3 p-4 rounded-xl border motion-safe:transition-colors "
            + (!previewSeen
              ? "bg-stone-50 border-stone-200 cursor-not-allowed"
              : attestation
                ? "bg-sage-navy/5 border-sage-navy/40 cursor-pointer"
                : "bg-white border-stone-300 hover:border-sage-navy/40 hover:bg-stone-50/60 cursor-pointer")
          }
        >
          <input
            id="consultant-review-attestation"
            type="checkbox"
            checked={attestation}
            disabled={!previewSeen}
            onChange={(e) => onAttestation(e.target.checked)}
            className="mt-0.5 h-4 w-4 accent-sage-navy disabled:cursor-not-allowed"
          />
          <span
            className={
              "text-[14px] leading-snug "
              + (previewSeen ? "text-stone-800" : "text-stone-500")
            }
          >
            I have read this agreement and confirm the details are mine and
            accurate.
          </span>
        </label>
        {!previewSeen && (
          <p className="mt-2 ml-1 text-[12px] text-stone-500 inline-flex items-center gap-1.5">
            <AlertCircle size={12} /> Scroll through the full agreement above to
            continue.
          </p>
        )}
      </div>

      {visibleSections.slice(0, -1).map((section, idx) => {
        const complete = isSectionComplete(section, form, reqs, chequeEntries, workAuthUploaded, offerLetterUploaded, dlDocUploaded);
        const isAppendix = Boolean(section.appendixKey);
        const optionalAndSkipped =
          isAppendix
          && section.appendixKey
          && !reqs[section.appendixKey]
          && !isAppendixTouched(section, form);
        return (
          <section
            key={section.id}
            className="border border-stone-200/80 rounded-xl bg-white px-5 py-4"
          >
            <div className="flex items-start justify-between gap-3 flex-wrap">
              <div>
                <p className="text-[11px] text-stone-500 tabular-nums">
                  Section {section.step}
                </p>
                <h3 className="font-serif text-[17px] text-sage-navy mt-0.5 leading-snug [text-wrap:balance]">
                  {section.title}
                </h3>
              </div>
              <div className="flex items-center gap-3 flex-wrap">
                <span
                  className={
                    "inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium "
                    + (optionalAndSkipped
                      ? "bg-stone-100 text-stone-600"
                      : complete
                        ? "bg-sage-navy/10 text-sage-navy"
                        : "bg-sage-copper/12 text-sage-copper-deep")
                  }
                >
                  {optionalAndSkipped ? (
                    <>
                      <CheckCircle2 size={11} /> Skipped (optional)
                    </>
                  ) : complete ? (
                    <>
                      <CheckCircle2 size={11} /> Complete
                    </>
                  ) : (
                    <>
                      <AlertCircle size={11} /> Needs attention
                    </>
                  )}
                </span>
                <button
                  type="button"
                  onClick={() => onJumpToSection(idx)}
                  className="text-[12px] font-medium text-stone-500 hover:text-sage-navy motion-safe:transition-colors underline underline-offset-[3px] decoration-stone-300 hover:decoration-sage-navy"
                >
                  Edit section
                </button>
              </div>
            </div>

            {section.fields.length > 0 && (
              <dl className="mt-3 grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2.5">
                {section.fields.map((field) => {
                  if (field.type === "file") return null;
                  const raw = form.fields[field.key] ?? "";
                  let displayed: string;
                  if (field.type === "date") {
                    displayed = formatUsDate(raw) || "Not set";
                  } else if (field.type === "id-type") {
                    displayed =
                      raw === "DL"
                        ? "Driver's License"
                        : raw === "STATE_ID"
                          ? "State ID"
                          : "Not set";
                  } else if (field.sensitive && raw.length > 0) {
                    displayed = maskValue(raw);
                  } else {
                    displayed = raw || "Not set";
                  }
                  return (
                    <div key={field.key}>
                      <dt className="text-[11px] text-stone-500">
                        {field.label}
                      </dt>
                      <dd className="text-[14px] text-stone-800 break-words mt-0.5">
                        {displayed}
                      </dd>
                    </div>
                  );
                })}
              </dl>
            )}

            {section.id === "appendix5" && (() => {
              const required = parseChequeCount(form.fields["securityCheckCount"]);
              const uploadedCount = chequeEntries.filter(
                (e) => (e.publicId || e.s3Key) && e.number && e.index < required,
              ).length;
              const allReady = required > 0 && uploadedCount === required;
              return (
                <p className="mt-3 text-[12.5px] text-stone-600 inline-flex items-center gap-1.5">
                  {allReady ? (
                    <CheckCircle2 size={12} className="text-sage-navy" />
                  ) : (
                    <AlertCircle size={12} className="text-sage-copper-deep" />
                  )}
                  Cheques: {uploadedCount} of {required} uploaded
                </p>
              );
            })()}

            {section.requiresAffirmation && section.affirmationFlag && (
              <p className="mt-3 text-[12.5px] text-stone-600 inline-flex items-center gap-1.5">
                {form.affirmations[section.affirmationFlag] ? (
                  <CheckCircle2 size={12} className="text-sage-navy" />
                ) : (
                  <AlertCircle size={12} className="text-sage-copper-deep" />
                )}
                Affirmation{" "}
                {form.affirmations[section.affirmationFlag]
                  ? "confirmed"
                  : "still required"}
              </p>
            )}
          </section>
        );
      })}

      <section className="border border-stone-200/80 rounded-xl bg-white px-5 py-4">
        <p className="text-[11px] font-medium text-stone-500">
          Primary signature, from the main agreement step
        </p>
        {form.signature ? (
          <div className="mt-3 inline-block rounded-md border border-dashed border-stone-300 bg-stone-50 p-2">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={form.signature}
              alt="Captured primary signature"
              style={{ maxHeight: 64, maxWidth: 240 }}
            />
          </div>
        ) : mainStepInScope ? (
          <p className="mt-2 text-[12.5px] text-sage-copper-deep inline-flex items-center gap-1.5">
            <AlertCircle size={12} /> Not captured. Go back to the main
            agreement step to add it.
          </p>
        ) : (
          <p className="mt-2 text-[12.5px] text-stone-600 inline-flex items-center gap-1.5">
            <CheckCircle2 size={12} className="text-emerald-600" /> Your
            signature from your original signing will be reused for this
            revision.
          </p>
        )}
        {form.signedLegalName && (
          <p className="mt-2 text-[12.5px] text-stone-600">
            Signed legal name:{" "}
            <span className="font-semibold text-sage-navy">
              {form.signedLegalName}
            </span>
          </p>
        )}
      </section>

      <div id="final-signature-block">
        <FinalSignatureBlock
          finalSignature={form.finalSignature}
          legalName={form.signedLegalName}
          onFinalSignature={onFinalSignature}
          onLegalName={onLegalName}
          needsAttention={missingFinalSignature}
        />
      </div>

      {!allComplete && (
        <div className="rounded-md border border-sage-copper/30 bg-[#FBF1E8] px-4 py-3 text-[13.5px] text-sage-copper-deep inline-flex items-start gap-2 max-w-[min(100%,860px)]">
          <AlertCircle size={14} className="mt-0.5 shrink-0" />
          <span>
            {form.finalSignature
              ? "Some sections still need attention. Use the section list above to jump to them."
              : "Add your final signature above to execute the agreement, then submit."}
          </span>
        </div>
      )}

      {/* Build AB — a Submit attempt while incomplete is no longer a silent
          no-op: this surfaces the specific blocker (the tap also scrolls it
          into view). Also shows a real backend submit failure, which used to
          be set but never rendered. */}
      {submitError && (
        <div
          role="alert"
          className="rounded-md border border-red-300 bg-red-50 px-4 py-3 text-[13.5px] text-red-700 inline-flex items-start gap-2 max-w-[min(100%,860px)]"
        >
          <AlertCircle size={14} className="mt-0.5 shrink-0" />
          <span>{submitError}</span>
        </div>
      )}

      {/* Build P / hotfix — desktop submit cluster. FooterNav is
          lg:hidden so the desktop layout needs its own Submit
          affordance. Mobile users still get the sticky FooterNav
          below the viewport. */}
      <div
        className="flex items-center justify-between gap-3 pt-4 max-w-[min(100%,860px)]"
        style={{ borderTop: "1px solid var(--line)" }}
      >
        <button
          type="button"
          onClick={onBack}
          disabled={submitting}
          className="inline-flex items-center gap-1 text-[13px] font-medium motion-safe:transition-colors disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer"
          style={{ color: "var(--muted)" }}
        >
          <ArrowLeft size={13} /> Back
        </button>
        <button
          type="button"
          onClick={onSubmit}
          // Build AB — tappable even when incomplete; onSubmit (attemptSubmit)
          // surfaces + scrolls to the blocker instead of a dead disabled tap.
          disabled={submitting}
          className={
            "inline-flex items-center gap-1.5 px-5 py-2.5 rounded-md text-sm font-semibold motion-safe:transition-colors cursor-pointer "
            + (allComplete && !submitting ? "text-white" : "")
          }
          style={
            allComplete && !submitting
              ? { background: "var(--navy)" }
              : { background: "#eef0f5", color: "#9aa1ad" }
          }
          onMouseEnter={(e) => {
            if (allComplete && !submitting) {
              e.currentTarget.style.background = "var(--copper-deep)";
            }
          }}
          onMouseLeave={(e) => {
            if (allComplete && !submitting) {
              e.currentTarget.style.background = "var(--navy)";
            }
          }}
        >
          {submitting ? (
            <Loader2 size={13} className="animate-spin" />
          ) : (
            <CheckCircle2 size={13} />
          )}
          Submit agreement
        </button>
      </div>
    </div>
  );
}

// ── Final signature block (review step) ──────────────────────

function FinalSignatureBlock({
  finalSignature,
  legalName,
  onFinalSignature,
  onLegalName,
  needsAttention = false,
}: {
  finalSignature: string | null;
  legalName: string;
  onFinalSignature: (dataUrl: string | null) => void;
  onLegalName: (value: string) => void;
  /** Build W — flagged by a failed submit; renders the copper attention border. */
  needsAttention?: boolean;
}) {
  const [redrawing, setRedrawing] = useState(false);
  const captured = Boolean(finalSignature);
  const shouldShowPad = !captured || redrawing;

  return (
    <section
      className="rounded-xl px-5 py-5 space-y-4 max-w-[min(100%,860px)]"
      style={{
        background: needsAttention ? "var(--copper-wash)" : "var(--paper)",
        border: needsAttention
          ? "1px solid var(--copper)"
          : "1px solid var(--navy)",
      }}
    >
      <div>
        <p
          className="text-[11px] font-medium uppercase tracking-[0.16em]"
          style={{ color: "var(--copper-deep)" }}
        >
          Execution signature
        </p>
        <h3
          className="display text-[20px] font-semibold mt-1 leading-snug [text-wrap:balance]"
          style={{ color: "var(--navy)" }}
        >
          After reading this, I am formally signing this acknowledgement.
        </h3>
        {needsAttention && (
          <p
            className="mt-1.5 text-[11.5px] inline-flex items-center gap-1 font-medium"
            style={{ color: "var(--copper-deep)" }}
          >
            <AlertCircle size={11} /> Your final signature is still required.
          </p>
        )}
        <p
          className="text-[13.5px] mt-2 leading-snug"
          style={{ color: "var(--ink)" }}
        >
          Upload (or draw) your final signature here. It confirms that
          everything above matches what you intended and that you are
          executing the agreement.
        </p>
      </div>

      <div>
        <label
          className="block text-[12px] font-medium mb-1.5"
          style={{ color: "var(--ink)" }}
        >
          Your full legal name{" "}
          <span style={{ color: "var(--copper-deep)" }} aria-hidden>
            *
          </span>
        </label>
        <input
          type="text"
          value={legalName}
          onChange={(e) => onLegalName(e.target.value)}
          className="w-full px-3 py-2.5 text-[14px] rounded-md bg-white motion-safe:transition-colors focus:outline-none"
          style={{
            border: "1px solid var(--line)",
            color: "var(--ink)",
          }}
          onFocus={(e) => {
            e.currentTarget.style.borderColor = "var(--copper)";
            e.currentTarget.style.boxShadow = "0 0 0 3px rgba(200,125,92,0.18)";
          }}
          onBlur={(e) => {
            e.currentTarget.style.borderColor = "var(--line)";
            e.currentTarget.style.boxShadow = "none";
          }}
          placeholder="First Middle Last"
        />
      </div>

      {shouldShowPad ? (
        <div>
          <SignaturePad
            onChange={(data) => {
              onFinalSignature(data);
              if (data) setRedrawing(false);
            }}
            fileInputId="consultant-wizard-final-sig"
          />
          {redrawing && (
            <button
              type="button"
              onClick={() => setRedrawing(false)}
              className="mt-2 text-[12px] font-medium text-stone-500 hover:text-sage-navy motion-safe:transition-colors"
            >
              Cancel replacement
            </button>
          )}
        </div>
      ) : (
        <div>
          <p className="text-[12px] font-medium text-stone-600 mb-1.5">
            Your captured execution signature
          </p>
          <div className="inline-flex items-center gap-3 flex-wrap">
            <div className="inline-block rounded-md border border-dashed border-stone-300 bg-stone-50 p-2">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={finalSignature!}
                alt="Captured execution signature"
                style={{ maxHeight: 60, maxWidth: 200 }}
              />
            </div>
            <button
              type="button"
              onClick={() => {
                setRedrawing(true);
                onFinalSignature(null);
              }}
              className="text-[12px] font-medium text-stone-500 hover:text-sage-navy motion-safe:transition-colors underline underline-offset-[3px] decoration-stone-300 hover:decoration-sage-navy"
            >
              Replace signature
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

function maskValue(value: string): string {
  const last = value.slice(-4);
  return "•".repeat(Math.max(0, value.length - 4)) + last;
}

// ── Footer nav ───────────────────────────────────────────────

function FooterNav({
  currentStep,
  total,
  canAdvance,
  saveStatus,
  submitting,
  onBack,
  onNext,
  onSubmit,
  isReviewStep,
}: {
  currentStep: number;
  total: number;
  canAdvance: boolean;
  saveStatus: SaveStatus;
  submitting: boolean;
  onBack: () => void;
  onNext: () => void;
  onSubmit: () => void;
  isReviewStep: boolean;
}) {
  // Build P — sticky bottom bar on mobile / tablet only. Desktop hides
  // this and uses the in-rail Back / Continue cluster, so the document
  // surface gets the full viewport vertical real estate.
  return (
    <nav
      aria-label="Wizard navigation"
      className="lg:hidden fixed bottom-0 left-0 right-0 z-30"
      style={{
        background: "var(--paper)",
        borderTop: "1px solid var(--line)",
        boxShadow: "0 -8px 22px -16px rgba(15,31,68,0.18)",
        paddingBottom: "env(safe-area-inset-bottom, 0)",
      }}
    >
      <div className="max-w-7xl mx-auto px-4 py-3 flex items-center justify-between gap-3">
        <button
          type="button"
          onClick={onBack}
          disabled={currentStep === 0 || submitting}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-[13px] font-medium motion-safe:transition-colors disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer"
          style={{ color: "var(--muted)" }}
        >
          <ArrowLeft size={13} /> Back
        </button>
        {isReviewStep ? (
          <button
            type="button"
            onClick={onSubmit}
            // Build AB — tappable even when incomplete; attemptSubmit surfaces
            // + scrolls to the blocker instead of a dead disabled tap.
            disabled={submitting}
            className={
              "inline-flex items-center gap-1.5 px-4 py-2.5 rounded-md text-sm font-semibold motion-safe:transition-colors cursor-pointer "
              + (canAdvance && !submitting ? "text-white" : "")
            }
            style={
              canAdvance && !submitting
                ? { background: "var(--navy)" }
                : { background: "#eef0f5", color: "#9aa1ad" }
            }
          >
            {submitting ? (
              <Loader2 size={13} className="animate-spin" />
            ) : (
              <CheckCircle2 size={13} />
            )}
            Submit agreement
          </button>
        ) : (
          <button
            type="button"
            onClick={onNext}
            disabled={!canAdvance || submitting}
            className={
              "inline-flex items-center gap-1.5 px-4 py-2.5 rounded-md text-sm font-semibold motion-safe:transition-colors cursor-pointer "
              + (canAdvance && !submitting
                ? "text-white"
                : "cursor-not-allowed")
            }
            style={
              canAdvance && !submitting
                ? { background: "var(--navy)" }
                : { background: "#eef0f5", color: "#9aa1ad" }
            }
          >
            Continue <ArrowRight size={13} />
          </button>
        )}
      </div>
      <div
        className="px-4 pb-2 text-[11px] flex items-center justify-between"
        style={{ color: "var(--muted)" }}
      >
        <span className="tabular-nums">
          Section {currentStep + 1} of {total}
        </span>
        <SaveStatusBadge status={saveStatus} />
      </div>
    </nav>
  );
}

function SaveStatusBadge({ status }: { status: SaveStatus }) {
  const base = "inline-flex items-center gap-1 text-[11px]";
  if (status.kind === "saving") {
    return (
      <span className={`${base} text-stone-500`}>
        <Loader2 size={11} className="animate-spin" /> Saving
      </span>
    );
  }
  if (status.kind === "saved") {
    return (
      <span className={`${base} text-sage-navy/80`}>
        <CheckCircle2 size={11} /> Saved
      </span>
    );
  }
  if (status.kind === "paused") {
    return (
      <span className={`${base} text-sage-copper-deep`}>
        <PauseCircle size={11} /> Saving paused. Will retry.
      </span>
    );
  }
  if (status.kind === "error") {
    return (
      <span className={`${base} text-red-700`}>
        <AlertCircle size={11} /> {status.message}
      </span>
    );
  }
  return (
    <span className={`${base} text-stone-400`}>Auto saves as you type</span>
  );
}

// ── Template viewer modal ────────────────────────────────────

function AgreementTemplateModal({ onClose }: { onClose: () => void }) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    let createdUrl: string | null = null;
    setLoading(true);
    fetchAgreementTemplatePdfBlob()
      .then(async (res) => {
        if (!res.ok) {
          throw new Error(`Couldn't load the agreement (${res.status})`);
        }
        const blob = await res.blob();
        if (cancelled) return;
        createdUrl = URL.createObjectURL(blob);
        setBlobUrl(createdUrl);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : "Couldn't load the agreement.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, []);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Agreement template"
      className="fixed inset-0 z-50 bg-black/60 flex items-stretch sm:items-center justify-center p-0 sm:p-6"
      onClick={onClose}
    >
      <div
        className="bg-white w-full sm:max-w-4xl sm:rounded-2xl shadow-xl flex flex-col h-full sm:h-[88vh] overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="px-4 sm:px-5 py-3 border-b border-stone-200 flex items-center justify-between gap-3">
          <div>
            <p className="text-[10px] font-bold uppercase tracking-widest text-sage-copper">
              Reference document
            </p>
            <h3 className="font-serif text-lg text-sage-navy">
              The full Sage IT agreement
            </h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="p-1.5 rounded-md hover:bg-stone-100 text-gray-600"
          >
            <X size={16} />
          </button>
        </header>
        <div className="flex-1 bg-stone-100">
          {loading ? (
            <div className="flex items-center justify-center h-full">
              <Loader2 size={28} className="animate-spin text-sage-navy" />
            </div>
          ) : error ? (
            <div className="flex items-center justify-center h-full px-6 text-center">
              <p className="text-sm text-red-700 inline-flex items-center gap-1.5">
                <AlertCircle size={14} /> {error}
              </p>
            </div>
          ) : blobUrl ? (
            <iframe
              title="Sage IT agreement template"
              src={blobUrl}
              className="w-full h-full"
            />
          ) : null}
        </div>
      </div>
    </div>
  );
}
