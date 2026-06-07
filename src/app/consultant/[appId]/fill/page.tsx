"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
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
import {
  AGREEMENT_SECTIONS,
  AFFIRMATION_FLAGS,
  type AffirmationFlag,
  type AgreementSection,
  type SectionField,
} from "@/lib/agreement-sections";
import {
  fetchAgreementTemplatePdfBlob,
  getConsultantApplicationView,
  getConsultantToken,
  saveConsultantFill,
  signConsultantApplication,
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
  signature: string | null;
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
  return true;
}

function isSectionComplete(
  section: AgreementSection,
  state: FormState,
): boolean {
  for (const field of section.fields) {
    if (field.readOnly) continue;
    if (!isFieldValueValid(field, state.fields[field.key] ?? "")) {
      return false;
    }
  }
  if (section.requiresSignature && !state.signature) return false;
  if (section.requiresAffirmation && section.affirmationFlag) {
    if (!state.affirmations[section.affirmationFlag]) return false;
  }
  return true;
}

function firstIncompleteIndex(state: FormState): number {
  for (let i = 0; i < AGREEMENT_SECTIONS.length - 1; i++) {
    if (!isSectionComplete(AGREEMENT_SECTIONS[i], state)) return i;
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
    getConsultantApplicationView(appId)
      .then((data) => {
        if (cancelled) return;
        if (data.status === "COMPLETED") {
          router.replace("/consultant/dashboard");
          return;
        }
        if (data.status === "VERIFIED" || data.status === "SIGNED") {
          router.replace("/consultant/dashboard");
          return;
        }
        if (data.status === "CANCELLED" || data.status === "EXPIRED") {
          router.replace("/consultant/dashboard");
          return;
        }
        const initial = buildInitialState(data);
        setApp(data);
        setForm(initial);
        lastSavedRef.current = { ...initial };
        // Resume at the first incomplete section (or step 0 for a
        // brand-new application). REVISION_REQUESTED also resumes
        // from whichever section still has gaps after the ERM kick.
        setCurrentStep(firstIncompleteIndex(initial));
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

  const setLegalName = useCallback(
    (value: string) => {
      setForm((prev) => {
        if (prev.signedLegalName === value) return prev;
        return { ...prev, signedLegalName: value };
      });
    },
    [],
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
        throw new Error("Your signature is required before you can submit.");
      }
      await signConsultantApplication(
        appId,
        form.signedLegalName.trim(),
        form.signature,
      );
      router.push("/consultant/dashboard");
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
          };
          message?: string;
        };
        if (obj?.data) {
          msg = obj.message || "Some items are still missing.";
          const incompleteIdx = firstIncompleteIndex(form);
          setCurrentStep(incompleteIdx);
          routed = true;
        }
      } catch {
        /* not JSON; fall through with the original message */
      }
      if (!routed) {
        // Best-effort: also try to find which section is incomplete
        // from local state so the consultant lands somewhere sensible.
        setCurrentStep(firstIncompleteIndex(form));
      }
      setSubmitError(msg);
      setSubmitting(false);
    }
  }, [appId, computeDelta, form, router]);

  // Step accessors ──────────────────────────────────────────────
  const section = AGREEMENT_SECTIONS[currentStep];
  const sectionStatus = useMemo(
    () =>
      AGREEMENT_SECTIONS.map((s, i) => {
        const complete =
          i === AGREEMENT_SECTIONS.length - 1
            ? AGREEMENT_SECTIONS.slice(0, -1).every((sec) =>
                isSectionComplete(sec, form),
              )
            : isSectionComplete(s, form);
        return { id: s.id, title: s.title, step: s.step, complete };
      }),
    [form],
  );
  const canAdvance = isSectionComplete(section, form);
  const isReviewStep = currentStep === AGREEMENT_SECTIONS.length - 1;
  const allComplete = useMemo(
    () =>
      AGREEMENT_SECTIONS.slice(0, -1).every((s) => isSectionComplete(s, form)),
    [form],
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

  return (
    <main className="min-h-screen bg-stone-50 pb-40">
      <meta name="robots" content="noindex,nofollow" />

      <header className="bg-sage-navy text-white">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 py-8 sm:py-10">
          <div className="flex items-center justify-between gap-3 flex-wrap">
            <div>
              <p className="text-[10px] sm:text-xs font-bold uppercase tracking-widest text-sage-copper">
                Sage IT Consultant Portal
              </p>
              <h1 className="font-serif text-2xl sm:text-3xl mt-1">
                Complete your agreement
              </h1>
              <p className="text-xs sm:text-sm text-white/80 mt-1.5 max-w-xl">
                Read each section, fill the details, check the
                affirmation, and continue. We auto-save as you go.
              </p>
            </div>
            <button
              type="button"
              onClick={() => setTemplateOpen(true)}
              className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-semibold rounded-md border border-white/30 hover:bg-white/10 transition-colors shrink-0"
            >
              <FileText size={12} /> View full agreement
            </button>
          </div>
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

      <section className="max-w-5xl mx-auto px-4 sm:px-6 pt-6">
        {isReviewStep ? (
          <ReviewStep
            form={form}
            onJumpToSection={(idx) => setCurrentStep(idx)}
            allComplete={allComplete}
          />
        ) : (
          <SectionStep
            section={section}
            form={form}
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
            effectiveDateText={
              app.effectiveDate
                ? new Date(app.effectiveDate).toLocaleDateString()
                : "Will be set by Sage IT"
            }
            onOpenTemplate={() => setTemplateOpen(true)}
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
  form,
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
}: {
  section: AgreementSection;
  form: FormState;
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
}) {
  const isCoverStep = section.id === "cover";
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
              <span className="font-semibold">Agreement effective date:</span>{" "}
              {effectiveDateText} (set by Sage IT — not editable).
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
  value,
  onChange,
  onBlur,
  touched,
  revealed,
  onRevealToggle,
}: {
  field: SectionField;
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

  const invalid = touched && !field.readOnly && !isFieldValueValid(field, value);
  const errorMsg =
    invalid && field.type === "email"
      ? "Enter a valid email address."
      : invalid
        ? "This field is required."
        : "";

  const baseInputClasses =
    "w-full px-3 py-2 text-sm rounded-md border bg-white focus:outline-none focus:ring-1 focus:ring-sage-navy focus:border-sage-navy " +
    (invalid
      ? "border-red-300 bg-red-50/40"
      : field.readOnly
        ? "border-stone-200 bg-stone-100 cursor-not-allowed"
        : "border-stone-300");

  let control: ReactNode;
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
    control = (
      <div className="relative">
        <input
          type={inputType}
          inputMode={
            field.type === "routing" || field.type === "account"
              ? "numeric"
              : undefined
          }
          autoComplete="off"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onBlur={onBlur}
          placeholder={field.placeholder}
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
        {!field.readOnly && (
          <span className="text-red-500 ml-1">*</span>
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
  form,
  onJumpToSection,
  allComplete,
}: {
  form: FormState;
  onJumpToSection: (idx: number) => void;
  allComplete: boolean;
}) {
  return (
    <div className="space-y-5">
      <div className="bg-white rounded-2xl border border-stone-200 shadow-sm p-5">
        <p className="text-[10px] font-bold uppercase tracking-widest text-sage-copper">
          Final step
        </p>
        <h2 className="font-serif text-2xl text-sage-navy mt-1">
          Review and submit
        </h2>
        <p className="text-sm text-gray-700 mt-2">
          Confirm everything below matches what you intended. Click any
          section to edit it. When you submit, the agreement moves to
          Sage IT for review.
        </p>
      </div>

      {AGREEMENT_SECTIONS.slice(0, -1).map((section, idx) => {
        const complete = isSectionComplete(section, form);
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
                    (complete
                      ? "bg-emerald-100 text-emerald-800"
                      : "bg-amber-100 text-amber-800")
                  }
                >
                  {complete ? <CheckCircle2 size={11} /> : <AlertCircle size={11} />}
                  {complete ? "Complete" : "Needs attention"}
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
                  const raw = form.fields[field.key] ?? "";
                  const masked =
                    field.sensitive && raw.length > 0
                      ? maskValue(raw)
                      : raw || "—";
                  return (
                    <div key={field.key}>
                      <dt className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">
                        {field.label}
                      </dt>
                      <dd className="text-sm text-gray-900 break-words">
                        {masked}
                      </dd>
                    </div>
                  );
                })}
              </dl>
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
          Signature
        </p>
        <h3 className="font-serif text-lg text-sage-navy mt-0.5">
          Your signature
        </h3>
        {form.signature ? (
          <div className="mt-3 inline-block rounded-md border border-dashed border-stone-300 bg-stone-50 p-2">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={form.signature}
              alt="Captured signature"
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

      {!allComplete && (
        <div className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900 inline-flex items-start gap-2">
          <AlertCircle size={14} className="mt-0.5 shrink-0" />
          <span>
            Some sections still need attention. Use the section pills
            above (or the Edit links) to jump to them.
          </span>
        </div>
      )}
    </div>
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
