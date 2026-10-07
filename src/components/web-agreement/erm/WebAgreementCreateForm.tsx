"use client";

import { useEffect, useState, type FormEvent } from "react";
import {
  AlertCircle,
  ArrowLeft,
  Loader2,
  Save,
} from "lucide-react";

import {
  createWebAgreement,
  getWebAgreementRequest,
} from "@/lib/api";
import { WORK_AUTHORIZATION_OPTIONS } from "@/lib/web-agreement-sections";

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
  // Appendix 1 Schedule 1 — ERM-set Phase 2 deliverables period (merged cell).
  phase2DeliverablePeriod: string;
  // F-4: ERM-set visa status, persisted on the workAuthCategory column.
  // Locked + visible to the participant on the cover step.
  visaStatus: string;
  // Build W — custom value, required only when visaStatus === "Others".
  visaStatusOther: string;
  // F-4: per-appendix requirement flags. The ERM ticks whichever
  // appendices apply to THIS participant; the wizard + submit gate use
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
  // Build Y — ERM-filled ACH debit schedule. Single free-text fields
  // (e.g. "15th of every month" / "$416.67"); read-only to the participant.
  achDebitDates: string;
  achDebitAmounts: string;
  // Build I — Service Track (ERM-set, locked). Technology/Skill Track is
  // required to send; Custom Scope is optional. Read-only to the participant.
  technologyTrack: string;
  customScopeNotes: string;
  // Build Z — ERM-set Appendix 4 fields (read-only to the participant).
  // Authorized Actions carries a default bracketed string (editable);
  // Revocation Contact is free-text with no default.
  portalAuthorizedActions: string;
  portalRevocationContact: string;
}

// Build Z — default Appendix 4 "Authorized actions" (ERM-set, editable).
const DEFAULT_PORTAL_AUTHORIZED_ACTIONS =
  "[Review / update profile / submit applications / respond to recruiters / schedule interviews / other limited actions]";

// Build W — the eight ERM-selectable work-authorization options, shared
// with the participant's read-only view via web-agreement-sections.
const VISA_OPTIONS = WORK_AUTHORIZATION_OPTIONS;

/** A dollar amount without the "$": "2,400", "2400", "2,400.50". */
const MONEY_RE = /^\d{1,3}(,\d{3})*(\.\d{1,2})?$|^\d+(\.\d{1,2})?$/;

/**
 * A positive dollar amount, with or without the "$" ("$2,400", "2400.50").
 * The server applies the same rule to the rate amounts.
 */
export function isPositiveMoney(value: string): boolean {
  const digits = value.replace(/\$/g, "").trim();
  return MONEY_RE.test(digits) && Number(digits.replace(/,/g, "")) > 0;
}

const AMOUNT_RULE = "Enter a dollar amount greater than zero, like 2,400 or 2400.50.";

const EMPTY: FormState = {
  firstName: "",
  middleName: "",
  lastName: "",
  consultantEmail: "",
  ratePeriod1: "",
  rateAmount1: "",
  ratePeriod2: "",
  rateAmount2: "",
  phase2DeliverablePeriod: "",
  visaStatus: "",
  visaStatusOther: "",
  requireAppendix1: false,
  requireAppendix2: false,
  requireAppendix3: false,
  requireAppendix4: false,
  requireAppendix5: false,
  requireSsn: false,
  achDebitDates: "",
  achDebitAmounts: "",
  technologyTrack: "",
  customScopeNotes: "",
  portalAuthorizedActions: DEFAULT_PORTAL_AUTHORIZED_ACTIONS,
  portalRevocationContact: "",
};

/**
 * The website agreement's copy of the console's create form
 * (src/app/agreements/new/page.tsx), as a component inside the ERM
 * dashboard's Agreements tab. It always starts from a participant on the
 * "ready for their agreement" list: their own details (name, email,
 * technology) are filled in from their request; everything else is the
 * ERM's side.
 *
 * Submits to POST /api/web-agreements, which creates the agreement as
 * SUBMITTED; the participant fills it on their dashboard (no email).
 * onCreated then opens the new agreement.
 */
