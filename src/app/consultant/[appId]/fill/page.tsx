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
  ShieldCheck,
  Sparkles,
  X,
} from "lucide-react";

import SignaturePad from "@/components/common/SignaturePad";
import AgreementClauseView from "@/components/consultant/AgreementClauseView";
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
  saveConsultantFill,
  signConsultantApplication,
  uploadConsultantCheque,
  type AgreementContent,
  type ConsultantApplication,
  type ConsultantFillPayload,
} from "@/lib/api";

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

function emptyAffirmations(): Record<AffirmationFlag, boolean> {
  const out = {} as Record<AffirmationFlag, boolean>;
  for (const flag of AFFIRMATION_FLAGS) out[flag] = false;
  return out;
}

function buildInitialState(app: ConsultantApplication | null): FormState {
  const fields: Record<string, string> = {};
  for (const key of ALL_FIELD_KEYS) {
    const v = app?.[key as keyof ConsultantApplication];
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
    return /^\d{3}-\d{2}-\d{4}$/.test(trimmed);
  }
  if (field.type === "id-type") {
    return trimmed === "DL" || trimmed === "STATE_ID";
  }
  return true;
}

/** Build G — formats an ISO yyyy-MM-dd date string as MM-DD-YYYY. */
function formatUsDate(iso: string | null | undefined): string {
  if (!iso) return "";
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!match) return iso;
  return `${match[2]}-${match[3]}-${match[1]}`;
}

/** Build G — digit-only auto-mask for routing/account inputs. */
function digitsOnly(raw: string, maxLen: number): string {
  return raw.replace(/\D/g, "").slice(0, maxLen);
}

/** Build G — XXX-XX-XXXX auto-mask for SSN input. */
function formatSsn(raw: string): string {
  const digits = raw.replace(/\D/g, "").slice(0, 9);
  if (digits.length <= 3) return digits;
  if (digits.length <= 5) return `${digits.slice(0, 3)}-${digits.slice(3)}`;
  return `${digits.slice(0, 3)}-${digits.slice(3, 5)}-${digits.slice(5)}`;
}

/**
 * True iff any non-readOnly, non-implementationPartner field in this
 * appendix has a value OR the affirmation flag is set. CORE sections
 * (no appendixKey) return false — they're always required, never
 * "touched"-conditional.
 */
function isAppendixTouched(section: AgreementSection, form: FormState): boolean {
  if (!section.appendixKey) return false;
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
  if (!isSectionActive(section, form, reqs)) return false;
  return true;
}

