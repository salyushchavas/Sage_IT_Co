"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import {
  AlertCircle,
  ArrowLeft,
  FilePlus2,
  Loader2,
  Save,
} from "lucide-react";

import AgreementErmShell from "@/components/agreement-erm/AgreementErmShell";
import {
  createConsultantApplication,
  getAgreementErmToken,
} from "@/lib/api";
import { WORK_AUTHORIZATION_OPTIONS } from "@/lib/agreement-sections";

interface FormState {
  // Build W — structured name (First required, Middle optional, Last
  // required). Composed into the full legal name server-side.
  firstName: string;
  middleName: string;
  lastName: string;
  consultantEmail: string;
  ratePeriod1: string;
  rateAmount1: string;
  ratePeriod2: string;
  rateAmount2: string;
  // F-4: ERM-set visa status, persisted on the workAuthCategory column.
  // Locked + visible to the consultant on the cover step.
  visaStatus: string;
  // Build W — custom value, required only when visaStatus === "Others".
  visaStatusOther: string;
  // F-4: per-appendix requirement flags. The ERM ticks whichever
  // appendices apply to THIS consultant; the wizard + submit gate use
  // them to decide required vs optional-skippable.
  requireAppendix1: boolean;
  requireAppendix2: boolean;
  requireAppendix3: boolean;
  requireAppendix4: boolean;
  requireAppendix5: boolean;
  // F-4: SSN-in-Appendix-3 toggle. The Require-SSN checkbox is only
  // enabled when Appendix 3 is also required (Required iff requireSsn
  // AND Appendix 3 active).
  requireSsn: boolean;
}

// Build W — the eight ERM-selectable work-authorization options, shared
// with the consultant read-only view via agreement-sections.
const VISA_OPTIONS = WORK_AUTHORIZATION_OPTIONS;

const EMPTY: FormState = {
  firstName: "",
  middleName: "",
  lastName: "",
  consultantEmail: "",
  ratePeriod1: "",
  rateAmount1: "",
  ratePeriod2: "",
  rateAmount2: "",
  visaStatus: "",
  visaStatusOther: "",
  requireAppendix1: false,
  requireAppendix2: false,
  requireAppendix3: false,
  requireAppendix4: false,
  requireAppendix5: false,
  requireSsn: false,
};

/**
 * Structured 6-field create form. Submits to
 * POST /api/agreement-erm/applications, which transitions the
 * application to SUBMITTED and fires the "complete your details"
 * email to the consultant.
 *
 * The detail-page edit panel still uses the shared ConsultantForm
 * component (different concern: editing existing rows via the
 * legacy update endpoint). This page is intentionally inline so
 * the create + edit flows can diverge without coupling.
 */
