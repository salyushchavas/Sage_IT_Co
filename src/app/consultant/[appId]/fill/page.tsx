"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
} from "react";
import Image from "next/image";
import { useParams, useRouter } from "next/navigation";
import {
  AlertCircle,
  Check,
  CheckCircle2,
  ChevronRight,
  Eye,
  EyeOff,
  Loader2,
  Lock,
  PauseCircle,
  ShieldAlert,
} from "lucide-react";

import {
  getConsultantApplicationView,
  saveConsultantFill,
  type ConsultantApplication,
  type ConsultantFillPayload,
} from "@/lib/api";

// ── Field metadata ──────────────────────────────────────────────

type FillKey = keyof FillState;

type FieldType =
  | "text"
  | "tel"
  | "email"
  | "date"
  | "textarea"
  | "select"
  | "password";

interface FieldDef {
  key: FillKey;
  label: string;
  type: FieldType;
  options?: readonly string[];
  placeholder?: string;
  rows?: number;
  optional?: boolean;
  /** Returns error message or null. Runs on blur + on the final
   *  Continue-click validation pass. Per-field; section-level
   *  required gating is handled by the progress / continue logic. */
  validate?: (value: string) => string | null;
  /** Wider field that spans both columns on md+. */
  wide?: boolean;
}

type SectionId =
  | "personal"
  | "service"
  | "employment"
  | "ach"
  | "bg"
  | "portal"
  | "security";

type ToggleId = Exclude<SectionId, "personal" | "service" | "employment">;

interface SectionDef {
  id: SectionId;
  title: string;
  subtitle?: string;
  /** True when this section is one of the four toggleable appendices. */
  optional?: boolean;
  /** Sensitive-PII banner shown when expanded. */
  warning?: string;
  fields: FieldDef[];
}

const WORK_AUTH_OPTIONS = [
  "F-1 OPT",
  "F-1 STEM OPT",
  "H-1B",
  "H-4 EAD",
  "L-2 EAD",
  "GC",
  "USC",
  "Other",
] as const;

const PAYROLL_CYCLE_OPTIONS = [
  "Weekly",
  "Biweekly",
  "Monthly",
  "Other",
] as const;

const ACH_ACCOUNT_TYPE_OPTIONS = ["Checking", "Savings"] as const;

const phoneRegex = /^\+?[0-9 ()\-.]{7,}$/;
const required = (msg: string) => (v: string) =>
  v.trim().length === 0 ? msg : null;
const minLen = (n: number, msg: string) => (v: string) =>
  v.trim().length < n ? msg : null;
const mustPattern = (re: RegExp, msg: string) => (v: string) =>
  v.trim().length > 0 && !re.test(v.trim()) ? msg : null;
const compose =
  (...checks: Array<(v: string) => string | null>) =>
  (v: string) => {
    for (const c of checks) {
      const e = c(v);
      if (e) return e;
    }
    return null;
  };

