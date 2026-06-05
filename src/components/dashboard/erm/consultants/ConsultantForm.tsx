"use client";

import { useState, type FormEvent } from "react";
import { AlertCircle, Loader2, Save } from "lucide-react";

export interface ConsultantFormValues {
  consultantEmail: string;
  consultantName: string;
  consultantPhone: string;
  payloadJson: string;
}

interface Props {
  initial?: Partial<ConsultantFormValues>;
  submitLabel?: string;
  disableEmail?: boolean;
  onSubmit: (values: ConsultantFormValues) => Promise<void>;
  onCancel?: () => void;
}

/**
 * Shared form used by /erm-dashboard/consultants/new and the edit
 * panel in /erm-dashboard/consultants/{appId}. The payload field is
 * a free-form JSON textarea -- a placeholder until the final field
 * schema is decided. The form validates that whatever the ERM types
 * parses as JSON before sending, so we never POST malformed bodies.
 */
export default function ConsultantForm({
  initial,
  submitLabel = "Save",
  disableEmail = false,
  onSubmit,
  onCancel,
}: Props) {
  const [email, setEmail] = useState(initial?.consultantEmail ?? "");
  const [name, setName] = useState(initial?.consultantName ?? "");
  const [phone, setPhone] = useState(initial?.consultantPhone ?? "");
  const [payloadJson, setPayloadJson] = useState(initial?.payloadJson ?? "{}");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setError("");

    if (!email.trim() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      setError("A valid consultant email is required.");
      return;
    }
    if (payloadJson.trim()) {
      try {
        JSON.parse(payloadJson);
      } catch {
        setError("Payload is not valid JSON.");
        return;
      }
    }

    setSaving(true);
    try {
      await onSubmit({
        consultantEmail: email.trim(),
        consultantName: name.trim(),
        consultantPhone: phone.trim(),
        payloadJson: payloadJson.trim(),
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Save failed");
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Field label="Consultant email" required>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={disableEmail || saving}
            placeholder="consultant@example.com"
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy disabled:bg-gray-50 disabled:text-gray-500"
          />
        </Field>
        <Field label="Consultant name">
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            disabled={saving}
            placeholder="Full name"
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
          />
        </Field>
        <Field label="Phone" className="sm:col-span-2">
          <input
            type="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            disabled={saving}
            placeholder="+91 ..."
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
          />
        </Field>
      </div>

      <Field label="Payload (JSON)">
        <textarea
          value={payloadJson}
          onChange={(e) => setPayloadJson(e.target.value)}
          disabled={saving}
          rows={10}
          className="w-full px-3 py-2 text-xs font-mono rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
          placeholder='{ "scopeOfWork": "...", "engagementMonths": 6 }'
        />
        <p className="mt-1 text-[11px] text-gray-500">
          Placeholder schema. The structured form will replace this textarea
          once the field schema is locked in.
        </p>
      </Field>

      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}

      <div className="flex items-center justify-end gap-2">
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            disabled={saving}
            className="px-3 py-1.5 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer disabled:opacity-50"
          >
            Cancel
          </button>
        )}
        <button
          type="submit"
          disabled={saving}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
        >
          {saving ? <Loader2 size={12} className="animate-spin" /> : <Save size={12} />}
          {saving ? "Saving…" : submitLabel}
        </button>
      </div>
    </form>
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