function isSectionComplete(
  section: AgreementSection,
  form: FormState,
  reqs: EffectiveRequirements,
  chequeUploaded: boolean,
): boolean {
  for (const field of section.fields) {
    // Build G — chequeUpload has a non-form completion signal.
    if (field.type === "file") {
      if (!isFieldRequired(field, section, form, reqs)) continue;
      if (!chequeUploaded) return false;
      continue;
    }
    if (!isFieldRequired(field, section, form, reqs)) continue;
    if (!isFieldValueValid(field, form.fields[field.key] ?? "")) {
      return false;
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

function firstIncompleteIndex(
  form: FormState,
  reqs: EffectiveRequirements,
  chequeUploaded: boolean,
): number {
  for (let i = 0; i < AGREEMENT_SECTIONS.length - 1; i++) {
    if (!isSectionComplete(AGREEMENT_SECTIONS[i], form, reqs, chequeUploaded)) return i;
  }
  return AGREEMENT_SECTIONS.length - 1;
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
  const [templateOpen, setTemplateOpen] = useState(false);
  // Build G — Appendix 5 cheque upload state. True once the server
  // confirms the public_id is stored. Wizard mirrors the row.
  const [chequeUploaded, setChequeUploaded] = useState(false);
  const [chequeUploadError, setChequeUploadError] = useState("");
  const [chequeUploading, setChequeUploading] = useState(false);
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
      router.replace("/consultant");
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
        setForm(initial);
        lastSavedRef.current = { ...initial };
        setChequeUploaded(Boolean(data.chequePublicId));
        // Resume at the first incomplete section (or step 0 for a
        // brand-new application). REVISION_REQUESTED also resumes
        // from whichever section still has gaps after the ERM kick.
        setCurrentStep(firstIncompleteIndex(
            initial, effectiveRequirements(data), Boolean(data.chequePublicId)));
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

  // Auto-save (reuses Phase 5 internals) ───────────────────────
  const computeDelta = useCallback(
    (current: FormState): ConsultantFillPayload => {
      const delta: ConsultantFillPayload = {};
      const snap = lastSavedRef.current;
      for (const key of ALL_FIELD_KEYS) {
        if (current.fields[key] !== snap.fields[key]) {
          (delta as Record<string, string>)[key] = current.fields[key];
        }
      }
      for (const flag of AFFIRMATION_FLAGS) {
        if (current.affirmations[flag] !== snap.affirmations[flag]) {
          (delta as Record<string, boolean>)[flag] = current.affirmations[flag];
        }
      }
      return delta;
    },
    [],
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

  // Build G — Appendix 5 cheque upload. Uploads via multipart POST;
  // the row's chequePublicId is the "uploaded ✓" signal. The wizard
  // mirrors that as local state so it doesn't have to refetch the
  // whole application after every upload.
  const handleChequeUpload = useCallback(
    async (file: File) => {
      if (!appId) return;
      setChequeUploadError("");
      setChequeUploading(true);
      try {
        await uploadConsultantCheque(appId, file);
        setChequeUploaded(true);
      } catch (e) {
        const msg = e instanceof Error ? e.message : "Couldn't upload.";
        setChequeUploadError(msg);
      } finally {
        setChequeUploading(false);
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
  const reqs = useMemo<EffectiveRequirements>(
    () => effectiveRequirements(app),
    [app],
  );

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
      if (!form.signature) {
        throw new Error("Your primary signature is required before you can submit.");
      }
      if (!form.finalSignature) {
        throw new Error("Please draw your final signature on the review step before submitting.");
      }
      const updated = await signConsultantApplication(
        appId,
        form.signedLegalName.trim(),
        form.signature,
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
  }, [appId, computeDelta, form, reqs, chequeUploaded, router]);

  // Step accessors ──────────────────────────────────────────────
  const section = AGREEMENT_SECTIONS[currentStep];
  const sectionStatus = useMemo(
    () =>
      AGREEMENT_SECTIONS.map((s, i) => {
        const complete =
          i === AGREEMENT_SECTIONS.length - 1
            ? AGREEMENT_SECTIONS.slice(0, -1).every((sec) =>
                isSectionComplete(sec, form, reqs, chequeUploaded),
              ) && Boolean(form.finalSignature)
            : isSectionComplete(s, form, reqs, chequeUploaded);
        return { id: s.id, title: s.title, step: s.step, complete };
      }),
    [form, reqs, chequeUploaded],
  );
  const canAdvance = isSectionComplete(section, form, reqs, chequeUploaded);
  const isReviewStep = currentStep === AGREEMENT_SECTIONS.length - 1;
  // Build G — submit is gated on EVERY non-review section being
  // complete, the final signature being drawn, the consultant having
  // SEEN the generated PDF preview, AND the read-confirmation
  // attestation being ticked. Missing any of these keeps the submit
  // button disabled.
  const allComplete = useMemo(
    () =>
      AGREEMENT_SECTIONS.slice(0, -1).every((s) =>
        isSectionComplete(s, form, reqs, chequeUploaded),
      )
      && Boolean(form.finalSignature)
      && attestation
      && previewSeen,
    [form, reqs, chequeUploaded, attestation, previewSeen],
  );

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

  // Build K — post-submit experience is status-only. The wizard never
  // mounts in these states; the consultant sees a calm confirmation
  // screen with no PDF, no download, no edit affordance.
  if (app.status === "VERIFIED" || app.status === "SIGNED"
          || app.status === "UPDATED") {
    return <ConsultantStatusScreen kind="sent" onSignOut={() => {
      router.replace("/consultant/dashboard");
    }} />;
  }
  if (app.status === "COMPLETED") {
    return <ConsultantStatusScreen kind="accepted" onSignOut={() => {
      router.replace("/consultant/dashboard");
    }} />;
  }
  // Build L — invite went stale (15-day window). The consultant sees a
  // clear "expired" state; the wizard never mounts. They can re-engage
  // only after an ERM resends the invite, which restarts the clock.
  if (app.status === "EXPIRED") {
    return <ConsultantStatusScreen kind="expired" onSignOut={() => {
      router.replace("/consultant/dashboard");
    }} />;
  }

  return (
    <main className="min-h-screen bg-[#F7F4EE] pb-24 lg:pb-12 antialiased">
      <meta name="robots" content="noindex,nofollow" />

      <header className="border-b border-stone-200/80 bg-white/80 backdrop-blur sticky top-0 z-30">
        <div className="max-w-7xl mx-auto px-4 sm:px-8 h-14 flex items-center justify-between">
          <span className="font-serif text-[15px] font-semibold tracking-tight text-sage-navy">
            Sage IT
          </span>
          <span className="hidden sm:inline text-[12px] text-stone-500">
            Consultant Agreement
          </span>
          <SaveStatusBadge status={saveStatus} />
        </div>
      </header>

      <Stepper
        sections={sectionStatus}
        currentStep={currentStep}
        onJump={(i) => {
          // Allow jumping to any earlier (complete) section, or to the
          // current/next-incomplete; block forward leaps over gaps so
          // the consultant can't skip a required affirmation.
          if (i < currentStep) {
            setCurrentStep(i);
            return;
          }
          for (let j = 0; j <= i; j++) {
            if (j !== AGREEMENT_SECTIONS.length - 1 && !sectionStatus[j].complete) {
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
                    document. Everything you filled in Phase 1 is preserved.
                    Complete the sections now marked Required for Phase 2,
                    re-sign, and submit.
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
                    Update the highlighted fields, then submit to send it back
                    for verification.
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
            onJumpToSection={(idx) => setCurrentStep(idx)}
            onFinalSignature={setFinalSignature}
            onLegalName={setLegalName}
            allComplete={allComplete}
            chequeUploaded={chequeUploaded}
            attestation={attestation}
            onAttestation={setAttestation}
            previewSeen={previewSeen}
            onPreviewSeen={markPreviewSeen}
            onBack={() => setCurrentStep((s) => Math.max(0, s - 1))}
            onSubmit={() => void handleSubmit()}
            submitting={submitting}
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
            consultantEmail={app.consultantEmail}
            effectiveDateText={formatUsDate(app.effectiveDate)}
            onOpenTemplate={() => setTemplateOpen(true)}
            chequeUploaded={chequeUploaded}
            chequeUploading={chequeUploading}
            chequeUploadError={chequeUploadError}
            onUploadCheque={(f) => void handleChequeUpload(f)}
            phase={app.phase ?? 1}
            onBack={() => setCurrentStep((s) => Math.max(0, s - 1))}
            onNext={() =>
              setCurrentStep((s) =>
                Math.min(AGREEMENT_SECTIONS.length - 1, s + 1),
              )
            }
            canAdvance={canAdvance}
            submitting={submitting}
            isFirstStep={currentStep === 0}
          />
        )}
        {submitError && (
          <div className="max-w-[min(100%,860px)] mt-6">
            <SubmitErrorPanel
              message={submitError}
              missing={submitMissing}
              onJumpToSection={(idx, fieldKey) => {
                setCurrentStep(idx);
                if (fieldKey) {
                  window.setTimeout(() => {
                    const el = document.getElementById(`field-${fieldKey}`);
                    if (el) {
                      el.scrollIntoView({ behavior: "smooth", block: "center" });
                      (el as HTMLElement).focus();
                    }
                  }, 60);
                }
              }}
            />
          </div>
        )}
      </section>

      <FooterNav
        currentStep={currentStep}
        total={AGREEMENT_SECTIONS.length}
        canAdvance={isReviewStep ? allComplete : canAdvance}
        saveStatus={saveStatus}
        submitting={submitting}
        onBack={() => setCurrentStep((s) => Math.max(0, s - 1))}
        onNext={() =>
          setCurrentStep((s) =>
            Math.min(AGREEMENT_SECTIONS.length - 1, s + 1),
          )
        }
        onSubmit={() => void handleSubmit()}
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
 * Build P — non-overflowing step progress. A 10-segment progress bar
 * shows where the consultant is (completed / current / upcoming) plus
 * the CURRENT section name spelled out in full. A "Jump to section"
 * menu opens a popover with the same 10-section checklist; section
 * names never clip and the document below stays as wide as possible.
 */
function Stepper({
  sections,
  currentStep,
  onJump,
}: {
  sections: { id: string; title: string; step: number; complete: boolean }[];
  currentStep: number;
  onJump: (i: number) => void;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const current = sections[currentStep];
  const total = sections.length;
  return (
    <nav
      aria-label="Agreement sections"
      className="sticky top-14 z-20 border-b border-stone-200/80 bg-[#F7F4EE]/90 backdrop-blur"
    >
      <div className="max-w-7xl mx-auto px-4 sm:px-8 py-4">
        <div className="flex items-center justify-between gap-4 flex-wrap">
          <div className="min-w-0 flex-1">
            <p className="text-xs text-stone-500">
              Section {currentStep + 1} of {total}
            </p>
            <h2 className="font-serif text-[17px] sm:text-lg text-sage-navy mt-0.5 leading-snug [text-wrap:balance]">
              {current?.title ?? ""}
            </h2>
          </div>
          <div className="relative shrink-0">
            <button
              type="button"
              onClick={() => setMenuOpen((v) => !v)}
              aria-haspopup="menu"
              aria-expanded={menuOpen}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium border border-stone-300 bg-white text-stone-700 hover:border-sage-navy/40 hover:text-sage-navy motion-safe:transition-colors cursor-pointer"
            >
              Jump to section
              <ChevronDown
                size={12}
                className={
                  "motion-safe:transition-transform motion-safe:duration-150 motion-safe:ease-out "
                  + (menuOpen ? "rotate-180" : "")
                }
              />
            </button>
            {menuOpen && (
              <>
                <div
                  className="fixed inset-0 z-10"
                  aria-hidden
                  onClick={() => setMenuOpen(false)}
                />
                <div
                  role="menu"
                  className="absolute right-0 top-full mt-2 z-20 w-[min(86vw,360px)] rounded-xl border border-stone-200 bg-white shadow-[0_10px_30px_-12px_rgba(15,31,68,0.18)] overflow-hidden"
                >
                  <ol className="max-h-[60vh] overflow-y-auto">
                    {sections.map((s, i) => {
                      const active = i === currentStep;
                      return (
                        <li key={s.id}>
                          <button
                            type="button"
                            onClick={() => {
                              setMenuOpen(false);
                              onJump(i);
                            }}
                            role="menuitem"
                            className={
                              "w-full flex items-center gap-3 px-4 py-2.5 text-left text-sm motion-safe:transition-colors "
                              + (active
                                ? "bg-sage-navy/5 text-sage-navy"
                                : "text-stone-700 hover:bg-stone-50")
                            }
                          >
                            <span
                              className={
                                "inline-flex items-center justify-center h-6 w-6 rounded-full text-[11px] font-medium tabular-nums "
                                + (s.complete
                                  ? "bg-sage-navy text-white"
                                  : active
                                    ? "bg-white border border-sage-navy text-sage-navy"
                                    : "bg-stone-100 text-stone-600")
                              }
                            >
                              {s.complete ? (
                                <CheckCircle2 size={13} />
                              ) : (
                                s.step
                              )}
                            </span>
                            <span className="font-serif text-[15px] leading-tight">
                              {s.title}
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
        </div>
        <div
          className="mt-4 grid gap-1.5"
          style={{ gridTemplateColumns: `repeat(${total}, minmax(0, 1fr))` }}
          aria-hidden
        >
          {sections.map((s, i) => {
            const active = i === currentStep;
            const past = i < currentStep;
            return (
              <span
                key={s.id}
                className={
                  "h-1.5 rounded-full motion-safe:transition-colors motion-safe:duration-300 motion-safe:ease-out "
                  + (s.complete
                    ? "bg-sage-navy"
                    : active
                      ? "bg-sage-copper"
                      : past
                        ? "bg-sage-navy/30"
                        : "bg-stone-200")
                }
              />
            );
          })}
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
  chequeUploaded,
  chequeUploading,
  chequeUploadError,
  onUploadCheque,
  phase,
  onBack,
  onNext,
  canAdvance,
  submitting,
  isFirstStep,
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
  chequeUploaded: boolean;
  chequeUploading: boolean;
  chequeUploadError: string;
  onUploadCheque: (file: File) => void;
  phase: number;
  onBack: () => void;
  onNext: () => void;
  canAdvance: boolean;
  submitting: boolean;
  isFirstStep: boolean;
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

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_360px] xl:grid-cols-[minmax(0,1fr)_380px] gap-6 lg:gap-10 items-start">
      {/* ── Reading column (dominant) ─────────────────────────── */}
      <article
        className="bg-white border border-stone-200/80 rounded-2xl px-6 sm:px-10 lg:px-14 py-8 sm:py-10 lg:py-12 motion-safe:transition-opacity"
        aria-labelledby="section-title"
      >
        <p className="text-xs font-medium text-stone-500 tabular-nums">
          Section {section.step}
        </p>
        <h1
          id="section-title"
          className="font-serif text-3xl sm:text-[34px] leading-[1.15] text-sage-navy mt-1.5 [text-wrap:balance]"
        >
          {section.title}
        </h1>
        {(showOptionalBadge || showPhase2RequiredBadge) && (
          <div className="mt-3 flex gap-2 flex-wrap">
            {showOptionalBadge && (
              <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-medium bg-stone-100 text-stone-700">
                Optional. You can skip this section.
              </span>
            )}
            {showPhase2RequiredBadge && (
              <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-medium bg-sage-copper/12 text-sage-copper-deep">
                Required for Phase 2
              </span>
            )}
          </div>
        )}

        {/* Plain-English aside */}
        <aside className="mt-6 rounded-xl bg-[#FBF1E8] px-5 py-4 max-w-[68ch]">
          <p className="text-[11px] font-semibold tracking-wide text-sage-copper-deep">
            In plain English
          </p>
          <p className="mt-1.5 font-serif text-[15.5px] leading-[1.6] text-stone-800 [text-wrap:pretty]">
            {section.summary}
          </p>
          <p className="mt-3 text-[13px] leading-[1.55] text-stone-600 [text-wrap:pretty]">
            <span className="font-semibold text-stone-700">Why this matters. </span>
            {section.why}
          </p>
        </aside>

        {/* Cover-step effective date callout */}
        {isCoverStep && (
          <div className="mt-6 inline-flex items-start gap-2 text-[13px] text-stone-600">
            <Lock size={13} className="mt-0.5 shrink-0 text-stone-500" />
            <span>
              Effective date{" "}
              <span className="font-semibold text-sage-navy tabular-nums">
                {effectiveDateText || "to be set"}
              </span>
              . Set by Sage IT.
            </span>
          </div>
        )}

        {/* Formal clauses */}
        {blocks && blocks.length > 0 ? (
          <div className="mt-8 max-w-[68ch]">
            <div className="text-[10px] font-medium tracking-[0.18em] text-stone-400 uppercase">
              The contract text
            </div>
            <div className="mt-3 border-t border-stone-200 pt-5">
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
              className="mt-6 inline-flex items-center gap-1.5 text-[12px] font-medium text-stone-500 hover:text-sage-navy motion-safe:transition-colors underline underline-offset-[3px] decoration-stone-300 hover:decoration-sage-navy"
            >
              <FileText size={12} /> View the formatted PDF
            </button>
          </div>
        ) : (
          <div className="mt-8 max-w-[68ch]">
            <p className="font-serif text-[16px] leading-[1.65] text-stone-700">
              {section.summary}
            </p>
            <button
              type="button"
              onClick={onOpenTemplate}
              className="mt-6 inline-flex items-center gap-1.5 text-[12px] font-medium text-stone-500 hover:text-sage-navy motion-safe:transition-colors underline underline-offset-[3px] decoration-stone-300 hover:decoration-sage-navy"
            >
              <FileText size={12} /> View the formatted PDF
            </button>
          </div>
        )}
      </article>

      {/* ── Sticky action rail ─────────────────────────────────── */}
      <aside className="lg:sticky lg:top-[200px] lg:self-start">
        <div className="bg-white border border-stone-200/80 rounded-2xl px-5 sm:px-6 py-5 space-y-5">
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
                        : form.fields[field.key] ?? ""
                    }
                    onChange={(v) => onField(field.key, v)}
                    onBlur={() => onTouched(field.key)}
                    touched={touched.has(field.key)}
                    revealed={revealed.has(field.key)}
                    onRevealToggle={(on) => onRevealed(field.key, on)}
                  />
                ))}
              </div>
            </div>
          )}

          {section.id === "appendix5" && (
            <ChequeUploadBlock
              uploaded={chequeUploaded}
              uploading={chequeUploading}
              error={chequeUploadError}
              onUpload={onUploadCheque}
            />
          )}

          {section.requiresSignature && (
            <SignatureBlock
              signature={form.signature}
              legalName={form.signedLegalName}
              onSignature={onSignature}
              onLegalName={onLegalName}
            />
          )}

          {section.requiresAffirmation && !section.requiresSignature && (
            <SignaturePreviewBlock signature={form.signature} />
          )}

          {section.requiresAffirmation && section.affirmationFlag && (
            <AffirmationBlock
              flag={section.affirmationFlag}
              checked={form.affirmations[section.affirmationFlag]}
              onChange={(v) => onAffirm(section.affirmationFlag!, v)}
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
}) {
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
            ? "Enter the SSN as XXX-XX-XXXX."
            : field.type === "id-type"
              ? "Pick one."
              : "This field is required."
    : "";

  const baseInputClasses =
    "w-full px-3 py-2.5 text-[14px] rounded-md border bg-white text-stone-900 placeholder:text-stone-400 focus:outline-none focus:ring-2 focus:ring-sage-copper/40 focus:border-sage-copper motion-safe:transition-colors " +
    (invalid
      ? "border-red-300 bg-red-50/40"
      : field.readOnly
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
        readOnly={field.readOnly}
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
              onClick={() => {
                onChange(code);
                onBlur();
              }}
              className={
                pillBase
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
        disabled={field.readOnly}
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
    control = (
      <input
        type="date"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        readOnly={field.readOnly}
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
      if (field.type === "ssn") return onChange(formatSsn(raw));
      return onChange(raw);
    };
    const ph =
      field.placeholder
      ?? (field.type === "routing"
        ? "9 digits"
        : field.type === "account"
          ? "10 digits"
          : field.type === "ssn"
            ? "XXX-XX-XXXX"
            : undefined);
    control = (
      <div className="relative">
        <input
          type={inputType}
          inputMode={
            field.type === "routing"
            || field.type === "account"
            || field.type === "ssn"
              ? "numeric"
              : undefined
          }
          autoComplete="off"
          value={value}
          onChange={(e) => handleSensitiveChange(e.target.value)}
          onBlur={onBlur}
          placeholder={ph}
          readOnly={field.readOnly}
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
        readOnly={field.readOnly}
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
      {field.help && !errorMsg && (
        <p className="mt-1 text-[11px] text-gray-500">{field.help}</p>
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
}: {
  signature: string | null;
  legalName: string;
  onSignature: (dataUrl: string | null) => void;
  onLegalName: (value: string) => void;
}) {
  const [redrawing, setRedrawing] = useState(false);
  const captured = Boolean(signature);
  const shouldShowPad = !captured || redrawing;

  return (
    <div className="space-y-4">
      <div>
        <h3 className="font-serif text-base text-sage-navy">
          Sign your agreement
        </h3>
        <p className="text-[13px] text-stone-600 mt-1 leading-snug">
          Upload an image of your signature, or draw it instead. The same
          signature applies to every block in the agreement.
        </p>
      </div>

      <div>
        <label className="block text-[12px] font-medium text-stone-700 mb-1.5">
          Your full legal name{" "}
          <span className="text-sage-copper-deep" aria-hidden>
            *
          </span>
        </label>
        <input
          type="text"
          value={legalName}
          onChange={(e) => onLegalName(e.target.value)}
          className="w-full px-3 py-2.5 text-[14px] rounded-md border border-stone-300 bg-white text-stone-900 placeholder:text-stone-400 focus:outline-none focus:ring-2 focus:ring-sage-copper/40 focus:border-sage-copper motion-safe:transition-colors"
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
              className="mt-2 text-[11px] font-semibold text-gray-500 hover:text-sage-navy"
            >
              Cancel re-draw
            </button>
          )}
        </div>
      ) : (
        <div>
          <p className="text-[12px] font-medium text-stone-600 mb-1.5">
            Your captured signature
          </p>
          <div className="inline-flex items-center gap-3 flex-wrap">
            <div className="inline-block rounded-md border border-dashed border-stone-300 bg-stone-50 p-2">
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
              className="text-[12px] font-medium text-stone-500 hover:text-sage-navy motion-safe:transition-colors underline underline-offset-[3px] decoration-stone-300 hover:decoration-sage-navy"
            >
              Replace signature
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function SignaturePreviewBlock({ signature }: { signature: string | null }) {
  if (!signature) {
    return (
      <div className="rounded-xl bg-[#FBF1E8] border border-sage-copper/30 px-4 py-3 text-[12.5px] text-sage-copper-deep inline-flex items-start gap-2">
        <AlertCircle size={14} className="mt-0.5 shrink-0" />
        <span>
          Add your signature on the main agreement step first. We will
          apply it here automatically.
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
  kind: "sent" | "accepted" | "expired";
  onSignOut: () => void;
}) {
  const copy = kind === "sent"
    ? {
        eyebrow: "Submitted",
        title: "Your agreement has been sent for verification.",
        body: "We'll review it and update you here once it's accepted. There's nothing else you need to do right now.",
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
  // The cheque upload field has its own label in the section config
  // but the UI surfaces it via the ChequeUploadBlock; keep the
  // friendly label here too so the missing-list reads consistently.
  out["chequeUpload"] = "Security cheque upload";
  return out;
})();

function fieldFriendlyLabel(key: string): string {
  return FIELD_LABELS[key] ?? key;
}

function SubmitErrorPanel({
  message,
  missing,
  onJumpToSection,
}: {
  message: string;
  missing: {
    missingFields: string[];
    missingAffirmations: string[];
    missingSignature: boolean;
    missingFinalSignature: boolean;
  } | null;
  onJumpToSection: (idx: number, fieldKey?: string) => void;
}) {
  if (!missing) {
    return (
      <div className="mt-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 inline-flex items-start gap-2">
        <AlertCircle size={14} className="mt-0.5 shrink-0" />
        <span>{message}</span>
      </div>
    );
  }

  type Group = {
    sectionIdx: number;
    sectionTitle: string;
    items: { label: string; fieldKey?: string }[];
  };
  const groups = new Map<string, Group>();
  const upsert = (
    sectionIdx: number,
    sectionTitle: string,
    label: string,
    fieldKey?: string,
  ) => {
    const key = `${sectionIdx}|${sectionTitle}`;
    const existing = groups.get(key);
    if (existing) {
      existing.items.push({ label, fieldKey });
    } else {
      groups.set(key, {
        sectionIdx,
        sectionTitle,
        items: [{ label, fieldKey }],
      });
    }
  };

  for (const key of missing.missingFields) {
    const section = findSectionForFieldKey(key);
    if (!section) continue;
    const idx = AGREEMENT_SECTIONS.findIndex((s) => s.id === section.id);
    upsert(idx, section.title, fieldFriendlyLabel(key), key);
  }
  for (const flag of missing.missingAffirmations) {
    const section = findSectionForAffirmation(flag as AffirmationFlag);
    if (!section) continue;
    const idx = AGREEMENT_SECTIONS.findIndex((s) => s.id === section.id);
    upsert(idx, section.title, "Section affirmation not ticked");
  }
  if (missing.missingSignature) {
    const idx = AGREEMENT_SECTIONS.findIndex((s) => s.id === "main-agreement");
    if (idx >= 0) {
      upsert(idx, "The Agreement", "Primary signature not captured");
    }
  }
  if (missing.missingFinalSignature) {
    const idx = AGREEMENT_SECTIONS.findIndex((s) => s.id === "review");
    if (idx >= 0) {
      upsert(idx, "Review & Submit", "Final signature not captured");
    }
  }

  const ordered = Array.from(groups.values()).sort(
    (a, b) => a.sectionIdx - b.sectionIdx,
  );

  if (ordered.length === 0) {
    // Backend rejected for an unknown reason -- fall back to the
    // generic message so the consultant still gets feedback.
    return (
      <div className="mt-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 inline-flex items-start gap-2">
        <AlertCircle size={14} className="mt-0.5 shrink-0" />
        <span>{message}</span>
      </div>
    );
  }

  return (
    <div className="mt-4 rounded-2xl border border-red-200 bg-red-50 p-4 sm:p-5">
      <div className="flex items-start gap-2">
        <AlertCircle size={16} className="text-red-700 mt-0.5 shrink-0" />
        <div>
          <p className="text-sm font-semibold text-red-800">
            {message}
          </p>
          <p className="text-[11px] text-red-700/80 mt-0.5">
            Tap any item below to jump straight to the section that needs it.
          </p>
        </div>
      </div>
      <ul className="mt-3 space-y-2">
        {ordered.map((g) => (
          <li
            key={g.sectionIdx}
            className="rounded-xl border border-red-200 bg-white p-3"
          >
            <div className="flex items-start justify-between gap-3 flex-wrap">
              <div>
                <p className="text-[10px] font-bold uppercase tracking-widest text-red-700">
                  {g.sectionTitle}
                </p>
                <ul className="mt-1.5 space-y-0.5">
                  {g.items.map((item, i) => (
                    <li
                      key={i}
                      className="text-xs text-gray-800 inline-flex items-center gap-1.5"
                    >
                      <span className="block h-1 w-1 rounded-full bg-red-500" />
                      {item.label}
                    </li>
                  ))}
                </ul>
              </div>
              <button
                type="button"
                onClick={() =>
                  onJumpToSection(g.sectionIdx, g.items[0]?.fieldKey)
                }
                className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-md text-[11px] font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer shrink-0"
              >
                Go to section <ArrowRight size={11} />
              </button>
            </div>
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

  useEffect(() => {
    if (!pages || reachedEnd) return;
    const el = scrollerRef.current;
    if (!el) return;
    const check = () => {
      if (el.scrollHeight <= el.clientHeight + 1) markEnd();
    };
    check();
    const observer = new ResizeObserver(check);
    observer.observe(el);
    Array.from(el.querySelectorAll("img")).forEach((img) => observer.observe(img));
    return () => observer.disconnect();
  }, [pages, reachedEnd, markEnd]);

  return (
    <section className="bg-white rounded-2xl border border-stone-200 shadow-sm overflow-hidden">
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
            Generating watermarked preview…
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
            className="max-h-[640px] overflow-y-auto space-y-3 pr-2"
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
          </div>
        )}
      </div>
    </section>
  );
}

// ── Build G: Appendix 5 security-cheque upload ────────────────

function ChequeUploadBlock({
  uploaded,
  uploading,
  error,
  onUpload,
}: {
  uploaded: boolean;
  uploading: boolean;
  error: string;
  onUpload: (file: File) => void;
}) {
  const inputId = "consultant-wizard-cheque";
  return (
    <section
      className={
        "rounded-2xl border shadow-sm p-5 space-y-3 "
        + (uploaded
          ? "bg-emerald-50/40 border-emerald-300"
          : "bg-white border-stone-200")
      }
    >
      <div>
        <p className="text-[10px] font-bold uppercase tracking-widest text-sage-copper">
          Security cheque
        </p>
        <h3 className="font-serif text-lg text-sage-navy mt-0.5">
          Upload your post-dated cheque
        </h3>
        <p className="text-xs text-gray-700 mt-1 leading-relaxed">
          Required to complete Appendix 5. Acceptable formats: JPG, PNG,
          HEIC, or PDF. Maximum size 10 MB. The file is stored privately
          and is visible only to Sage IT.
        </p>
      </div>

      {uploaded ? (
        <div className="inline-flex items-center gap-2 text-xs font-semibold text-emerald-800">
          <CheckCircle2 size={14} /> Uploaded. You can re-upload to replace it.
        </div>
      ) : null}

      <label
        htmlFor={inputId}
        className={
          "inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold border cursor-pointer transition-colors "
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
            <FileText size={12} /> {uploaded ? "Replace file" : "Choose file"}
          </>
        )}
      </label>
      <input
        id={inputId}
        type="file"
        accept="image/*,application/pdf"
        disabled={uploading}
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) onUpload(f);
          // Reset so the same filename re-fires onChange when retried.
          e.currentTarget.value = "";
        }}
        className="hidden"
      />

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
}: {
  flag: AffirmationFlag;
  checked: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <label
      htmlFor={`affirm-${flag}`}
      className={
        "flex items-start gap-3 p-4 rounded-xl border cursor-pointer motion-safe:transition-colors "
        + (checked
          ? "bg-sage-navy/5 border-sage-navy/40"
          : "bg-white border-stone-300 hover:border-sage-navy/40 hover:bg-stone-50/60")
      }
    >
      <input
        id={`affirm-${flag}`}
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="mt-0.5 h-4 w-4 accent-sage-navy"
      />
      <span className="text-[13.5px] leading-snug text-stone-800">
        I have read and understood this section and agree to its terms.
      </span>
    </label>
  );
}

// ── Review step ──────────────────────────────────────────────

function ReviewStep({
  appId,
  form,
  reqs,
  onJumpToSection,
  onFinalSignature,
  onLegalName,
  allComplete,
  chequeUploaded,
  attestation,
  onAttestation,
  previewSeen,
  onPreviewSeen,
  onBack,
  onSubmit,
  submitting,
}: {
  appId: string;
  form: FormState;
  reqs: EffectiveRequirements;
  onJumpToSection: (idx: number) => void;
  onFinalSignature: (dataUrl: string | null) => void;
  onLegalName: (value: string) => void;
  allComplete: boolean;
  chequeUploaded: boolean;
  attestation: boolean;
  onAttestation: (value: boolean) => void;
  previewSeen: boolean;
  onPreviewSeen: () => void;
  onBack: () => void;
  onSubmit: () => void;
  submitting: boolean;
}) {
  return (
    <div className="space-y-6">
      <div>
        <p className="text-xs text-stone-500">Final step</p>
        <h1 className="font-serif text-3xl sm:text-[34px] leading-[1.15] text-sage-navy mt-1 [text-wrap:balance]">
          Read the agreement, then sign and submit
        </h1>
        <p className="text-[15px] text-stone-700 mt-3 max-w-[68ch] leading-[1.6]">
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

      {AGREEMENT_SECTIONS.slice(0, -1).map((section, idx) => {
        const complete = isSectionComplete(section, form, reqs, chequeUploaded);
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

            {section.id === "appendix5" && (
              <p className="mt-3 text-[12.5px] text-stone-600 inline-flex items-center gap-1.5">
                {chequeUploaded ? (
                  <CheckCircle2 size={12} className="text-sage-navy" />
                ) : (
                  <AlertCircle size={12} className="text-sage-copper-deep" />
                )}
                Cheque {chequeUploaded ? "uploaded" : "not uploaded"}
              </p>
            )}

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
        ) : (
          <p className="mt-2 text-[12.5px] text-sage-copper-deep inline-flex items-center gap-1.5">
            <AlertCircle size={12} /> Not captured. Go back to the main
            agreement step to add it.
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

      <FinalSignatureBlock
        finalSignature={form.finalSignature}
        legalName={form.signedLegalName}
        onFinalSignature={onFinalSignature}
        onLegalName={onLegalName}
      />

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

      {/* Build P / hotfix — desktop submit cluster. FooterNav is
          lg:hidden so the desktop layout needs its own Submit
          affordance. Mobile users still get the sticky FooterNav
          below the viewport. */}
      <div className="flex items-center justify-between gap-3 pt-4 border-t border-stone-200 max-w-[min(100%,860px)]">
        <button
          type="button"
          onClick={onBack}
          disabled={submitting}
          className="inline-flex items-center gap-1 text-[13px] font-medium text-stone-500 hover:text-sage-navy motion-safe:transition-colors disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer"
        >
          <ArrowLeft size={13} /> Back
        </button>
        <button
          type="button"
          onClick={onSubmit}
          disabled={!allComplete || submitting}
          className={
            "inline-flex items-center gap-1.5 px-5 py-2.5 rounded-md text-sm font-semibold motion-safe:transition-colors cursor-pointer "
            + (allComplete && !submitting
              ? "bg-sage-navy text-white hover:bg-sage-navy-deep"
              : "bg-stone-100 text-stone-400 cursor-not-allowed")
          }
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
}: {
  finalSignature: string | null;
  legalName: string;
  onFinalSignature: (dataUrl: string | null) => void;
  onLegalName: (value: string) => void;
}) {
  const [redrawing, setRedrawing] = useState(false);
  const captured = Boolean(finalSignature);
  const shouldShowPad = !captured || redrawing;

  return (
    <section className="bg-white border border-sage-navy/30 rounded-xl px-5 py-5 space-y-4 max-w-[min(100%,860px)]">
      <div>
        <p className="text-[11px] font-medium text-sage-copper-deep">
          Execution signature
        </p>
        <h3 className="font-serif text-[18px] text-sage-navy mt-1 leading-snug [text-wrap:balance]">
          After reading this, I am formally signing this acknowledgement.
        </h3>
        <p className="text-[13.5px] text-stone-700 mt-2 leading-snug">
          Upload (or draw) your final signature here. It confirms that
          everything above matches what you intended and that you are
          executing the agreement.
        </p>
      </div>

      <div>
        <label className="block text-[12px] font-medium text-stone-700 mb-1.5">
          Your full legal name{" "}
          <span className="text-sage-copper-deep" aria-hidden>
            *
          </span>
        </label>
        <input
          type="text"
          value={legalName}
          onChange={(e) => onLegalName(e.target.value)}
          className="w-full px-3 py-2.5 text-[14px] rounded-md border border-stone-300 bg-white text-stone-900 placeholder:text-stone-400 focus:outline-none focus:ring-2 focus:ring-sage-copper/40 focus:border-sage-copper motion-safe:transition-colors"
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
      className="lg:hidden fixed bottom-0 left-0 right-0 bg-white border-t border-stone-200 z-30"
    >
      <div className="max-w-7xl mx-auto px-4 py-3 flex items-center justify-between gap-3">
        <button
          type="button"
          onClick={onBack}
          disabled={currentStep === 0 || submitting}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-[13px] font-medium text-stone-700 hover:text-sage-navy motion-safe:transition-colors disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer"
        >
          <ArrowLeft size={13} /> Back
        </button>
        {isReviewStep ? (
          <button
            type="button"
            onClick={onSubmit}
            disabled={!canAdvance || submitting}
            className={
              "inline-flex items-center gap-1.5 px-4 py-2.5 rounded-md text-sm font-semibold motion-safe:transition-colors cursor-pointer "
              + (canAdvance && !submitting
                ? "bg-sage-navy text-white hover:bg-sage-navy-deep"
                : "bg-stone-100 text-stone-400 cursor-not-allowed")
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
                ? "bg-sage-navy text-white hover:bg-sage-navy-deep"
                : "bg-stone-100 text-stone-400 cursor-not-allowed")
            }
          >
            Continue <ArrowRight size={13} />
          </button>
        )}
      </div>
      <div className="px-4 pb-2 text-[11px] text-stone-500 flex items-center justify-between">
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
