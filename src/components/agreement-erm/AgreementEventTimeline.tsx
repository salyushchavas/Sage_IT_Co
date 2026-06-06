"use client";

import { Clock } from "lucide-react";
import type { ConsultantApplicationEvent } from "@/lib/api";

const EVENT_LABELS: Record<string, string> = {
  CREATED: "Created",
  UPDATED: "Updated",
  ACCESSED: "Opened by consultant",
  DETAILS_VERIFIED: "Details verified",
  CONSULTANT_FILLED: "Consultant updated details",
  APPROVED_AND_SIGNED: "ERM approved and signed",
  REVISION_REQUESTED: "Revision requested",
  REVISED: "Revised",
  SIGNED: "Signed",
  PDF_GENERATED: "Final PDF generated",
  EMAIL_SENT: "Email sent",
  CANCELLED: "Cancelled",
  EXPIRED: "Expired",
  OTP_SENT: "OTP sent (legacy)",
  OTP_VERIFIED: "OTP verified (legacy)",
  OTP_FAILED: "OTP failed (legacy)",
};

const ACTOR_LABELS: Record<string, string> = {
  ERM: "Operator",
  CONSULTANT: "Consultant",
  SYSTEM: "System",
};

const ACTOR_COLORS: Record<string, string> = {
  ERM: "bg-sage-navy/10 text-sage-navy",
  CONSULTANT: "bg-sage-copper/10 text-sage-copper",
  SYSTEM: "bg-gray-100 text-gray-600",
};

export default function AgreementEventTimeline({
  events,
}: {
  events: ConsultantApplicationEvent[];
}) {
  if (events.length === 0) {
    return <p className="text-xs text-gray-400 italic">No activity yet.</p>;
  }

  const sorted = [...events].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  );

  return (
    <ol className="relative border-l border-gray-200 ml-2 space-y-3">
      {sorted.map((e) => {
        let extra: Record<string, unknown> | null = null;
        if (e.metadata) {
          try {
            extra = JSON.parse(e.metadata) as Record<string, unknown>;
          } catch {
            extra = null;
          }
        }
        return (
          <li key={e.id} className="ml-3">
            <div className="absolute -left-[5px] mt-1.5 w-2.5 h-2.5 rounded-full bg-sage-navy" />
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-sm font-semibold text-gray-900">
                {EVENT_LABELS[e.eventType] ?? e.eventType}
              </span>
              <span
                className={
                  "px-2 py-0.5 rounded-full text-[10px] font-bold " +
                  (ACTOR_COLORS[e.actorType] ?? "bg-gray-100 text-gray-600")
                }
              >
                {ACTOR_LABELS[e.actorType] ?? e.actorType}
              </span>
              {e.ipAddress && (
                <span className="text-[10px] font-mono text-gray-400">
                  {e.ipAddress}
                </span>
              )}
            </div>
            <p className="text-[11px] text-gray-500 inline-flex items-center gap-1 mt-0.5">
              <Clock size={10} />
              {new Date(e.createdAt).toLocaleString("en-IN", {
                timeZone: "Asia/Kolkata",
                dateStyle: "medium",
                timeStyle: "short",
              })}
            </p>
            {extra && Object.keys(extra).length > 0 && (
              <pre className="mt-1 text-[11px] text-gray-600 bg-gray-50 border border-gray-100 rounded-md px-2 py-1 whitespace-pre-wrap">
                {JSON.stringify(extra, null, 2)}
              </pre>
            )}
          </li>
        );
      })}
    </ol>
  );
}