const SECTIONS: readonly SectionDef[] = [
  {
    id: "personal",
    title: "Personal information",
    subtitle: "Tell us where you live, how to reach you, and your work-auth basis.",
    fields: [
      {
        key: "effectiveDate",
        label: "Effective date",
        type: "date",
        validate: required("Required"),
      },
      {
        key: "primaryPhone",
        label: "Primary phone",
        type: "tel",
        placeholder: "+1 555 123 4567",
        validate: compose(
          required("Required"),
          mustPattern(phoneRegex, "Doesn't look like a phone number."),
        ),
      },
      {
        key: "workAuthorizationCategory",
        label: "Work authorization category",
        type: "select",
        options: WORK_AUTH_OPTIONS,
        validate: required("Required"),
      },
      {
        key: "residenceAddress",
        label: "Residence address",
        type: "textarea",
        rows: 3,
        placeholder: "Street, city, state, ZIP",
        validate: compose(
          required("Required"),
          minLen(10, "Add street + city + state."),
        ),
        wide: true,
      },
    ],
  },
  {
    id: "service",
    title: "Service track",
    subtitle: "Which technology or skill track applies to this engagement?",
    fields: [
      {
        key: "technologyTrack",
        label: "Technology / skill track",
        type: "text",
        placeholder: "e.g. Salesforce, Snowflake, React, Data Engineering",
        validate: compose(required("Required"), minLen(2, "Too short.")),
      },
      {
        key: "customScopeNotes",
        label: "Custom scope / notes",
        type: "textarea",
        rows: 2,
        placeholder: "Anything specific about the engagement scope (optional)",
        optional: true,
        wide: true,
      },
    ],
  },
  {
    id: "employment",
    title: "Phase 2 employment",
    subtitle: "Where you'll be placed and how payroll runs.",
    fields: [
      {
        key: "employerPayrollEntity",
        label: "Employer / payroll entity",
        type: "text",
        validate: required("Required"),
      },
      {
        key: "implementationPartner",
        label: "Implementation partner",
        type: "text",
        placeholder: "N/A if not applicable",
        validate: required("Required (use N/A if none)"),
      },
      {
        key: "endClient",
        label: "End client",
        type: "text",
        placeholder: "N/A if not applicable",
        validate: required("Required (use N/A if none)"),
      },
      {
        key: "roleTitle",
        label: "Role / position",
        type: "text",
        validate: required("Required"),
      },
      {
        key: "verifiedStartDate",
        label: "Verified start date",
        type: "date",
        validate: required("Required"),
      },
      {
        key: "payrollCycle",
        label: "Payroll cycle",
        type: "select",
        options: PAYROLL_CYCLE_OPTIONS,
        validate: required("Required"),
      },
    ],
  },
  {
    id: "ach",
    title: "ACH payment authorization",
    subtitle: "Authorize Sage IT Co to debit your account for fees.",
    optional: true,
    fields: [
      {
        key: "achAccountType",
        label: "Account type",
        type: "select",
        options: ACH_ACCOUNT_TYPE_OPTIONS,
      },
      { key: "achBankName", label: "Bank name", type: "text" },
      {
        key: "achAccountHolderName",
        label: "Account holder name",
        type: "text",
      },
      { key: "achRoutingNumber", label: "Routing number", type: "text" },
      { key: "achAccountNumber", label: "Account number", type: "text" },
      {
        key: "achNoticeEmail",
        label: "Notice email",
        type: "email",
        placeholder: "Where ACH notices should land",
      },
      {
        key: "achDebitDates",
        label: "Debit dates",
        type: "text",
        placeholder: "e.g. 1st & 15th of each month",
      },
      { key: "achDebitAmounts", label: "Debit amounts", type: "text" },
    ],
  },
  {
    id: "bg",
    title: "Background check",
    subtitle: "Authorization for the background check vendor.",
    optional: true,
    warning:
      "This section collects sensitive personal information (SSN, DOB, driver's license). Only fill it if your ERM has confirmed it's required for this engagement.",
    fields: [
      { key: "bgFullLegalName", label: "Full legal name", type: "text" },
      { key: "bgOtherNamesUsed", label: "Other names used", type: "text" },
      {
        key: "bgCurrentAddress",
        label: "Current address",
        type: "textarea",
        rows: 3,
        wide: true,
      },
      { key: "bgDateOfBirth", label: "Date of birth", type: "date" },
      {
        key: "bgFullSsn",
        label: "Full SSN",
        type: "password",
        placeholder: "XXX-XX-XXXX",
      },
      {
        key: "bgDriverLicense",
        label: "Driver's license",
        type: "password",
        placeholder: "State + number",
      },
    ],
  },
  {
    id: "portal",
    title: "Portal access",
    subtitle: "Grant the operator a limited window into a client portal.",
    optional: true,
    fields: [
      { key: "portalPlatform", label: "Platform", type: "text" },
      { key: "portalUsername", label: "Username", type: "text" },
      {
        key: "portalAuthorizedActions",
        label: "Authorized actions",
        type: "textarea",
        rows: 3,
        wide: true,
      },
      { key: "portalEffectiveDate", label: "Effective date", type: "date" },
      {
        key: "portalRevocationContact",
        label: "Revocation contact",
        type: "text",
      },
    ],
  },
  {
    id: "security",
    title: "Security check",
    subtitle: "Hold-on-file check details (refunded at engagement end).",
    optional: true,
    fields: [
      {
        key: "securityCheckCount",
        label: "Check count",
        type: "text",
        placeholder: "How many checks",
      },
      { key: "securityCheckNumbers", label: "Check numbers", type: "text" },
      { key: "securityCheckBank", label: "Bank", type: "text" },
      { key: "securityCheckHolderName", label: "Holder name", type: "text" },
      { key: "securityCheckAmount", label: "Amount", type: "text" },
      { key: "securityCheckDates", label: "Date(s)", type: "text" },
    ],
  },
];

