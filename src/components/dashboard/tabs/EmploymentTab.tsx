"use client";

import { useEffect, useState } from "react";
import {
  AlertCircle,
  CheckCircle2,
  FileText,
  Loader2,
  Upload as UploadIcon,
} from "lucide-react";

import {
  acceptEmployment,
  acceptPhase1Completion,
  getEmploymentStatus,
  uploadOfferDocument,
  type EmploymentStatus,
} from "@/lib/api";

const PHASE_1_ACK_VERSION = "PH1-v1.0";

export default function EmploymentTab() {
  const [status, setStatus] = useState<EmploymentStatus | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = async () => {
    setStatus(await getEmploymentStatus());
  };

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setInterval> | null = null;
    const tick = async () => {
      try {
        const s = await getEmploymentStatus();
        if (!cancelled) setStatus(s);
      } catch {
        /* swallow */
      }
    };
    tick().finally(() => {
      if (!cancelled) setLoading(false);
    });
    // Light polling while waiting for ERM verification.
    timer = setInterval(tick, 15_000);
    return () => {
      cancelled = true;
      if (timer) clearInterval(timer);
    };
  }, []);

  if (loading) {
    return (
      <div className="text-center py-10">
        <Loader2 size={20} className="animate-spin text-sage-navy inline" />
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <h1 className="text-2xl font-bold text-gray-900">Employment acceptance</h1>

      {!status?.submitted ? (
        <EmploymentForm onSaved={refresh} />
      ) : (
        <EmploymentSummary status={status!} />
      )}

      {status?.submitted &&
        (status.ermVerified ? (
          <Phase1Section status={status} onSaved={refresh} />
        ) : (
          <PendingErmCallout ermName={status.ermName ?? null} />
        ))}
    </div>
  );
}

function EmploymentForm({ onSaved }: { onSaved: () => Promise<void> }) {
  const [employer, setEmployer] = useState("");
  const [jobTitle, setJobTitle] = useState("");
  const [startDate, setStartDate] = useState("");
  const [location, setLocation] = useState("");
  const [employmentType, setEmploymentType] = useState("Full-time");
  const [notes, setNotes] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  const canSubmit = employer.trim() && jobTitle.trim() && startDate && !saving;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSaving(true);
    setError("");
    try {
      let offerUrl: string | null = null;
      if (file) {
        const up = await uploadOfferDocument(file);
        offerUrl = up.url;
      }
      await acceptEmployment({
        employer: employer.trim(),
        jobTitle: jobTitle.trim(),
        startDate,
        location: location.trim() || null,
        employmentType: employmentType || null,
        offerDocumentUrl: offerUrl,
        notes: notes.trim() || null,
      });
      await onSaved();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't submit employment");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="rounded-2xl border border-gray-100 bg-white shadow-sm p-5 space-y-3">
      <p className="text-sm text-gray-500">
        Congratulations on your offer! Provide the following details so your
        ERM can verify and unlock Phase 1 completion.
      </p>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Input label="Employer / Client *" value={employer} onChange={setEmployer} />
        <Input label="Job title *" value={jobTitle} onChange={setJobTitle} />
        <Input label="Start date *" type="date" value={startDate} onChange={setStartDate} />
        <Input label="Location" value={location} onChange={setLocation} />
        <div>
          <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
            Employment type
          </label>
          <select
            value={employmentType}
            onChange={(e) => setEmploymentType(e.target.value)}
            className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200 bg-white"
          >
            {["Full-time", "Part-time", "Contract", "Internship", "Other"].map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
            Offer document (PDF / JPG / PNG)
          </label>
          <input
            type="file"
            accept="application/pdf,image/png,image/jpeg,image/jpg"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            className="block w-full text-xs file:mr-3 file:py-1.5 file:px-3 file:rounded-md file:border-0 file:text-xs file:font-semibold file:bg-sage-navy file:text-white hover:file:bg-sage-navy-deep cursor-pointer"
          />
          <p className="text-[10px] text-gray-400 mt-0.5">Max 5 MB.</p>
        </div>
      </div>
      <div>
        <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
          Additional notes
        </label>
        <textarea
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={3}
          className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
        />
      </div>
      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      <div className="flex justify-end">
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!canSubmit}
          className={
            "inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-bold transition " +
            (canSubmit
              ? "bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
              : "bg-gray-200 text-gray-500 cursor-not-allowed")
          }
        >
          {saving ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <UploadIcon size={14} />
          )}
          {saving ? "Submitting…" : "Submit employment acceptance →"}
        </button>
      </div>
      <p className="text-[11px] text-gray-400">
        After submission, your ERM will review and verify.
      </p>
    </div>
  );
}

