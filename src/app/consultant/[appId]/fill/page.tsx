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
      // Parse the structured backend payload when present so we can
      // route back to the first incomplete section.
      let msg = e instanceof Error ? e.message : "Couldn't submit.";
      let routed = false;
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
          msg = obj.message || "Some items are still missing.";
          const incompleteIdx = firstIncompleteIndex(form, reqs, chequeUploaded);
          setCurrentStep(incompleteIdx);
          routed = true;
        }
      } catch {
        /* not JSON; fall through with the original message */
      }
      if (!routed) {
        // Best-effort: also try to find which section is incomplete
        // from local state so the consultant lands somewhere sensible.
        setCurrentStep(firstIncompleteIndex(form, reqs, chequeUploaded));
      }
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

  return (
    <main className="min-h-screen bg-stone-50 pb-40">
      <meta name="robots" content="noindex,nofollow" />

      <header className="bg-sage-navy text-white">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 py-8 sm:py-10">
          <div>
            <p className="text-[10px] sm:text-xs font-bold uppercase tracking-widest text-sage-copper">
              Sage IT Consultant Portal
            </p>
            <h1 className="font-serif text-2xl sm:text-3xl mt-1">
              Complete your agreement
            </h1>
            <p className="text-xs sm:text-sm text-white/80 mt-1.5 max-w-xl">
              Read the full clauses in each section, fill the details
              (they appear in the text as you type), check the
              affirmation, and continue. We auto-save as you go.
            </p>
          </div>
          {/* "View full agreement" is demoted: the real clauses now render
              inline per section. A small "View as formatted PDF" link
              remains in each section's read pane. */}
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

      {app.status === "REVISION_REQUESTED" && app.currentRevisionRemarks && (
        <div className="max-w-5xl mx-auto px-4 sm:px-6 pt-6">
          <div className="rounded-2xl border border-sage-copper/40 bg-orange-50 px-5 py-4 flex items-start gap-3">
            <AlertCircle size={16} className="text-sage-copper-deep shrink-0 mt-0.5" />
            <div>
              <p className="text-[10px] font-bold uppercase tracking-widest text-sage-copper-deep">
                Sage IT requested changes
              </p>
              <p className="text-sm text-gray-800 mt-1 whitespace-pre-wrap leading-relaxed">
                {app.currentRevisionRemarks}
              </p>
              <p className="text-[11px] text-gray-600 mt-2">
                Update the highlighted fields below, then submit again to send
                it back for verification.
              </p>
            </div>
          </div>
        </div>
      )}

      <section className="max-w-5xl mx-auto px-4 sm:px-6 pt-6">
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
          />
        )}
        {submitError && (
          <div className="mt-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 inline-flex items-start gap-2">
            <AlertCircle size={14} className="mt-0.5 shrink-0" />
            <span>{submitError}</span>
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

function Stepper({
  sections,
  currentStep,
  onJump,
}: {
  sections: { id: string; title: string; step: number; complete: boolean }[];
  currentStep: number;
  onJump: (i: number) => void;
}) {
  return (
    <nav
      aria-label="Sections"
      className="sticky top-0 z-20 bg-stone-50/95 backdrop-blur border-b border-stone-200"
    >
      <div className="max-w-5xl mx-auto px-2 sm:px-6 py-3 overflow-x-auto">
        <ol className="flex items-center gap-1.5 sm:gap-2 min-w-max">
          {sections.map((s, i) => {
            const active = i === currentStep;
            const past = i < currentStep;
            return (
              <li key={s.id}>
                <button
                  type="button"
                  onClick={() => onJump(i)}
                  className={
                    "inline-flex items-center gap-1.5 px-2.5 sm:px-3 py-1.5 rounded-full text-[10px] sm:text-[11px] font-semibold transition-colors whitespace-nowrap " +
                    (active
                      ? "bg-sage-navy text-white"
                      : s.complete
                        ? "bg-emerald-100 text-emerald-800 hover:bg-emerald-200"
                        : past
                          ? "bg-amber-100 text-amber-800 hover:bg-amber-200"
                          : "bg-stone-200 text-gray-600 hover:bg-stone-300")
                  }
                >
                  {s.complete && !active ? (
                    <CheckCircle2 size={11} />
                  ) : (
                    <span className="font-mono">{s.step}</span>
                  )}
                  <span className="hidden sm:inline">{s.title}</span>
                </button>
              </li>
            );
          })}
        </ol>
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
}) {
  const isCoverStep = section.id === "cover";
  // F-4 per-section state: optional appendices show an "Optional"
  // badge until the consultant types something, at which point they
  // get an all-or-nothing notice (finish the section or clear it).
  const appendixOptional = Boolean(
    section.appendixKey && !reqs[section.appendixKey],
  );
  const appendixTouched = isAppendixTouched(section, form);
  const showOptionalBadge = appendixOptional && !appendixTouched;
  const showAllOrNothingNotice = appendixOptional && appendixTouched;
  return (
    <div className="grid grid-cols-1 lg:grid-cols-5 gap-4 lg:gap-6">
      {/* Read pane */}
      <aside className="lg:col-span-2 space-y-4">
        <div className="bg-white rounded-2xl border border-stone-200 shadow-sm p-5">
          <p className="text-[10px] font-bold uppercase tracking-widest text-sage-copper">
            Step {section.step} of {AGREEMENT_SECTIONS.length}
          </p>
          <h2 className="font-serif text-xl sm:text-2xl text-sage-navy mt-1">
            {section.title}
          </h2>
          {showOptionalBadge && (
            <span className="mt-2 inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider bg-stone-200 text-gray-700">
              Optional — you can skip
            </span>
          )}
          {(() => {
            const blocks = content?.sections?.[section.id];
            if (blocks && blocks.length > 0) {
              return (
                <>
                  <p className="text-xs text-gray-500 mt-2 mb-3 leading-relaxed">
                    {section.summary}
                  </p>
                  <div className="border-t border-stone-100 pt-3">
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
                    className="mt-3 inline-flex items-center gap-1.5 text-[11px] font-medium text-gray-400 hover:text-sage-navy underline underline-offset-2"
                  >
                    <FileText size={10} /> View as formatted PDF
                  </button>
                </>
              );
            }
            // Fallback (content not loaded): the plain-language summary.
            return (
              <>
                <p className="text-sm text-gray-700 mt-3 leading-relaxed">
                  {section.summary}
                </p>
                <button
                  type="button"
                  onClick={onOpenTemplate}
                  className="mt-4 inline-flex items-center gap-1.5 px-2.5 py-1.5 text-[11px] font-semibold text-sage-navy hover:text-sage-navy-deep underline underline-offset-2"
                >
                  <FileText size={11} /> View the full agreement
                </button>
              </>
            );
          })()}
        </div>
        <div className="bg-orange-50 rounded-2xl border border-sage-copper/30 p-4">
          <p className="inline-flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-widest text-sage-copper-deep">
            <Sparkles size={11} /> Why we need this
          </p>
          <p className="text-xs sm:text-sm text-gray-700 mt-2 leading-relaxed">
            {section.why}
          </p>
        </div>
      </aside>

      {/* Fields / signature / affirmation pane */}
      <div className="lg:col-span-3 space-y-4">
        {isCoverStep && (
          <div className="bg-stone-100 rounded-xl border border-stone-200 px-4 py-3 text-xs text-gray-700 inline-flex items-start gap-2">
            <Lock size={12} className="mt-0.5 shrink-0 text-gray-500" />
            <span>
              <span className="font-semibold">Effective date:</span>{" "}
              {effectiveDateText || "—"}
            </span>
          </div>
        )}

        {showAllOrNothingNotice && (
          <div className="rounded-md border border-sage-copper/40 bg-orange-50 px-3 py-2 text-xs text-sage-copper-deep inline-flex items-start gap-2">
            <AlertCircle size={14} className="mt-0.5 shrink-0" />
            <span>
              This section is optional, but you&apos;ve started filling it
              in. Complete every required field and tick the affirmation,
              or clear all fields to skip the section.
            </span>
          </div>
        )}

        {section.fields.length > 0 && (
          <div className="bg-white rounded-2xl border border-stone-200 shadow-sm p-5">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-x-4 gap-y-4">
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
      </div>
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
    "w-full px-3 py-2 text-sm rounded-md border bg-white focus:outline-none focus:ring-1 focus:ring-sage-navy focus:border-sage-navy " +
    (invalid
      ? "border-red-300 bg-red-50/40"
      : field.readOnly
        ? "border-stone-200 bg-stone-100 cursor-not-allowed"
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
    <div className={wide ? "md:col-span-2" : ""}>
      <label className="block text-[11px] font-semibold uppercase tracking-wider text-gray-600 mb-1">
        {field.label}
        {effectivelyRequired && <span className="text-red-500 ml-1">*</span>}
        {field.readOnly && (
          <span className="ml-1 inline-flex items-center gap-0.5 text-[10px] font-medium normal-case text-gray-500">
            <Lock size={9} /> set by Sage IT
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
    <div className="bg-white rounded-2xl border border-stone-200 shadow-sm p-5 space-y-4">
      <div>
        <p className="text-[11px] font-bold uppercase tracking-widest text-sage-navy">
          Sign once — applied everywhere
        </p>
        <p className="text-sm text-gray-700 mt-1">
          Draw your signature below. We&apos;ll apply it to every
          signature block in the agreement — you won&apos;t be asked to
          re-draw it on later sections.
        </p>
      </div>

      <div>
        <label className="block text-[11px] font-semibold uppercase tracking-wider text-gray-600 mb-1">
          Your full legal name <span className="text-red-500">*</span>
        </label>
        <input
          type="text"
          value={legalName}
          onChange={(e) => onLegalName(e.target.value)}
          className="w-full px-3 py-2 text-sm rounded-md border border-stone-300 bg-white focus:outline-none focus:ring-1 focus:ring-sage-navy focus:border-sage-navy"
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
          <p className="text-[11px] font-semibold uppercase tracking-wider text-gray-600 mb-1.5">
            Captured signature
          </p>
          <div className="inline-block rounded-md border border-dashed border-stone-300 bg-stone-50 p-2">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={signature!}
              alt="Captured signature"
              style={{ maxHeight: 70, maxWidth: 240 }}
            />
          </div>
          <button
            type="button"
            onClick={() => {
              setRedrawing(true);
              onSignature(null);
            }}
            className="ml-3 text-[11px] font-semibold text-sage-navy hover:text-sage-navy-deep underline underline-offset-2"
          >
            Re-draw
          </button>
        </div>
      )}
    </div>
  );
}

function SignaturePreviewBlock({ signature }: { signature: string | null }) {
  if (!signature) {
    return (
      <div className="bg-orange-50 rounded-xl border border-sage-copper/30 p-4 text-xs text-sage-copper-deep inline-flex items-start gap-2">
        <AlertCircle size={14} className="mt-0.5 shrink-0" />
        <span>
          Please draw your signature on the main agreement step before
          continuing -- we&apos;ll reuse it here.
        </span>
      </div>
    );
  }
  return (
    <div className="bg-stone-100 rounded-xl border border-stone-200 p-4">
      <p className="text-[10px] font-bold uppercase tracking-widest text-gray-500">
        Your signature will be applied to this section
      </p>
      <div className="mt-2 inline-block rounded-md border border-dashed border-stone-300 bg-white p-2">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={signature}
          alt="Signature preview"
          style={{ maxHeight: 50, maxWidth: 180 }}
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
  kind: "sent" | "accepted";
  onSignOut: () => void;
}) {
  const copy = kind === "sent"
    ? {
        eyebrow: "Submitted",
        title: "Your agreement has been sent for verification.",
        body: "We'll review it and update you here once it's accepted. There's nothing else you need to do right now.",
      }
    : {
        eyebrow: "Accepted",
        title: "Your agreement has been accepted.",
        body: "Thank you. Your engagement is now in motion. Sage IT will be in touch with the next steps.",
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
  onLoaded,
}: {
  appId: string;
  primarySignature: string | null;
  onLoaded: () => void;
}) {
  const [pages, setPages] = useState<string[] | null>(null);
  const [viewerEmail, setViewerEmail] = useState<string>("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

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
        onLoaded();
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
  }, [appId, primarySignature, onLoaded]);

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
          <div className="max-h-[640px] overflow-y-auto space-y-3 pr-2">
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
          <CheckCircle2 size={14} /> Uploaded — you can re-upload to replace it.
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
        "flex items-start gap-3 p-4 rounded-2xl border cursor-pointer transition-colors " +
        (checked
          ? "bg-emerald-50 border-emerald-200"
          : "bg-white border-stone-300 hover:bg-stone-50")
      }
    >
      <input
        id={`affirm-${flag}`}
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="mt-0.5 h-4 w-4 accent-sage-navy"
      />
      <span className="text-sm text-gray-800">
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
}) {
  return (
    <div className="space-y-5">
      <div className="bg-white rounded-2xl border border-stone-200 shadow-sm p-5">
        <p className="text-[10px] font-bold uppercase tracking-widest text-sage-copper">
          Final step
        </p>
        <h2 className="font-serif text-2xl text-sage-navy mt-1">
          Read the agreement, then sign and submit
        </h2>
        <p className="text-sm text-gray-700 mt-2">
          Below is the actual agreement that will be filed once you submit
          — your details and primary signature are already in it. Read it
          carefully, tick the attestation, then sign and submit.
        </p>
      </div>

      <ConsultantImagesPreview
        appId={appId}
        primarySignature={form.signature}
        onLoaded={onPreviewSeen}
      />

      <label
        htmlFor="consultant-review-attestation"
        className={
          "flex items-start gap-3 p-4 rounded-2xl border cursor-pointer transition-colors "
          + (attestation
            ? "bg-emerald-50 border-emerald-200"
            : "bg-white border-stone-300 hover:bg-stone-50")
        }
      >
        <input
          id="consultant-review-attestation"
          type="checkbox"
          checked={attestation}
          onChange={(e) => onAttestation(e.target.checked)}
          className="mt-0.5 h-4 w-4 accent-sage-navy"
        />
        <span className="text-sm text-gray-800">
          I have read this agreement and confirm the details are mine and
          accurate.
        </span>
      </label>

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
            className="bg-white rounded-2xl border border-stone-200 shadow-sm p-5"
          >
            <div className="flex items-start justify-between gap-3 flex-wrap">
              <div>
                <p className="text-[10px] font-bold uppercase tracking-widest text-gray-500">
                  Step {section.step}
                </p>
                <h3 className="font-serif text-lg text-sage-navy mt-0.5">
                  {section.title}
                </h3>
              </div>
              <div className="flex items-center gap-2 flex-wrap">
                <span
                  className={
                    "inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider " +
                    (optionalAndSkipped
                      ? "bg-stone-200 text-gray-700"
                      : complete
                        ? "bg-emerald-100 text-emerald-800"
                        : "bg-amber-100 text-amber-800")
                  }
                >
                  {optionalAndSkipped ? (
                    <>
                      <CheckCircle2 size={11} />
                      Skipped (optional)
                    </>
                  ) : complete ? (
                    <>
                      <CheckCircle2 size={11} />
                      Complete
                    </>
                  ) : (
                    <>
                      <AlertCircle size={11} />
                      Needs attention
                    </>
                  )}
                </span>
                <button
                  type="button"
                  onClick={() => onJumpToSection(idx)}
                  className="text-[11px] font-semibold text-sage-navy hover:text-sage-navy-deep underline underline-offset-2"
                >
                  Edit
                </button>
              </div>
            </div>

            {section.fields.length > 0 && (
              <dl className="mt-3 grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2">
                {section.fields.map((field) => {
                  if (field.type === "file") return null;
                  const raw = form.fields[field.key] ?? "";
                  let displayed: string;
                  if (field.type === "date") {
                    displayed = formatUsDate(raw) || "—";
                  } else if (field.type === "id-type") {
                    displayed =
                      raw === "DL"
                        ? "Driver's License"
                        : raw === "STATE_ID"
                          ? "State ID"
                          : "—";
                  } else if (field.sensitive && raw.length > 0) {
                    displayed = maskValue(raw);
                  } else {
                    displayed = raw || "—";
                  }
                  return (
                    <div key={field.key}>
                      <dt className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">
                        {field.label}
                      </dt>
                      <dd className="text-sm text-gray-900 break-words">
                        {displayed}
                      </dd>
                    </div>
                  );
                })}
              </dl>
            )}

            {section.id === "appendix5" && (
              <p className="mt-3 text-xs text-gray-700 inline-flex items-center gap-1.5">
                {chequeUploaded ? (
                  <CheckCircle2 size={12} className="text-emerald-700" />
                ) : (
                  <AlertCircle size={12} className="text-amber-700" />
                )}
                Cheque{" "}
                {chequeUploaded ? "uploaded" : "not uploaded"}
              </p>
            )}

            {section.requiresAffirmation && section.affirmationFlag && (
              <p className="mt-3 text-xs text-gray-700 inline-flex items-center gap-1.5">
                {form.affirmations[section.affirmationFlag] ? (
                  <CheckCircle2 size={12} className="text-emerald-700" />
                ) : (
                  <AlertCircle size={12} className="text-amber-700" />
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

      <section className="bg-white rounded-2xl border border-stone-200 shadow-sm p-5">
        <p className="text-[10px] font-bold uppercase tracking-widest text-gray-500">
          Primary signature (from the main agreement step)
        </p>
        {form.signature ? (
          <div className="mt-3 inline-block rounded-md border border-dashed border-stone-300 bg-stone-50 p-2">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={form.signature}
              alt="Captured primary signature"
              style={{ maxHeight: 70, maxWidth: 260 }}
            />
          </div>
        ) : (
          <p className="mt-2 text-xs text-amber-700 inline-flex items-center gap-1">
            <AlertCircle size={12} /> Not yet captured — go back to the
            main agreement step.
          </p>
        )}
        {form.signedLegalName && (
          <p className="mt-2 text-xs text-gray-600">
            Signed legal name:{" "}
            <span className="font-semibold text-gray-900">
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
        <div className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900 inline-flex items-start gap-2">
          <AlertCircle size={14} className="mt-0.5 shrink-0" />
          <span>
            {form.finalSignature
              ? "Some sections still need attention. Use the section pills above (or the Edit links) to jump to them."
              : "Draw your final signature above to execute the agreement, then submit."}
          </span>
        </div>
      )}
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
    <section className="bg-white rounded-2xl border border-sage-navy/30 shadow-sm p-5 space-y-4">
      <div>
        <p className="text-[10px] font-bold uppercase tracking-widest text-sage-copper">
          Execution signature
        </p>
        <h3 className="font-serif text-lg text-sage-navy mt-0.5">
          After reading this, I am formally signing this acknowledgement.
        </h3>
        <p className="text-sm text-gray-700 mt-1">
          Draw your final signature here. This is your fresh attestation
          that everything above matches what you intended and that you
          are executing the agreement.
        </p>
      </div>

      <div>
        <label className="block text-[11px] font-semibold uppercase tracking-wider text-gray-600 mb-1">
          Your full legal name <span className="text-red-500">*</span>
        </label>
        <input
          type="text"
          value={legalName}
          onChange={(e) => onLegalName(e.target.value)}
          className="w-full px-3 py-2 text-sm rounded-md border border-stone-300 bg-white focus:outline-none focus:ring-1 focus:ring-sage-navy focus:border-sage-navy"
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
              className="mt-2 text-[11px] font-semibold text-gray-500 hover:text-sage-navy"
            >
              Cancel re-draw
            </button>
          )}
        </div>
      ) : (
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-wider text-gray-600 mb-1.5">
            Captured execution signature
          </p>
          <div className="inline-block rounded-md border border-dashed border-stone-300 bg-stone-50 p-2">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={finalSignature!}
              alt="Captured execution signature"
              style={{ maxHeight: 70, maxWidth: 240 }}
            />
          </div>
          <button
            type="button"
            onClick={() => {
              setRedrawing(true);
              onFinalSignature(null);
            }}
            className="ml-3 text-[11px] font-semibold text-sage-navy hover:text-sage-navy-deep underline underline-offset-2"
          >
            Re-draw
          </button>
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
  return (
    <nav
      aria-label="Wizard navigation"
      className="fixed bottom-0 left-0 right-0 bg-white border-t border-stone-200 z-30"
    >
      <div className="max-w-5xl mx-auto px-4 sm:px-6 py-3 flex items-center justify-between gap-3">
        <button
          type="button"
          onClick={onBack}
          disabled={currentStep === 0 || submitting}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-stone-200 bg-white hover:bg-stone-50 text-gray-700 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <ArrowLeft size={12} /> Back
        </button>
        <div className="hidden sm:flex items-center gap-3 text-[11px] text-gray-500">
          <span className="font-mono">
            {currentStep + 1} / {total}
          </span>
          <SaveStatusBadge status={saveStatus} />
        </div>
        {isReviewStep ? (
          <button
            type="button"
            onClick={onSubmit}
            disabled={!canAdvance || submitting}
            className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-md text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 disabled:cursor-not-allowed shadow-sm"
          >
            {submitting ? (
              <Loader2 size={12} className="animate-spin" />
            ) : (
              <CheckCircle2 size={12} />
            )}
            Submit agreement
          </button>
        ) : (
          <button
            type="button"
            onClick={onNext}
            disabled={!canAdvance || submitting}
            className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-md text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 disabled:cursor-not-allowed shadow-sm"
          >
            Next <ArrowRight size={12} />
          </button>
        )}
      </div>
      <div className="sm:hidden px-4 pb-2 text-[10px] text-gray-500 flex items-center justify-between">
        <span className="font-mono">
          Step {currentStep + 1} / {total}
        </span>
        <SaveStatusBadge status={saveStatus} />
      </div>
    </nav>
  );
}

function SaveStatusBadge({ status }: { status: SaveStatus }) {
  if (status.kind === "saving") {
    return (
      <span className="inline-flex items-center gap-1 text-gray-500">
        <Loader2 size={11} className="animate-spin" /> Saving…
      </span>
    );
  }
  if (status.kind === "saved") {
    return (
      <span className="inline-flex items-center gap-1 text-emerald-700">
        <CheckCircle2 size={11} /> Saved
      </span>
    );
  }
  if (status.kind === "paused") {
    return (
      <span className="inline-flex items-center gap-1 text-amber-700">
        <PauseCircle size={11} /> Saving paused — will retry
      </span>
    );
  }
  if (status.kind === "error") {
    return (
      <span className="inline-flex items-center gap-1 text-red-700">
        <AlertCircle size={11} /> {status.message}
      </span>
    );
  }
  return null;
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