const TOGGLE_FIELDS: Record<ToggleId, FillKey[]> = {
  ach: SECTIONS.find((s) => s.id === "ach")!.fields.map((f) => f.key),
  bg: SECTIONS.find((s) => s.id === "bg")!.fields.map((f) => f.key),
  portal: SECTIONS.find((s) => s.id === "portal")!.fields.map((f) => f.key),
  security: SECTIONS.find((s) => s.id === "security")!.fields.map((f) => f.key),
};

const REQUIRED_SECTION_IDS: SectionId[] = ["personal", "service", "employment"];

// ── Local form state ────────────────────────────────────────────

type FillState = {
  [K in keyof Required<ConsultantFillPayload>]: string;
};

const ALL_FILL_KEYS = SECTIONS.flatMap((s) => s.fields.map((f) => f.key));

function buildInitialState(app: ConsultantApplication | null): FillState {
  const state = {} as FillState;
  for (const key of ALL_FILL_KEYS) {
    const value = app?.[key as keyof ConsultantApplication] as
      | string
      | null
      | undefined;
    state[key] = value == null ? "" : String(value);
  }
  // Effective date: default to today if the ERM hasn't seeded it.
  if (!state.effectiveDate) {
    state.effectiveDate = new Date().toISOString().slice(0, 10);
  }
  return state;
}

function detectToggleState(app: ConsultantApplication | null): Set<ToggleId> {
  const enabled = new Set<ToggleId>();
  if (!app) return enabled;
  (Object.keys(TOGGLE_FIELDS) as ToggleId[]).forEach((toggle) => {
    const hasAny = TOGGLE_FIELDS[toggle].some((k) => {
      const v = app[k as keyof ConsultantApplication];
      return typeof v === "string" && v.length > 0;
    });
    if (hasAny) enabled.add(toggle);
  });
  return enabled;
}

// ── Page ─────────────────────────────────────────────────────────

type SaveStatus =
  | { kind: "idle" }
  | { kind: "saving" }
  | { kind: "saved"; at: number }
  | { kind: "paused" }
  | { kind: "error"; message: string };