export default function NewConsultantApplicationPage() {
  const router = useRouter();
  const [checked, setChecked] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!getAgreementErmToken()) {
      router.replace("/agreements/login");
      return;
    }
    setChecked(true);
  }, [router]);

  const setText = <K extends keyof FormState>(key: K) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setForm((s) => ({ ...s, [key]: e.target.value }));

  const toggleFlag = <K extends keyof FormState>(key: K) => () =>
    setForm((s) => {
      const next = { ...s, [key]: !(s[key] as boolean) } as FormState;
      // Require-SSN is only meaningful when Appendix 3 is required;
      // clearing Appendix 3 must also clear Require-SSN so the form
      // can't ship in an impossible state.
      if (key === "requireAppendix3" && !next.requireAppendix3) {
        next.requireSsn = false;
      }
      return next;
    });

  const trimmedStrings = {
    firstName: form.firstName.trim(),
    middleName: form.middleName.trim(),
    lastName: form.lastName.trim(),
    consultantEmail: form.consultantEmail.trim(),
    ratePeriod1: form.ratePeriod1.trim(),
    rateAmount1: form.rateAmount1.trim(),
    ratePeriod2: form.ratePeriod2.trim(),
    rateAmount2: form.rateAmount2.trim(),
    visaStatus: form.visaStatus.trim(),
    visaStatusOther: form.visaStatusOther.trim(),
  };

  // Middle name is optional; everything else here is required.
  const requiredTextValues = [
    trimmedStrings.firstName,
    trimmedStrings.lastName,
    trimmedStrings.consultantEmail,
    trimmedStrings.ratePeriod1,
    trimmedStrings.rateAmount1,
    trimmedStrings.ratePeriod2,
    trimmedStrings.rateAmount2,
    trimmedStrings.visaStatus,
  ];
  // Build W — when "Others" is chosen, the custom value is required.
  const visaOtherOk =
    trimmedStrings.visaStatus !== "Others"
    || trimmedStrings.visaStatusOther.length > 0;
  const allRequiredFilled =
    requiredTextValues.every((v) => v.length > 0) && visaOtherOk;
  const emailLooksValid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedStrings.consultantEmail);
  const periodsMatch =
    trimmedStrings.ratePeriod1.length > 0 &&
    trimmedStrings.ratePeriod2.length > 0 &&
    trimmedStrings.ratePeriod1.toLowerCase() === trimmedStrings.ratePeriod2.toLowerCase();

  const canSubmit = allRequiredFilled && !isSubmitting;

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setError("");

    if (!allRequiredFilled) {
      setError("Every required field needs a value.");
      return;
    }
    if (!emailLooksValid) {
      setError("Consultant email doesn't look right.");
      return;
    }

    setIsSubmitting(true);
    try {
      const app = await createConsultantApplication({
        ...trimmedStrings,
        requireAppendix1: form.requireAppendix1,
        requireAppendix2: form.requireAppendix2,
        requireAppendix3: form.requireAppendix3,
        requireAppendix4: form.requireAppendix4,
        requireAppendix5: form.requireAppendix5,
        requireSsn: form.requireSsn,
      });
      router.replace(`/agreements/${app.applicationId}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't create application.");
      setIsSubmitting(false);
    }
    // Don't reset isSubmitting on success -- the router.replace will
    // unmount the page; resetting would briefly re-enable the button
    // and let the operator double-click.
  };

  if (!checked) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <AgreementErmShell
      title="New consultant agreement"
      subtitle="Create a draft and email the invite to the consultant."
      Icon={FilePlus2}
      toolbar={
        <Link
          href="/agreements"
          className="inline-flex items-center gap-1 text-xs font-semibold text-gray-500 hover:text-sage-navy"
        >
          <ArrowLeft size={12} /> Back
        </Link>
      }
    >
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 max-w-3xl">
        <form onSubmit={handleSubmit} className="space-y-6">
          {error && (
            <div
              role="alert"
              className="rounded-md border border-red-200 bg-red-50 px-3 py-2 inline-flex items-start gap-2 text-sm text-red-700 w-full"
            >
              <AlertCircle size={14} className="mt-0.5 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          <section className="space-y-3">
            <SectionHeader title="Consultant" />
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <Field label="First name" required>
                <input
                  type="text"
                  value={form.firstName}
                  onChange={setText("firstName")}
                  disabled={isSubmitting}
                  required
                  placeholder="Jane"
                  className={inputClass}
                />
              </Field>
              <Field label="Middle name">
                <input
                  type="text"
                  value={form.middleName}
                  onChange={setText("middleName")}
                  disabled={isSubmitting}
                  placeholder="(optional)"
                  className={inputClass}
                />
              </Field>
              <Field label="Last name" required>
                <input
                  type="text"
                  value={form.lastName}
                  onChange={setText("lastName")}
                  disabled={isSubmitting}
                  required
                  placeholder="Consultant"
                  className={inputClass}
                />
              </Field>
              <Field label="Primary email" required>
                <input
                  type="email"
                  value={form.consultantEmail}
                  onChange={setText("consultantEmail")}
                  disabled={isSubmitting}
                  required
                  autoComplete="off"
                  placeholder="consultant@example.com"
                  className={inputClass}
                />
              </Field>
              <Field
                label="Work authorization"
                required
                className="md:col-span-2"
              >
                <select
                  value={form.visaStatus}
                  onChange={setText("visaStatus")}
                  disabled={isSubmitting}
                  required
                  className={inputClass}
                >
                  <option value="" disabled>
                    Select status…
                  </option>
                  {VISA_OPTIONS.map((opt) => (
                    <option key={opt} value={opt}>
                      {opt}
                    </option>
                  ))}
                </select>
                {form.visaStatus === "Others" && (
                  <input
                    type="text"
                    value={form.visaStatusOther}
                    onChange={setText("visaStatusOther")}
                    disabled={isSubmitting}
                    required
                    placeholder="Specify the work-authorization status"
                    className={inputClass + " mt-2"}
                  />
                )}
                <p className="mt-1 text-[11px] text-gray-500">
                  Locked on the consultant&apos;s view. They&apos;ll see
                  it but cannot change it.
                </p>
              </Field>
            </div>
          </section>

          <section className="space-y-3">
            <SectionHeader title="Phase 2 rate schedule" />
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <Field label="Rate period 1" required>
                <input
                  type="text"
                  value={form.ratePeriod1}
                  onChange={setText("ratePeriod1")}
                  disabled={isSubmitting}
                  required
                  placeholder="Months 1-12"
                  className={inputClass}
                />
              </Field>
              <Field label="Amount 1" required>
                <input
                  type="text"
                  value={form.rateAmount1}
                  onChange={setText("rateAmount1")}
                  disabled={isSubmitting}
                  required
                  placeholder="$2,400"
                  className={inputClass}
                />
              </Field>
              <Field label="Rate period 2" required>
                <input
                  type="text"
                  value={form.ratePeriod2}
                  onChange={setText("ratePeriod2")}
                  disabled={isSubmitting}
                  required
                  placeholder="Months 13-18"
                  className={inputClass}
                />
              </Field>
              <Field label="Amount 2" required>
                <input
                  type="text"
                  value={form.rateAmount2}
                  onChange={setText("rateAmount2")}
                  disabled={isSubmitting}
                  required
                  placeholder="$1,920"
                  className={inputClass}
                />
              </Field>
            </div>
            {periodsMatch && (
              <p className="text-[11px] text-sage-copper-deep inline-flex items-center gap-1.5">
                <AlertCircle size={12} />
                Heads up: both rate periods read the same. That&apos;s allowed
                but usually a typo.
              </p>
            )}
          </section>

          <section className="space-y-3">
            <SectionHeader title="Sections required for this consultant" />
            <p className="text-[11px] text-gray-500">
              Tick the appendices THIS consultant must complete. Unchecked
              appendices are shown to them but skippable. Implementation
              partner is never required.
            </p>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
              <RequirementCheckbox
                label="Appendix 1 — Employment confirmation"
                checked={form.requireAppendix1}
                onToggle={toggleFlag("requireAppendix1")}
                disabled={isSubmitting}
              />
              <RequirementCheckbox
                label="Appendix 2 — ACH payment authorization"
                checked={form.requireAppendix2}
                onToggle={toggleFlag("requireAppendix2")}
                disabled={isSubmitting}
              />
              <RequirementCheckbox
                label="Appendix 3 — Background check"
                checked={form.requireAppendix3}
                onToggle={toggleFlag("requireAppendix3")}
                disabled={isSubmitting}
              />
              <RequirementCheckbox
                label="Appendix 4 — Portal access"
                checked={form.requireAppendix4}
                onToggle={toggleFlag("requireAppendix4")}
                disabled={isSubmitting}
              />
              <RequirementCheckbox
                label="Appendix 5 — Security check acknowledgment"
                checked={form.requireAppendix5}
                onToggle={toggleFlag("requireAppendix5")}
                disabled={isSubmitting}
              />
              <RequirementCheckbox
                label="Require SSN in Appendix 3"
                checked={form.requireSsn}
                onToggle={toggleFlag("requireSsn")}
                disabled={isSubmitting || !form.requireAppendix3}
                hint={
                  form.requireAppendix3
                    ? "Consultant must enter their full SSN."
                    : "Enable Appendix 3 first."
                }
              />
            </div>
          </section>

          <div className="flex flex-col-reverse sm:flex-row sm:items-center sm:justify-end gap-2 pt-2 border-t border-gray-100">
            <button
              type="button"
              onClick={() => router.push("/agreements")}
              disabled={isSubmitting}
              className="w-full sm:w-auto px-4 py-2 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={!canSubmit}
              className="w-full sm:w-auto inline-flex items-center justify-center gap-1.5 px-4 py-2.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 disabled:cursor-not-allowed cursor-pointer transition-colors"
            >
              {isSubmitting ? (
                <Loader2 size={12} className="animate-spin" />
              ) : (
                <Save size={12} />
              )}
              {isSubmitting ? "Creating…" : "Create + send invite"}
            </button>
          </div>
        </form>
      </div>
    </AgreementErmShell>
  );
}

const inputClass =
  "w-full px-3 py-2 text-sm rounded-md border border-gray-200 " +
  "focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy " +
  "disabled:bg-gray-50 disabled:text-gray-500";

function SectionHeader({ title }: { title: string }) {
  return (
    <h2 className="text-[11px] font-bold uppercase tracking-wider text-sage-navy">
      {title}
    </h2>
  );
}

function Field({
  label,
  required,
  className = "",
  children,
}: {
  label: string;
  required?: boolean;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div className={className}>
      <label className="block text-[11px] font-semibold text-gray-600 mb-0.5">
        {label}
        {required && <span className="text-red-500"> *</span>}
      </label>
      {children}
    </div>
  );
}

function RequirementCheckbox({
  label,
  checked,
  onToggle,
  disabled,
  hint,
}: {
  label: string;
  checked: boolean;
  onToggle: () => void;
  disabled?: boolean;
  hint?: string;
}) {
  return (
    <label
      className={
        "flex items-start gap-2 rounded-md border px-3 py-2 text-xs " +
        (disabled
          ? "border-gray-100 bg-gray-50 text-gray-400 cursor-not-allowed"
          : checked
            ? "border-sage-navy bg-sage-navy/5 text-sage-navy cursor-pointer"
            : "border-gray-200 bg-white text-gray-700 cursor-pointer hover:border-sage-navy/40")
      }
    >
      <input
        type="checkbox"
        checked={checked}
        onChange={onToggle}
        disabled={disabled}
        className="mt-0.5 h-3.5 w-3.5 accent-sage-navy"
      />
      <span className="flex-1 leading-snug">
        <span className="font-semibold">{label}</span>
        {hint && (
          <span className="block text-[10px] text-gray-500 font-normal mt-0.5">
            {hint}
          </span>
        )}
      </span>
    </label>
  );
}
