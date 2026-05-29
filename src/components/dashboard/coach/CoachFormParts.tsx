"use client";

import { ReactNode } from "react";
import { Loader2, Plus } from "lucide-react";

import type { CoachParticipantRow } from "@/lib/api";

// Shared form primitives used by Sessions, Tasks, and Feedback tabs.
// Each owning tab passes the participant selector value/setter plus its
// own fields as children.

export function CoachForm({
  participantId,
  onParticipant,
  participants,
  submitLabel,
  saving,
  onSubmit,
  children,
}: {
  participantId: number | "";
  onParticipant: (v: number | "") => void;
  participants: CoachParticipantRow[];
  submitLabel: string;
  saving: boolean;
  onSubmit: () => void;
  children: ReactNode;
}) {
  return (
    <div className="rounded-2xl border border-gray-100 bg-white p-4 space-y-3">
      <div>
        <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
          Participant
        </label>
        <select
          value={participantId}
          onChange={(e) =>
            onParticipant(e.target.value ? Number(e.target.value) : "")
          }
          className="w-full px-3 py-2 text-sm rounded-md border border-gray-200"
        >
          <option value="">-- Pick one --</option>
          {participants.map((p) => (
            <option key={p.userId} value={p.userId}>
              {p.fullName ?? "--"} ({p.technology ?? "--"})
            </option>
          ))}
        </select>
      </div>
      {children}
      <div className="flex justify-end">
        <button
          type="button"
          onClick={onSubmit}
          disabled={saving || !participantId}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
        >
          {saving ? (
            <Loader2 size={12} className="animate-spin" />
          ) : (
            <Plus size={12} />
          )}
          {submitLabel}
        </button>
      </div>
    </div>
  );
}

export function FormRow({ children }: { children: ReactNode }) {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">{children}</div>
  );
}

export function Field({
  label,
  type = "text",
  value,
  onChange,
  rows,
}: {
  label: string;
  type?: "text" | "date" | "number" | "textarea";
  value: string;
  onChange: (v: string) => void;
  rows?: number;
}) {
  return (
    <div>
      <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
        {label}
      </label>
      {type === "textarea" ? (
        <textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          rows={rows ?? 3}
          className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
        />
      ) : (
        <input
          type={type}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
        />
      )}
    </div>
  );
}

export function RecordsList<T>({
  items,
  empty,
  renderRow,
}: {
  items: T[];
  empty: string;
  renderRow: (t: T) => ReactNode;
}) {
  return (
    <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden divide-y divide-gray-100">
      {items.length === 0 ? (
        <p className="px-4 py-6 text-center text-sm text-gray-400 italic">
          {empty}
        </p>
      ) : (
        items.map((it, idx) => (
          <div
            key={idx}
            className="px-4 py-2.5 flex items-center gap-3 text-sm"
          >
            {renderRow(it)}
          </div>
        ))
      )}
    </div>
  );
}

export function TabLoading() {
  return (
    <div className="text-center py-10">
      <Loader2 size={20} className="animate-spin text-sage-navy inline" />
    </div>
  );
}
