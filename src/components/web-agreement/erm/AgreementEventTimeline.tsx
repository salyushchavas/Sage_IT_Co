"use client";

import { Clock } from "lucide-react";
import { formatUsDateTimeCt } from "@/lib/datetime";
import type { WebAgreementEvent } from "@/lib/api";
import { approverRoleLabel } from "@/lib/roles";
import { AGREEMENT_SECTIONS } from "@/lib/web-agreement-sections";
import { statusLabel } from "@/lib/web-agreement-status";

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
  // The ERM checked the signed agreement and released it as a new version
  // (the console's "Consultant version released").
  VERIFIED: "Verified by ERM",
  INVITE_RESENT: "Invitation re-sent",
  // The ERM took a change request back; metadata carries the status it was
  // restored to and the round number that was rolled back.
  REVISION_REVOKED: "Change request withdrawn",
  // The approval chain, worded as the console's timeline words them.
  SENT_FOR_APPROVAL: "Sent for approval",
  APPROVAL_APPROVED: "Approved by approver",
  // The approver bounced their gate back to the ERM; the participant is not
  // involved.
  APPROVAL_REVISION_REQUESTED: "Declined by approver",
  APPROVED_AND_SIGNED: "ERM approved and signed",
  PDF_GENERATED: "Final PDF generated",
  ADVANCED_TO_PHASE_2: "Advanced to Phase 2",
  // System Admin tools.
  ERM_SIGNATURE_REVOKED: "ERM signature revoked",
  APPLICATION_ARCHIVED: "Agreement archived",
};

const ACTOR_LABELS: Record<string, string> = {
  ERM: "ERM",
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
        const details = extra ? describeMetadata(e.eventType, extra) : [];
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
              {formatUsDateTimeCt(e.createdAt)}
            </p>
            {details.length > 0 && (
              <p className="mt-1 text-[11px] text-gray-600 bg-gray-50 border border-gray-100 rounded-md px-2 py-1 break-words">
                {details.join(" · ")}
              </p>
            )}
          </li>
        );
      })}
    </ol>
  );
}

// ── Event details ─────────────────────────────────────────────────
//
// The event's metadata as short readable facts. Only known keys are shown;
// storage keys, hashes, internal ids and raw field names never are.

const SECTION_TITLES: Record<string, string> = Object.fromEntries(
  AGREEMENT_SECTIONS.map((s) => [s.id, s.title]),
);

const DOC_LABELS: Record<string, string> = {
  "doc:workauth": "Work-authorization document",
  "doc:offer-letter": "Offer letter",
  "doc:dl-doc": "Driver's license",
  "doc:state-id": "State ID",
  "doc:ssn-doc": "SSN document",
  "doc:cheque": "Security cheque(s)",
};

const REVOKED_KINDS: Record<string, string> = {
  sections: "Section revision",
  signature: "Signature re-sign",
  documents: "Document re-upload",
};

/**
 * Phase 2 promotes appendices and, separately, the SSN requirement; the
 * SSN's name is the one on the console's Advance to Phase 2 checklist.
 */
const PROMOTED_LABELS: Record<string, string> = {
  ...SECTION_TITLES,
  ssn: "Require SSN (within Appendix 3)",
};

const PDF_KINDS: Record<string, string> = {
  final: "Final PDF",
  regenerated: "Regenerated",
};

function text(v: unknown): string {
  return typeof v === "string" ? v.trim() : typeof v === "number" ? String(v) : "";
}

function fileKind(contentType: string): string {
  const t = contentType.toLowerCase();
  if (t === "application/pdf") return "PDF";
  if (t.startsWith("image/")) return `${t.slice(6).toUpperCase()} image`;
  return "File";
}

function fileSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function describeMetadata(eventType: string, m: Record<string, unknown>): string[] {
  const out: string[] = [];

  // Uploads: which cheque, what kind of file, how big.
  if (typeof m.index === "number") out.push(`Cheque #${m.index + 1}`);
  const type = text(m.contentType);
  if (type) {
    out.push(
      typeof m.bytes === "number" ? `${fileKind(type)}, ${fileSize(m.bytes)}` : fileKind(type),
    );
  }

  // Created for; signed as.
  if (text(m.consultantEmail)) out.push(`For ${text(m.consultantEmail)}`);
  if (text(m.legalName)) out.push(`Signed as ${text(m.legalName)}`);

  // Participant edits.
  if (Array.isArray(m.fieldsTouched)) {
    const n = m.fieldsTouched.length;
    out.push(`${n} field${n === 1 ? "" : "s"} changed`);
  }

  // Status moves.
  if (text(m.from) && text(m.to)) {
    out.push(`${statusLabel(text(m.from))} → ${statusLabel(text(m.to))}`);
  } else if (text(m.from)) {
    out.push(`Was ${statusLabel(text(m.from))}`);
  } else if (text(m.status)) {
    out.push(`Status: ${statusLabel(text(m.status))}`);
  }

  // Contact fix.
  if (text(m.oldEmail) !== text(m.newEmail) && text(m.newEmail)) {
    out.push(`Email: ${text(m.oldEmail) || "—"} → ${text(m.newEmail)}`);
  }
  if (text(m.oldName) !== text(m.newName) && text(m.newName)) {
    out.push(`Name: ${text(m.oldName) || "—"} → ${text(m.newName)}`);
  }

  // Change requests.
  if (Array.isArray(m.selectedSections) && m.selectedSections.length > 0) {
    out.push(
      "Sections: "
        + m.selectedSections.map((k) => SECTION_TITLES[text(k)] ?? "Other section").join(", "),
    );
  }
  if (m.signatureRevision === true) out.push("Signature re-sign");
  if (m.documentRevision === true && text(m.documents)) {
    out.push(
      "Documents: "
        + text(m.documents).split(",").map((k) => DOC_LABELS[k.trim()] ?? "Other document").join(", "),
    );
  }
  // "kind" means a change-request kind only on a take-back (PDF_GENERATED
  // has its own kind, below).
  if (eventType === "REVISION_REVOKED" && text(m.kind)) {
    out.push(REVOKED_KINDS[text(m.kind)] ?? "Change request");
  }
  if (text(m.restoredTo)) out.push(`Back to ${statusLabel(text(m.restoredTo))}`);
  if (text(m.revertedErmCorrections)) out.push(`Reverted: ${text(m.revertedErmCorrections)}`);
  if (typeof m.rolledBackRound === "number") {
    out.push(`Round ${m.rolledBackRound} rolled back`);
  } else if (typeof m.revisionCount === "number" && m.revisionCount > 0) {
    out.push(
      eventType === "REVISION_REQUESTED"
        ? `Round ${m.revisionCount}`
        : `After ${m.revisionCount} revision round${m.revisionCount === 1 ? "" : "s"}`,
    );
  }

  // The e-sign consent's version ("version" on VERIFIED and
  // SENT_FOR_APPROVAL is the agreement version, below).
  if (eventType === "CONSENT_GIVEN" && text(m.version)) {
    out.push(`Consent version ${text(m.version)}`);
  }

  out.push(...describeApprovalChain(eventType, m));
  return out;
}

/** The agreement version a VERIFIED or SENT_FOR_APPROVAL event names, or "". */
function versionText(m: Record<string, unknown>): string {
  const v = text(m.version);
  // A send for an agreement verified before versions existed records "null".
  return v && v !== "null" ? `Version V${v}` : "";
}

/**
 * The approval chain's events: the facts the console's timeline shows as raw
 * metadata, as short lines.
 */
function describeApprovalChain(eventType: string, m: Record<string, unknown>): string[] {
  const out: string[] = [];
  const phase = text(m.phase);
  const round = text(m.round);

  switch (eventType) {
    case "VERIFIED": {
      const version = versionText(m);
      if (version) out.push(version);
      break;
    }
    case "SENT_FOR_APPROVAL": {
      if (phase && round) out.push(`Phase ${phase} · Round ${round}`);
      else if (round) out.push(`Round ${round}`);
      else if (phase) out.push(`Phase ${phase}`);
      const routedTo = Array.isArray(m.routedTo) ? m.routedTo.map(text).filter(Boolean) : [];
      if (routedTo.length > 0) out.push(`Routed to ${routedTo.join(", ")}`);
      const version = versionText(m);
      if (version) out.push(version);
      if (m.resend === true) out.push("Re-send");
      break;
    }
    case "APPROVAL_APPROVED":
    case "APPROVAL_REVISION_REQUESTED": {
      if (text(m.role)) {
        const role = approverRoleLabel(text(m.role));
        out.push(round ? `${role} · Round ${round}` : role);
      }
      if (text(m.approver)) out.push(`By ${text(m.approver)}`);
      if (eventType === "APPROVAL_REVISION_REQUESTED" && text(m.note)) {
        out.push(`Note: ${text(m.note)}`);
      }
      break;
    }
    case "APPROVED_AND_SIGNED": {
      const name = text(m.ermName);
      const title = text(m.ermTitle);
      if (name) out.push(title ? `Signed as ${name}, ${title}` : `Signed as ${name}`);
      break;
    }
    case "PDF_GENERATED": {
      const kind = PDF_KINDS[text(m.kind)];
      if (kind) out.push(kind);
      break;
    }
    case "ADVANCED_TO_PHASE_2": {
      if (Array.isArray(m.promoted)) {
        const promoted = m.promoted.map(text).filter(Boolean);
        out.push(
          promoted.length > 0
            ? "Reopened: " + promoted.map((k) => PROMOTED_LABELS[k] ?? "Other section").join(", ")
            : "Nothing reopened",
        );
      }
      break;
    }
    case "ERM_SIGNATURE_REVOKED": {
      if (text(m.fromStatus) && text(m.toStatus)) {
        out.push(`${statusLabel(text(m.fromStatus))} → ${statusLabel(text(m.toStatus))}`);
      }
      break;
    }
    case "APPLICATION_ARCHIVED": {
      if (text(m.previousStatus)) out.push(`Was ${statusLabel(text(m.previousStatus))}`);
      break;
    }
  }
  return out;
}