export default function ConsultantFillPage() {
  const router = useRouter();
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";

  const [app, setApp] = useState<ConsultantApplication | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const [form, setForm] = useState<FillState>(() => buildInitialState(null));
  const [enabledToggles, setEnabledToggles] = useState<Set<ToggleId>>(
    () => new Set(),
  );
  const [touched, setTouched] = useState<Set<FillKey>>(() => new Set());
  const [revealed, setRevealed] = useState<Set<FillKey>>(() => new Set());

  const [saveStatus, setSaveStatus] = useState<SaveStatus>({ kind: "idle" });
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState("");

  /** Server-side snapshot of every field. Used to compute the delta
   *  payload on each debounced PUT so we only send what changed. */
  const lastSavedRef = useRef<FillState>(buildInitialState(null));
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const pauseTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Hydrate from server ──────────────────────────────────────────
  useEffect(() => {
    if (!appId) return;
    let cancelled = false;
    setLoading(true);
    getConsultantApplicationView(appId)
      .then((data) => {
        if (cancelled) return;
        // State-machine routing -- mirror the spec.
        if (data.status === "COMPLETED") {
          router.replace(`/consultant/${encodeURIComponent(appId)}/done`);
          return;
        }
        if (data.status === "VERIFIED" || data.status === "SIGNED") {
          router.replace(`/consultant/${encodeURIComponent(appId)}/sign`);
          return;
        }
        if (data.status === "CANCELLED" || data.status === "EXPIRED") {
          router.replace("/consultant");
          return;
        }
        const initial = buildInitialState(data);
        setApp(data);
        setForm(initial);
        lastSavedRef.current = { ...initial };
        setEnabledToggles(detectToggleState(data));
      })
      .catch((e) => {
        if (cancelled) return;
        const msg = e instanceof Error ? e.message : "Couldn't load this application.";
        if (/404|not found/i.test(msg)) {
          router.replace("/consultant");
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

  // Cleanup on unmount ────────────────────────────────────────────
  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
      if (pauseTimerRef.current) clearTimeout(pauseTimerRef.current);
      abortRef.current?.abort();
    };
  }, []);

  // Helpers ───────────────────────────────────────────────────────
  const computeDelta = useCallback(
    (current: FillState): ConsultantFillPayload => {
      const delta: ConsultantFillPayload = {};
      const snapshot = lastSavedRef.current;
      for (const key of ALL_FILL_KEYS) {
        if (current[key] !== snapshot[key]) {
          (delta as Record<string, string>)[key] = current[key];
        }
      }
      return delta;
    },
    [],
  );

  const fireSave = useCallback(
    async (current: FillState) => {
      if (!appId) return;
      const patch = computeDelta(current);
      if (Object.keys(patch).length === 0) {
        setSaveStatus({ kind: "saved", at: Date.now() });
        return;
      }
      // Cancel any in-flight save and start a new one.
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      setSaveStatus({ kind: "saving" });
      try {
        await saveConsultantFill(appId, patch, controller.signal);
        if (controller.signal.aborted) return;
        // Update snapshot with the values we just sent. Any further
        // user typing during the flight stays in `form` and will be
        // captured by the next debounce cycle.
        const next = { ...lastSavedRef.current };
        for (const k of Object.keys(patch) as FillKey[]) {
          next[k] = (patch as Record<string, string>)[k] ?? "";
        }
        lastSavedRef.current = next;
        setSaveStatus({ kind: "saved", at: Date.now() });
      } catch (e) {
        // AbortError manifests as a DOMException; the user kept typing,
        // a fresher save is already in flight -- don't surface anything.
        if (e instanceof DOMException && e.name === "AbortError") return;
        if (controller.signal.aborted) return;
        const msg = e instanceof Error ? e.message : "Couldn't save.";
        if (/too many|429/i.test(msg)) {
          setSaveStatus({ kind: "paused" });
          // Back off 30s before allowing another debounce cycle to fire.
          if (pauseTimerRef.current) clearTimeout(pauseTimerRef.current);
          pauseTimerRef.current = setTimeout(() => {
            setSaveStatus({ kind: "idle" });
          }, 30_000);
        } else {
          setSaveStatus({ kind: "error", message: msg });
        }
      }
    },
    [appId, computeDelta],
  );

  const scheduleSave = useCallback(
    (current: FillState) => {
      // Paused (429) -- don't reset debounce; the back-off timer will
      // flip status back to idle and a future keystroke will resume.
      if (saveStatus.kind === "paused") return;
      if (debounceRef.current) clearTimeout(debounceRef.current);
      debounceRef.current = setTimeout(() => {
        void fireSave(current);
      }, 1500);
    },
    [fireSave, saveStatus.kind],
  );

  // Field change handler ──────────────────────────────────────────
  const setField = useCallback(
    (key: FillKey, value: string) => {
      setForm((prev) => {
        if (prev[key] === value) return prev;
        const next = { ...prev, [key]: value };
        scheduleSave(next);
        return next;
      });
    },
    [scheduleSave],
  );

  const markTouched = useCallback((key: FillKey) => {
    setTouched((prev) => {
      if (prev.has(key)) return prev;
      const next = new Set(prev);
      next.add(key);
      return next;
    });
  }, []);

  // Toggle on/off for the four optional sections ─────────────────
  const setToggle = useCallback(
    (toggle: ToggleId, enabled: boolean) => {
      setEnabledToggles((prev) => {
        const next = new Set(prev);
        if (enabled) next.add(toggle);
        else next.delete(toggle);
        return next;
      });
      if (!enabled) {
        // Clearing -- send "" for every field so the backend wipes
        // any previously-stored value (rate-limit-safe via the debounce).
        setForm((prev) => {
          const cleared = { ...prev };
          for (const k of TOGGLE_FIELDS[toggle]) cleared[k] = "";
          scheduleSave(cleared);
          return cleared;
        });
      }
    },
    [scheduleSave],
  );

  // Validation surface ────────────────────────────────────────────
  const errors = useMemo(() => {
    const out: Partial<Record<FillKey, string>> = {};
    for (const section of SECTIONS) {
      const isOptional = section.optional ?? false;
      const sectionEnabled =
        !isOptional || enabledToggles.has(section.id as ToggleId);
      if (!sectionEnabled) continue;
      for (const field of section.fields) {
        if (!field.validate) continue;
        const message = field.validate(form[field.key]);
        if (message) out[field.key] = message;
      }
    }
    return out;
  }, [form, enabledToggles]);

  const requiredOk = useMemo(() => {
    for (const id of REQUIRED_SECTION_IDS) {
      const section = SECTIONS.find((s) => s.id === id);
      if (!section) continue;
      for (const field of section.fields) {
        if (field.optional) continue;
        if (errors[field.key]) return false;
        if (!form[field.key].trim()) return false;
      }
    }
    return true;
  }, [errors, form]);

  const sectionCompletion = useMemo(() => {
    return SECTIONS.map((section) => {
      const isOptional = section.optional ?? false;
      const enabled = !isOptional || enabledToggles.has(section.id as ToggleId);
      if (!enabled) return { id: section.id, complete: true };
      const allFilled = section.fields.every((f) => {
        if (f.optional) return true;
        return form[f.key].trim().length > 0 && !errors[f.key];
      });
      return { id: section.id, complete: allFilled };
    });
  }, [enabledToggles, form, errors]);

  const sectionsCompleteCount = sectionCompletion.filter((s) => s.complete).length;

  // Continue: final synchronous save then route to /sign ────────
  const handleContinue = useCallback(async () => {
    if (!appId || !requiredOk) return;
    setSubmitError("");
    setSubmitting(true);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    abortRef.current?.abort();
    try {
      const patch = computeDelta(form);
      if (Object.keys(patch).length > 0) {
        await saveConsultantFill(appId, patch);
      }
      router.push(`/consultant/${encodeURIComponent(appId)}/sign`);
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Couldn't save before continuing.";
      setSubmitError(msg);
      setSubmitting(false);
    }
  }, [appId, computeDelta, form, requiredOk, router]);

  // Render ────────────────────────────────────────────────────────

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
            {loadError || "We couldn't load this application."}
          </p>
        </div>
      </div>
    );
  }

  return (
    <main className="min-h-screen bg-stone-50 pb-32">
      <meta name="robots" content="noindex,nofollow" />

      {/* Header strip */}
      <header className="bg-sage-navy text-white">
        <div className="max-w-3xl mx-auto px-4 sm:px-6 py-10 sm:py-12">
          <div className="flex items-center gap-3">
            <Image
              src="/sage_logo.png"
              alt="Sage IT Co"
              width={32}
              height={32}
              priority
              className="rounded-md object-contain"
            />
            <span className="text-sm font-bold tracking-tight">Sage IT Co</span>
          </div>
          <h1 className="font-serif text-3xl sm:text-4xl mt-5">
            Complete your agreement
          </h1>
          <p className="text-sm sm:text-base text-white/80 mt-2 max-w-xl">
            Fill in the details below. Your progress saves automatically as
            you type.
          </p>
        </div>
      </header>

      <div className="max-w-3xl mx-auto px-4 sm:px-6 space-y-6 -mt-6">
        {/* Revision banner */}
        {app.status === "REVISION_REQUESTED" && (
          <div className="rounded-xl border-l-4 border-sage-copper-deep bg-orange-50/70 p-4">
            <p className="text-sm font-bold text-sage-copper-deep">
              Revision requested
            </p>
            <p className="mt-1 text-xs text-gray-500">
              Revision #{app.revisionCount ?? 1}
            </p>
            {app.currentRevisionRemarks && (
              <blockquote className="mt-2 text-sm text-gray-700 italic border-l-2 border-sage-copper-deep/40 pl-3">
                {app.currentRevisionRemarks}
              </blockquote>
            )}
          </div>
        )}

        {/* Read-only ERM-filled card */}
        <section className="bg-stone-100 rounded-xl border border-stone-200 p-4 sm:p-5">
          <h2 className="font-serif text-lg text-sage-navy">
            From your ERM
          </h2>
          <p className="text-[11px] text-gray-500 mt-0.5">
            Filled by your ERM. Contact them if anything needs correcting.
          </p>
          <dl className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-x-4 gap-y-3 text-sm">
            <ReadOnlyRow label="Consultant name" value={app.consultantName} />
            <ReadOnlyRow label="Email" value={app.consultantEmail} />
            <ReadOnlyRow label="Rate period 1" value={app.ratePeriod1} />
            <ReadOnlyRow label="Amount 1" value={app.rateAmount1} />
            <ReadOnlyRow label="Rate period 2" value={app.ratePeriod2} />
            <ReadOnlyRow label="Amount 2" value={app.rateAmount2} />
          </dl>
        </section>

        {/* Progress strip */}
        <section className="sticky top-0 z-20 bg-stone-50/95 backdrop-blur py-3 -mx-4 sm:-mx-6 px-4 sm:px-6 border-b border-stone-200">
          <div className="flex items-center justify-between gap-3 flex-wrap">
            <div className="flex items-center gap-2">
              <span className="text-xs font-bold text-sage-navy">
                {sectionsCompleteCount} / {SECTIONS.length} sections complete
              </span>
              <div className="flex items-center gap-1">
                {sectionCompletion.map((s) => (
                  <span
                    key={s.id}
                    aria-label={`${s.id} ${s.complete ? "complete" : "in progress"}`}
                    className={
                      "inline-block w-2.5 h-2.5 rounded-full transition " +
                      (s.complete
                        ? "bg-sage-navy"
                        : "border border-sage-navy/40 bg-transparent")
                    }
                  />
                ))}
              </div>
            </div>
            <SaveStatusBadge status={saveStatus} />
          </div>
        </section>

        {/* Form sections */}
        <form className="space-y-6" onSubmit={(e) => e.preventDefault()}>
          {SECTIONS.map((section) => (
            <SectionCard
              key={section.id}
              section={section}
              enabled={
                !section.optional ||
                enabledToggles.has(section.id as ToggleId)
              }
              onToggle={
                section.optional
                  ? (v) => setToggle(section.id as ToggleId, v)
                  : undefined
              }
              form={form}
              setField={setField}
              touched={touched}
              markTouched={markTouched}
              errors={errors}
              revealed={revealed}
              setRevealed={(k, v) =>
                setRevealed((prev) => {
                  const next = new Set(prev);
                  if (v) next.add(k);
                  else next.delete(k);
                  return next;
                })
              }
            />
          ))}
        </form>

        {submitError && (
          <p className="inline-flex items-center gap-1.5 text-sm text-red-600">
            <AlertCircle size={14} /> {submitError}
          </p>
        )}
      </div>

      {/* Sticky bottom bar */}
      <nav
        aria-label="Form actions"
        className="fixed bottom-0 left-0 right-0 bg-white border-t border-stone-200 z-30"
      >
        <div className="max-w-3xl mx-auto px-4 sm:px-6 py-3 flex items-center justify-between gap-3">
          <div className="text-xs text-gray-500">
            <span className="font-bold text-sage-navy">
              {sectionsCompleteCount} / {SECTIONS.length}
            </span>{" "}
            sections complete
          </div>
          <button
            type="button"
            onClick={handleContinue}
            disabled={!requiredOk || submitting || saveStatus.kind === "saving"}
            className={
              "inline-flex items-center gap-1.5 px-4 py-2.5 rounded-lg text-sm font-bold transition " +
              (requiredOk && !submitting && saveStatus.kind !== "saving"
                ? "bg-sage-navy text-white hover:bg-sage-navy-deep shadow-md hover:shadow-lg cursor-pointer"
                : "bg-gray-200 text-gray-500 cursor-not-allowed")
            }
          >
            {submitting ? (
              <Loader2 size={14} className="animate-spin" />
            ) : (
              <ChevronRight size={14} />
            )}
            {submitting ? "Saving…" : "Continue to sign"}
          </button>
        </div>
      </nav>
    </main>
  );
}

