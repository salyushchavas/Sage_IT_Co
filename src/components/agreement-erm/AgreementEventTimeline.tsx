"use client";

import { Clock } from "lucide-react";
import { formatUsDateTime } from "@/lib/dates";
import type { ConsultantApplicationEvent } from "@/lib/api";

/**
 * Every value of ConsultantApplicationEvent.EventType, worded as a past-tense
 * fact. Anything missing here falls through to the raw SCREAMING_SNAKE enum,
 * so keep this in step with the backend enum.
 *
 * Wording follows the shared operator vocabulary in src/lib/agreement-status.ts
 * — APPROVAL_REVISION_REQUESTED is "Declined by approver" here for the same
 * reason the pill says it: the approver bounced their gate back to the ERM,
 * the consultant is not involved.
 */
const EVENT_LABELS: Record<string, string> = {
  CREATED: "Created",
  UPDATED: "Updated",
  ACCESSED: "Opened by consultant",
  // NOT staff verification — this is the consultant ticking "these details are
  // mine" on their own record. "Details verified" read as a Sage-side check.
  DETAILS_VERIFIED: "Details confirmed by consultant",
  CONSULTANT_FILLED: "Consultant updated details",
  APPROVED_AND_SIGNED: "ERM approved and signed",
  REVISION_REQUESTED: "Revision requested",
  REVISED: "Revised",
  SIGNED: "Signed",
  PDF_GENERATED: "Final PDF generated",
  EMAIL_SENT: "Email sent",
  CANCELLED: "Cancelled",
  EXPIRED: "Expired",
  // These three carried a "(legacy)" suffix, which was wrong and dangerous:
  // the consultant portal OTP is LIVE (ConsultantApplicationController
  // /auth/request-otp + /auth/verify-otp), so an operator investigating an
  // account was writing off real failed-login events as historical noise.
  // The enum's own comment in ConsultantApplicationEvent.java still claims the
  // flow was removed — it wasn't; Build V reinstated it appId-bound.
  OTP_SENT: "OTP sent",
  OTP_VERIFIED: "OTP verified",
  OTP_FAILED: "OTP failed",
  // Phase C
  CONSULTANT_CONTACT_UPDATED: "Consultant contact updated",
  APPLICATION_ARCHIVED: "Agreement archived",
  // Consultant uploads
  CHEQUE_UPLOADED: "Security cheque uploaded",
  WORK_AUTH_UPLOADED: "Work authorization document uploaded",
  OFFER_LETTER_UPLOADED: "Offer letter uploaded",
  DL_DOC_UPLOADED: "Driver's license uploaded",
  STATE_ID_DOC_UPLOADED: "State ID uploaded",
  SSN_DOC_UPLOADED: "SSN document uploaded",
  // 3B — role-based approval workflow
  SENT_FOR_APPROVAL: "Sent for approval",
  APPROVAL_APPROVED: "Approved by approver",
  APPROVAL_REVISION_REQUESTED: "Declined by approver",
  // Build M / T / AC
  ADVANCED_TO_PHASE_2: "Advanced to Phase 2",
  CONSENT_GIVEN: "E-sign consent given",
  CONSULTANT_VERSION_APPROVED: "Consultant version released",
  DOWNLOAD_OTP_SENT: "Download OTP sent",
  CONSULTANT_DOWNLOAD: "Consultant downloaded their copy",
  ERM_SIGNATURE_REVOKED: "ERM signature revoked",
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