function EmploymentSummary({ status }: { status: EmploymentStatus }) {
  const d = status.details;
  const verified = status.ermVerified;
  return (
    <div
      className={
        "rounded-2xl border p-5 " +
        (verified
          ? "border-emerald-200 bg-emerald-50/30"
          : "border-amber-200 bg-amber-50/30")
      }
    >
      <div className="flex items-center gap-2 mb-3">
        <CheckCircle2
          size={16}
          className={verified ? "text-emerald-600" : "text-amber-600"}
        />
        <p className="text-sm font-bold text-gray-900">
          {verified
            ? "Employment verified by ERM"
            : "Employment acceptance submitted"}
        </p>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5 text-sm">
        <SumRow label="Employer" value={d?.employerClient} />
        <SumRow label="Job title" value={d?.jobTitle} />
        <SumRow label="Start date" value={d?.startDate} mono />
        <SumRow label="Location" value={d?.location} />
        <SumRow label="Employment type" value={d?.employmentType} />
        <SumRow
          label="Submitted"
          value={
            d?.acceptanceDate
              ? new Date(d.acceptanceDate).toLocaleString("en-IN", {
                  timeZone: "Asia/Kolkata",
                  dateStyle: "medium",
                  timeStyle: "short",
                })
              : null
          }
        />
      </div>
      {d?.notes && (
        <p className="mt-3 text-xs text-gray-600 italic">
          &ldquo;{d.notes}&rdquo;
        </p>
      )}
      {d?.offerDocumentUrl && (
        <a
          href={d.offerDocumentUrl}
          target="_blank"
          rel="noreferrer"
          className="mt-3 inline-flex items-center gap-1 text-xs font-semibold text-sage-navy hover:underline"
        >
          <FileText size={11} /> View uploaded offer document
        </a>
      )}
      {verified && d?.ermVerifiedDate && (
        <p className="mt-3 text-xs text-emerald-700">
          Verified by {status.ermName ?? "your ERM"} on{" "}
          {new Date(d.ermVerifiedDate).toLocaleDateString("en-IN", {
            timeZone: "Asia/Kolkata",
            dateStyle: "medium",
          })}
          .
          {d.ermNotes && (
            <span className="block text-gray-600 italic mt-1">
              ERM note: {d.ermNotes}
            </span>
          )}
        </p>
      )}
    </div>
  );
}

function PendingErmCallout({ ermName }: { ermName: string | null }) {
  return (
    <div className="rounded-2xl border border-dashed border-amber-200 bg-amber-50/40 p-5">
      <p className="text-sm text-amber-900 inline-flex items-center gap-1.5">
        <Loader2 size={14} className="animate-spin" />
        Pending ERM verification
      </p>
      <p className="text-xs text-gray-600 mt-1.5">
        Your ERM{ermName ? ` (${ermName})` : ""} will review and verify your
        employment shortly. This page refreshes automatically. Once verified,
        the Phase 1 acknowledgment unlocks below.
      </p>
    </div>
  );
}

