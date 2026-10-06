"use client";

import { Clock } from "lucide-react";
import { formatUsDateTime } from "@/lib/dates";
import type { WebAgreementEvent } from "@/lib/api";

/**
 * The website agreement's copy of the console's activity timeline
 * (src/components/agreement-erm/AgreementEventTimeline.tsx).
 *
 * Every value of WebAgreementEvent.EventType, worded as a past-tense fact.
 * Anything missing here falls through to the raw SCREAMING_SNAKE enum, so
 * keep this in step with the backend enum.
 */
const EVENT_LABELS: Record<string, string> = {
  CREATED: "Created",
  ACCESSED: "Opened by participant",
  CONSULTANT_FILLED: "Participant updated details",
  REVISION_REQUESTED: "Revision requested",
  SIGNED: "Signed",
  EMAIL_SENT: "Email sent",
  CANCELLED: "Cancelled",
  CONSULTANT_CONTACT_UPDATED: "Participant contact updated",
  // Participant uploads
  CHEQUE_UPLOADED: "Security cheque uploaded",
  CHEQUE_METADATA_UPDATED: "Cheque number/date edited",
  WORK_AUTH_UPLOADED: "Work authorization document uploaded",
  OFFER_LETTER_UPLOADED: "Offer letter uploaded",
  DL_DOC_UPLOADED: "Driver's license uploaded",
  STATE_ID_DOC_UPLOADED: "State ID uploaded",
  SSN_DOC_UPLOADED: "SSN document uploaded",
  CONSENT_GIVEN: "E-sign consent given",
  // The ERM checked the signed agreement (the console's "Consultant version
  // released", without the PDF).
  VERIFIED: "Verified by ERM",
  INVITE_RESENT: "Invitation re-sent",
  // The ERM took a change request back; metadata carries the status it was
  // restored to and the round number that was rolled back.
  REVISION_REVOKED: "Change request withdrawn",
};

const ACTOR_LABELS: Record<string, string> = {
  ERM: "Operator",
  PARTICIPANT: "Participant",
  SYSTEM: "System",
};

const ACTOR_COLORS: Record<string, string> = {
  ERM: "bg-sage-navy/10 text-sage-navy",
  PARTICIPANT: "bg-sage-copper/10 text-sage-copper",
  SYSTEM: "bg-gray-100 text-gray-600",
};

export default function AgreementEventTimeline({
  events,
}: {
  events: WebAgreementEvent[];
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
              {formatUsDateTime(e.createdAt)}
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
