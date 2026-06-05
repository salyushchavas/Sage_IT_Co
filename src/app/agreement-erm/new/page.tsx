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

interface FormState {
  consultantName: string;
  consultantEmail: string;
  ratePeriod1: string;
  rateAmount1: string;
  ratePeriod2: string;
  rateAmount2: string;
}

const EMPTY: FormState = {
  consultantName: "",
  consultantEmail: "",
  ratePeriod1: "",
  rateAmount1: "",
  ratePeriod2: "",
  rateAmount2: "",
};

/**
 * Phase 4 -- structured 6-field create form (replaces the previous
 * JSON textarea on this page). Submits to the Phase 3 endpoint
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
      router.replace("/agreement-erm/login");
      return;
    }
    setChecked(true);
  }, [router]);

  const set = <K extends keyof FormState>(key: K) =>
    (e: React.ChangeEvent<HTMLInputElement>) =>
      setForm((s) => ({ ...s, [key]: e.target.value }));

  const trimmed: FormState = {
    consultantName: form.consultantName.trim(),
    consultantEmail: form.consultantEmail.trim(),
    ratePeriod1: form.ratePeriod1.trim(),
    rateAmount1: form.rateAmount1.trim(),
    ratePeriod2: form.ratePeriod2.trim(),
    rateAmount2: form.rateAmount2.trim(),
  };

  const allRequiredFilled = Object.values(trimmed).every((v) => v.length > 0);
  const emailLooksValid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed.consultantEmail);
  const periodsMatch =
    trimmed.ratePeriod1.length > 0 &&
    trimmed.ratePeriod2.length > 0 &&
    trimmed.ratePeriod1.toLowerCase() === trimmed.ratePeriod2.toLowerCase();

  const canSubmit = allRequiredFilled && !isSubmitting;

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setError("");

    if (!allRequiredFilled) {
      setError("Every field is required.");
      return;
    }
    if (!emailLooksValid) {
      setError("Consultant email doesn't look right.");
      return;
    }

    setIsSubmitting(true);
    try {
      const app = await createConsultantApplication(trimmed);
      router.replace(`/agreement-erm/${app.applicationId}`);
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
          href="/agreement-erm"
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
              <Field label="Full legal name" required>
                <input
                  type="text"
                  value={form.consultantName}
                  onChange={set("consultantName")}
                  disabled={isSubmitting}
                  required
                  placeholder="Jane Q. Consultant"
                  className={inputClass}
                />
              </Field>
              <Field label="Primary email" required>
                <input
                  type="email"
                  value={form.consultantEmail}
                  onChange={set("consultantEmail")}
                  disabled={isSubmitting}
                  required
                  autoComplete="off"
                  placeholder="consultant@example.com"
                  className={inputClass}
                />
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
                  onChange={set("ratePeriod1")}
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
                  onChange={set("rateAmount1")}
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
                  onChange={set("ratePeriod2")}
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
                  onChange={set("rateAmount2")}
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

          <div className="flex flex-col-reverse sm:flex-row sm:items-center sm:justify-end gap-2 pt-2 border-t border-gray-100">
            <button
              type="button"
              onClick={() => router.push("/agreement-erm")}
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