export default function WebAgreementCreateForm({
  participantUserId,
  onCancel,
  onCreated,
}: {
  /** users.id of the participant (from the ready list). */
  participantUserId: number;
  onCancel: () => void;
  onCreated: (applicationId: string) => void;
}) {
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState<FormState>(EMPTY);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState("");
  // The participant's request couldn't be read (no longer waiting, or they
  // already have an agreement): there is nothing to create.
  const [loadError, setLoadError] = useState("");
  // Whose request the participant details were filled in from.
  const [prefilledFrom, setPrefilledFrom] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    // Only the participant's own details; everything on the ERM's side stays for the ERM.
    getWebAgreementRequest(participantUserId)
      .then((p) => {
        if (cancelled) return;
        setForm((s) => ({
          ...s,
          firstName: p.firstName || s.firstName,
          middleName: p.middleName || s.middleName,
          lastName: p.lastName || s.lastName,
          consultantEmail: p.email || s.consultantEmail,
          technologyTrack: p.technology || s.technologyTrack,
        }));
        setPrefilledFrom(p.participantId || p.fullName || p.email);
      })
      .catch((e) => {
        // Not waiting any more (or already has an agreement): the server
        // refuses the create too, so say why up front.
        if (!cancelled) {
          setLoadError(e instanceof Error ? e.message : "Couldn't load this participant's request.");
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [participantUserId]);

  const setText = <K extends keyof FormState>(key: K) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
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
    // The "$" is a fixed prefix (rendered as an adornment + stripped from
    // input), so prepend it back onto the stored/stamped value.
    rateAmount1: form.rateAmount1.trim() ? "$" + form.rateAmount1.trim() : "",
    ratePeriod2: form.ratePeriod2.trim(),
    rateAmount2: form.rateAmount2.trim() ? "$" + form.rateAmount2.trim() : "",
    phase2DeliverablePeriod: form.phase2DeliverablePeriod.trim(),
    visaStatus: form.visaStatus.trim(),
    visaStatusOther: form.visaStatusOther.trim(),
    // Build I — Service Track (ERM-set). Track required, Scope optional.
    technologyTrack: form.technologyTrack.trim(),
    customScopeNotes: form.customScopeNotes.trim(),
  };

  // Middle name + custom scope are optional; everything else is required.
  const requiredTextValues = [
    trimmedStrings.firstName,
    trimmedStrings.lastName,
    trimmedStrings.consultantEmail,
    trimmedStrings.ratePeriod1,
    trimmedStrings.rateAmount1,
    trimmedStrings.ratePeriod2,
    trimmedStrings.rateAmount2,
    trimmedStrings.phase2DeliverablePeriod,
    trimmedStrings.visaStatus,
    trimmedStrings.technologyTrack,
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

  // Rate amounts: a positive dollar amount (checked once something is typed).
  const amount1Invalid = form.rateAmount1.trim().length > 0 && !isPositiveMoney(form.rateAmount1);
  const amount2Invalid = form.rateAmount2.trim().length > 0 && !isPositiveMoney(form.rateAmount2);

  const canSubmit = allRequiredFilled && !amount1Invalid && !amount2Invalid && !isSubmitting;

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setError("");

    if (!allRequiredFilled) {
      setError("Every required field needs a value.");
      return;
    }
    if (!emailLooksValid) {
      setError("Participant email doesn't look right.");
      return;
    }
    if (amount1Invalid || amount2Invalid) {
      setError(AMOUNT_RULE);
      return;
    }

    setIsSubmitting(true);
    try {
      const app = await createWebAgreement({
        participantUserId,
        ...trimmedStrings,
        requireAppendix1: form.requireAppendix1,
        requireAppendix2: form.requireAppendix2,
        requireAppendix3: form.requireAppendix3,
        requireAppendix4: form.requireAppendix4,
        requireAppendix5: form.requireAppendix5,
        requireSsn: form.requireSsn,
        // Build Y — single ERM-filled debit date/amount free-text.
        achDebitDates: form.achDebitDates.trim(),
        achDebitAmounts: form.achDebitAmounts.trim(),
        // Build Z — ERM-set Appendix 4 (read-only to the participant).
        portalAuthorizedActions: form.portalAuthorizedActions.trim(),
        portalRevocationContact: form.portalRevocationContact.trim(),
      });
      onCreated(app.applicationId);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't create the agreement.");
      setIsSubmitting(false);
    }
    // Don't reset isSubmitting on success -- onCreated swaps this form
    // for the new agreement; resetting would briefly re-enable the
    // button and let the operator double-click.
  };

  if (loading) {
    return (
      <div className="text-center py-10">
        <Loader2 size={20} className="animate-spin text-sage-navy inline" />
      </div>
    );
  }

  if (loadError) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-bold text-gray-900">New agreement</h1>
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-4 sm:p-6 max-w-3xl space-y-4">
          <div
            role="alert"
            className="rounded-md border border-red-200 bg-red-50 px-3 py-2 inline-flex items-start gap-2 text-sm text-red-700 w-full"
          >
            <AlertCircle size={14} className="mt-0.5 shrink-0" />
            <span>{loadError}</span>
          </div>
          <button
            type="button"
            onClick={onCancel}
            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
          >
            <ArrowLeft size={12} /> Back to the list
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div>
        <button
          type="button"
          onClick={onCancel}
          disabled={isSubmitting}
          className="inline-flex items-center gap-1 text-xs font-semibold text-gray-500 hover:text-sage-navy cursor-pointer disabled:opacity-50"
        >
          <ArrowLeft size={12} /> Back to agreements
        </button>
        <h1 className="mt-2 text-2xl font-bold text-gray-900">New agreement</h1>
        <p className="text-sm text-gray-500">
          Create the agreement; the participant fills and signs theirs on their dashboard.
        </p>
      </div>
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-4 sm:p-6 max-w-3xl">
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

          {prefilledFrom && (
            <p className="rounded-md border border-sage-navy/15 bg-sage-navy/5 px-3 py-2 text-xs text-sage-navy">
              Participant details filled in from {prefilledFrom}&apos;s request. Please check them and fill in your side.
            </p>
          )}

          <section className="space-y-3">
            <SectionHeader title="Participant" />
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
                  placeholder="Doe"
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
                  placeholder="participant@example.com"
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
                  Locked on the participant&apos;s view. They&apos;ll see
                  it but cannot change it.
                </p>
              </Field>
            </div>
          </section>

          <section className="space-y-3">
            <SectionHeader title="Service track" />
            <p className="text-[11px] text-gray-500">
              Set the engagement scope. These render in Exhibit A and are
              read-only to the participant.
            </p>
            <Field label="Technology / skill track" required>
              <input
                type="text"
                value={form.technologyTrack}
                onChange={setText("technologyTrack")}
                disabled={isSubmitting}
                required
                placeholder="e.g. ServiceNow, Salesforce, Data Analytics"
                className={inputClass}
              />
            </Field>
            <Field label="Custom scope / notes">
              <textarea
                value={form.customScopeNotes}
                onChange={setText("customScopeNotes")}
                disabled={isSubmitting}
                rows={3}
                placeholder="Optional — any custom scope specific to this engagement."
                className={inputClass + " min-h-[72px]"}
              />
            </Field>
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
                <div className="relative">
                  <span className="pointer-events-none select-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-gray-500">
                    $
                  </span>
                  <input
                    type="text"
                    value={form.rateAmount1}
                    onChange={(e) =>
                      setForm((s) => ({
                        ...s,
                        rateAmount1: e.target.value.replace(/\$/g, ""),
                      }))
                    }
                    disabled={isSubmitting}
                    required
                    inputMode="decimal"
                    aria-invalid={amount1Invalid}
                    placeholder="2,400"
                    className={inputClass + " pl-7"}
                  />
                </div>
                {amount1Invalid && (
                  <p className="mt-1 text-[11px] text-red-600 inline-flex items-start gap-1">
                    <AlertCircle size={11} className="mt-0.5 shrink-0" /> {AMOUNT_RULE}
                  </p>
                )}
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
                <div className="relative">
                  <span className="pointer-events-none select-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-gray-500">
                    $
                  </span>
                  <input
                    type="text"
                    value={form.rateAmount2}
                    onChange={(e) =>
                      setForm((s) => ({
                        ...s,
                        rateAmount2: e.target.value.replace(/\$/g, ""),
                      }))
                    }
                    disabled={isSubmitting}
                    required
                    inputMode="decimal"
                    aria-invalid={amount2Invalid}
                    placeholder="1,920"
                    className={inputClass + " pl-7"}
                  />
                </div>
                {amount2Invalid && (
                  <p className="mt-1 text-[11px] text-red-600 inline-flex items-start gap-1">
                    <AlertCircle size={11} className="mt-0.5 shrink-0" /> {AMOUNT_RULE}
                  </p>
                )}
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
            <SectionHeader title="Phase 2 monthly deliverables" />
            <p className="text-[11px] text-gray-500">
              The single Month / Period value shown in Appendix 1&apos;s
              &ldquo;Schedule 1 – Phase 2 Monthly Deliverables&rdquo; table
              (read-only to the participant).
            </p>
            <Field label="Month / Period" required>
              <input
                type="text"
                value={form.phase2DeliverablePeriod}
                onChange={setText("phase2DeliverablePeriod")}
                disabled={isSubmitting}
                required
                placeholder="Months 1-12"
                className={inputClass}
              />
            </Field>
          </section>

          <section className="space-y-3">
            <SectionHeader title="ACH debit schedule (optional)" />
            <p className="text-[11px] text-gray-500">
              Pre-fill the Appendix 2 debit date(s) and amount(s).
              Free-text (e.g. &ldquo;15th of every month&rdquo;); read-only
              to the participant.
            </p>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <Field label="Debit date(s)">
                <input
                  type="text"
                  value={form.achDebitDates}
                  onChange={setText("achDebitDates")}
                  disabled={isSubmitting}
                  placeholder="e.g. 15th of every month"
                  className={inputClass}
                />
              </Field>
              <Field label="Debit amount(s)">
                <input
                  type="text"
                  value={form.achDebitAmounts}
                  onChange={setText("achDebitAmounts")}
                  disabled={isSubmitting}
                  placeholder="e.g. $416.67"
                  className={inputClass}
                />
              </Field>
            </div>
          </section>

          <section className="space-y-3">
            <SectionHeader title="Appendix 4 — Portal access (optional)" />
            <p className="text-[11px] text-gray-500">
              Pre-fill the Appendix 4 Authorized Actions + Revocation Contact.
              Read-only to the participant. (Platform / Username and the Access
              Effective Date are still filled by the participant.)
            </p>
            <Field label="Authorized actions">
              <textarea
                value={form.portalAuthorizedActions}
                onChange={setText("portalAuthorizedActions")}
                disabled={isSubmitting}
                rows={3}
                placeholder={DEFAULT_PORTAL_AUTHORIZED_ACTIONS}
                className={inputClass + " min-h-[72px]"}
              />
            </Field>
            <Field label="Revocation contact">
              <input
                type="text"
                value={form.portalRevocationContact}
                onChange={setText("portalRevocationContact")}
                disabled={isSubmitting}
                placeholder="Who we notify if access is revoked (e.g. your Sage IT contact)"
                className={inputClass}
              />
            </Field>
          </section>

          <section className="space-y-3">
            <SectionHeader title="Sections required for this participant" />
            <p className="text-[11px] text-gray-500">
              Tick the appendices THIS participant must complete. Unchecked
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
                label="Appendix 5 — Security cheque acknowledgment"
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
                    ? "Participant must enter their full SSN."
                    : "Enable Appendix 3 first."
                }
              />
            </div>
          </section>

          <div className="flex flex-col-reverse sm:flex-row sm:items-center sm:justify-end gap-2 pt-2 border-t border-gray-100">
            <button
              type="button"
              onClick={onCancel}
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
              {isSubmitting ? "Creating…" : "Create agreement"}
            </button>
          </div>
        </form>
      </div>
    </div>
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