// ── Sub-components ──────────────────────────────────────────────

function ReadOnlyRow({
  label,
  value,
}: {
  label: string;
  value: string | null | undefined;
}) {
  return (
    <div>
      <dt className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">
        {label}
      </dt>
      <dd className="text-sm text-gray-800 font-medium mt-0.5">
        {value && value.length > 0 ? value : <span className="text-gray-400">—</span>}
      </dd>
    </div>
  );
}

function SaveStatusBadge({ status }: { status: SaveStatus }) {
  if (status.kind === "idle") return null;
  if (status.kind === "saving") {
    return (
      <span className="inline-flex items-center gap-1.5 text-[11px] font-semibold text-gray-500">
        <Loader2 size={11} className="animate-spin" /> Saving…
      </span>
    );
  }
  if (status.kind === "saved") {
    const seconds = Math.floor((Date.now() - status.at) / 1000);
    return (
      <span className="inline-flex items-center gap-1.5 text-[11px] font-semibold text-emerald-700">
        <CheckCircle2 size={11} />
        Saved{seconds > 0 ? ` · ${seconds}s ago` : ""}
      </span>
    );
  }
  if (status.kind === "paused") {
    return (
      <span className="inline-flex items-center gap-1.5 text-[11px] font-semibold text-sage-copper-deep">
        <PauseCircle size={11} /> Saving paused — retrying soon
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1.5 text-[11px] font-semibold text-red-600">
      <AlertCircle size={11} /> {status.message || "Save failed"}
    </span>
  );
}

function SectionCard({
  section,
  enabled,
  onToggle,
  form,
  setField,
  touched,
  markTouched,
  errors,
  revealed,
  setRevealed,
}: {
  section: SectionDef;
  enabled: boolean;
  onToggle?: (v: boolean) => void;
  form: FillState;
  setField: (key: FillKey, value: string) => void;
  touched: Set<FillKey>;
  markTouched: (key: FillKey) => void;
  errors: Partial<Record<FillKey, string>>;
  revealed: Set<FillKey>;
  setRevealed: (key: FillKey, v: boolean) => void;
}) {
  const collapsed = section.optional && !enabled;

  return (
    <section className="bg-white rounded-2xl border border-stone-200 shadow-sm overflow-hidden">
      <header className="px-5 sm:px-6 pt-5 pb-4 border-b border-stone-100">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="font-serif text-lg text-gray-900">
              {section.title}{" "}
              {section.optional && (
                <span className="text-[11px] font-sans uppercase tracking-wider text-sage-copper-deep ml-1 align-middle">
                  Optional
                </span>
              )}
            </h2>
            {section.subtitle && (
              <p className="text-xs text-gray-500 mt-0.5">{section.subtitle}</p>
            )}
          </div>
          {section.optional && onToggle && (
            <label className="inline-flex items-center gap-2 text-xs font-semibold text-gray-700 cursor-pointer shrink-0">
              <input
                type="checkbox"
                checked={enabled}
                onChange={(e) => onToggle(e.target.checked)}
                className="w-4 h-4 rounded border-gray-300 text-sage-navy focus:ring-sage-copper cursor-pointer"
              />
              Include this section
            </label>
          )}
        </div>
        {section.warning && enabled && (
          <div className="mt-3 rounded-md bg-orange-50 border-l-2 border-sage-copper-deep px-3 py-2 inline-flex items-start gap-2 text-[11px] text-sage-copper-deep">
            <ShieldAlert size={12} className="mt-0.5 shrink-0" />
            <span>{section.warning}</span>
          </div>
        )}
      </header>

      {!collapsed && (
        <div className="px-5 sm:px-6 py-5">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-x-4 gap-y-3">
            {section.fields.map((field) => (
              <FieldRow
                key={field.key}
                field={field}
                value={form[field.key]}
                onChange={(v) => setField(field.key, v)}
                onBlur={() => markTouched(field.key)}
                error={
                  touched.has(field.key) ? errors[field.key] ?? null : null
                }
                revealed={revealed.has(field.key)}
                onToggleReveal={(v) => setRevealed(field.key, v)}
              />
            ))}
          </div>
        </div>
      )}
    </section>
  );
}

function FieldRow({
  field,
  value,
  onChange,
  onBlur,
  error,
  revealed,
  onToggleReveal,
}: {
  field: FieldDef;
  value: string;
  onChange: (v: string) => void;
  onBlur: () => void;
  error: string | null;
  revealed: boolean;
  onToggleReveal: (v: boolean) => void;
}) {
  const spanClass = field.wide ? "md:col-span-2" : "";
  const inputBase =
    "w-full px-3 py-2 text-sm rounded-md border focus:outline-none focus:ring-1 " +
    (error
      ? "border-red-300 focus:border-red-400 focus:ring-red-200"
      : "border-stone-200 focus:border-sage-navy focus:ring-sage-navy");

  const labelEl = (
    <label className="block text-[11px] font-semibold text-gray-600 mb-1 inline-flex items-center gap-1">
      {field.label}
      {field.optional && (
        <span className="text-[10px] text-gray-400 font-normal">
          (optional)
        </span>
      )}
      {field.type === "password" && <Lock size={10} className="text-gray-400" />}
    </label>
  );

  const onTextChange = (
    e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
  ) => onChange(e.target.value);

  let control: React.ReactNode;
  if (field.type === "textarea") {
    control = (
      <textarea
        value={value}
        onChange={onTextChange}
        onBlur={onBlur}
        rows={field.rows ?? 3}
        placeholder={field.placeholder}
        className={inputBase}
      />
    );
  } else if (field.type === "select") {
    control = (
      <select
        value={value}
        onChange={onTextChange}
        onBlur={onBlur}
        className={inputBase + " bg-white"}
      >
        <option value="">— Select —</option>
        {field.options?.map((opt) => (
          <option key={opt} value={opt}>
            {opt}
          </option>
        ))}
      </select>
    );
  } else if (field.type === "password") {
    control = (
      <div className="relative">
        <input
          type={revealed ? "text" : "password"}
          value={value}
          onChange={onTextChange}
          onBlur={onBlur}
          placeholder={field.placeholder}
          autoComplete="off"
          className={inputBase + " pr-10 font-mono"}
        />
        <button
          type="button"
          onClick={() => onToggleReveal(!revealed)}
          aria-label={revealed ? "Hide value" : "Show value"}
          className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-sage-navy cursor-pointer"
        >
          {revealed ? <EyeOff size={14} /> : <Eye size={14} />}
        </button>
      </div>
    );
  } else {
    control = (
      <input
        type={field.type}
        value={value}
        onChange={onTextChange}
        onBlur={onBlur}
        placeholder={field.placeholder}
        autoComplete="off"
        className={inputBase}
      />
    );
  }

  return (
    <div className={spanClass}>
      {labelEl}
      {control}
      {error ? (
        <p className="mt-1 text-[11px] text-red-600 inline-flex items-center gap-1">
          <AlertCircle size={10} /> {error}
        </p>
      ) : field.optional && value.length === 0 ? (
        <p className="mt-1 text-[11px] text-gray-400 inline-flex items-center gap-1">
          <Check size={10} /> Optional — leave blank to skip
        </p>
      ) : null}
    </div>
  );
}
