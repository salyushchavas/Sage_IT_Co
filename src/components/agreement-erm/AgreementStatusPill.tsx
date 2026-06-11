"use client";

import type { ConsultantApplicationStatus } from "@/lib/api";

const STYLES: Record<ConsultantApplicationStatus, { bg: string; text: string; label: string }> = {
  DRAFT:              { bg: "bg-gray-100",    text: "text-gray-700",    label: "Draft" },
  SUBMITTED:          { bg: "bg-amber-50",    text: "text-amber-700",   label: "Submitted" },
  REVISION_REQUESTED: { bg: "bg-orange-50",   text: "text-orange-700",  label: "Revision requested" },
  UPDATED:            { bg: "bg-sky-50",      text: "text-sky-700",     label: "Updated" },
  VERIFIED:           { bg: "bg-blue-50",     text: "text-blue-700",    label: "Verified" },
  AWAITING_APPROVALS: { bg: "bg-violet-50",   text: "text-violet-700",  label: "Awaiting approvals" },
  APPROVAL_REVISION_REQUESTED: { bg: "bg-orange-50", text: "text-orange-700", label: "Approval revision" },
  READY_TO_SIGN:      { bg: "bg-teal-50",     text: "text-teal-700",    label: "Ready to sign" },
  SIGNED:             { bg: "bg-emerald-50",  text: "text-emerald-700", label: "Signed" },
  COMPLETED:          { bg: "bg-emerald-100", text: "text-emerald-800", label: "Completed" },
  CANCELLED:          { bg: "bg-red-50",      text: "text-red-700",     label: "Cancelled" },
  EXPIRED:            { bg: "bg-zinc-100",    text: "text-zinc-600",    label: "Expired" },
};

export default function AgreementStatusPill({
  status,
  size = "sm",
}: {
  status: ConsultantApplicationStatus;
  size?: "sm" | "xs";
}) {
  const s = STYLES[status] ?? STYLES.DRAFT;
  const pad = size === "xs" ? "px-2 py-0.5 text-[10px]" : "px-2.5 py-0.5 text-[11px]";
  return (
    <span className={`inline-flex items-center rounded-full font-bold ${pad} ${s.bg} ${s.text}`}>
      {s.label}
    </span>
  );
}