function Phase1Section({
  status,
  onSaved,
}: {
  status: EmploymentStatus;
  onSaved: () => Promise<void>;
}) {
  const alreadyAccepted = !!status.phase1?.acceptedAt;
  const [accepted, setAccepted] = useState(alreadyAccepted);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const handleAccept = async () => {
    if (!accepted || alreadyAccepted) return;
    setSaving(true);
    setError("");
    try {
      await acceptPhase1Completion(PHASE_1_ACK_VERSION);
      await onSaved();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't accept Phase 1");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="rounded-2xl border border-sage-navy/20 bg-sage-navy/5 p-5 space-y-3">
      <h2 className="text-xl font-bold text-gray-900">
        Phase 1 completion acknowledgment
      </h2>
      <div className="rounded-xl border border-gray-200 bg-white p-4 text-sm text-gray-700 leading-relaxed space-y-2">
        <p className="font-bold text-gray-900">
          PHASE 1 COMPLETION ACKNOWLEDGMENT
        </p>
        <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
          Version: {PHASE_1_ACK_VERSION}
        </p>
        <p>By accepting this acknowledgment, I confirm:</p>
        <ol className="list-decimal pl-5 space-y-1 text-[13px]">
          <li>
            I have completed the Phase 1 pre-employment readiness program
            activities including career coaching, resume administration,
            interview preparation, technical modules, and job-navigation
            support.
          </li>
          <li>
            I have accepted employment and provided the required acceptance
            details.
          </li>
          <li>
            I understand that Phase 1 completion activates the payment plan and
            invoice schedule as per my signed agreement.
          </li>
          <li>
            I acknowledge that Phase 2 post-offer support will be provided as
            per the terms of my agreement.
          </li>
        </ol>
      </div>

      {alreadyAccepted ? (
        <div className="rounded-xl border border-emerald-200 bg-emerald-50/60 p-3 text-sm text-emerald-900 inline-flex items-center gap-2">
          <CheckCircle2 size={14} className="text-emerald-700" />
          <span>
            Phase 1 acknowledgment accepted on{" "}
            {status.phase1?.acceptedAt
              ? new Date(status.phase1.acceptedAt).toLocaleDateString("en-IN", {
                  timeZone: "Asia/Kolkata",
                  dateStyle: "medium",
                })
              : "—"}{" "}
            ·{" "}
            <span className="font-mono text-xs">
              {status.phase1?.acknowledgmentVersion}
            </span>
          </span>
        </div>
      ) : (
        <>
          <label className="flex items-start gap-2.5 text-sm text-gray-700 cursor-pointer">
            <input
              type="checkbox"
              checked={accepted}
              onChange={(e) => setAccepted(e.target.checked)}
              className="mt-0.5 w-4 h-4 rounded border-gray-300 text-sage-navy focus:ring-sage-copper"
            />
            <span>
              I accept the Phase 1 Completion Acknowledgment ({PHASE_1_ACK_VERSION}){" "}
              <span className="text-red-500">*</span>
            </span>
          </label>
          {error && (
            <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
              <AlertCircle size={14} /> {error}
            </p>
          )}
          <div className="flex justify-end">
            <button
              type="button"
              onClick={handleAccept}
              disabled={!accepted || saving}
              className={
                "inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-bold transition " +
                (accepted && !saving
                  ? "bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
                  : "bg-gray-200 text-gray-500 cursor-not-allowed")
              }
            >
              {saving ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <CheckCircle2 size={14} />
              )}
              {saving ? "Accepting…" : "Accept Phase 1 completion →"}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

function SumRow({
  label,
  value,
  mono,
}: {
  label: string;
  value?: string | null;
  mono?: boolean;
}) {
  return (
    <div>
      <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">
        {label}
      </p>
      <p
        className={
          mono
            ? "font-mono text-[13px] text-gray-800"
            : "text-[13px] text-gray-800"
        }
      >
        {value ?? "—"}
      </p>
    </div>
  );
}

function Input({
  label,
  value,
  onChange,
  type = "text",
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
}) {
  return (
    <div>
      <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
        {label}
      </label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
      />
    </div>
  );
}
