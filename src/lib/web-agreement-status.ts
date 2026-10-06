import type { WebAgreementStatus } from "@/lib/api";

/**
 * The website agreement's copy of the parts of src/lib/agreement-status.ts
 * the ERM list and detail views need, under the same export names, so the
 * website's copies of those views only change their import path. Kept
 * separate so a change here never reaches the console.
 *
 * Single source of truth for what a website-agreement status MEANS and what
 * it is called on the ERM screens.
 *
 * VERIFIED is set the moment the PARTICIPANT SIGNS; nobody at Sage has
 * checked anything yet. The ERM's "Verify" action then sets
 * consultantCopyReleased, which is the only thing separating "Signed by
 * participant" (the ERM is checking it) from "Verified" (checked).
 *
 * Labels here are STAFF-facing (ERM / Operations). The participant sees the
 * softer wording MasterAgreementService sends with the dashboard step.
 *
 * Rule of thumb for every label below: name what HAPPENED and who owes the
 * next move, never the internal enum spelling.
 */

/** Which desk the agreement is sitting on right now. */
export type AgreementStage =
  | "with_participant"
  | "with_erm"
  | "with_approvers"
  | "executed"
  | "closed"
  | "stuck";

/** Flat badge palette matching the console's light-surface house style. */
export type StatusTone =
  | "neutral"
  | "waiting"
  | "action"
  | "review"
  | "attention"
  | "ready"
  | "done"
  | "danger";

export const TONE_CLASSES: Record<StatusTone, string> = {
  neutral: "bg-gray-100 text-gray-700",
  waiting: "bg-amber-50 text-amber-700",
  action: "bg-sky-50 text-sky-700",
  review: "bg-violet-50 text-violet-700",
  attention: "bg-orange-50 text-orange-700",
  ready: "bg-teal-50 text-teal-700",
  done: "bg-emerald-50 text-emerald-700",
  danger: "bg-red-50 text-red-700",
};

/** Who owes the next move while a row sits in a state. */
export type BlockedOn =
  | "Participant"
  | "ERM"
  | "Manager"
  | "Manager + Accounts"
  | "Operator"
  | null;

export interface AgreementStatusMeta {
  /** Short staff-facing label. Goes in the pill. */
  label: string;
  /** One sentence: what actually happened to put the row here. */
  meaning: string;
  /** Who is holding this up. Null when nothing is pending. */
  blockedOn: BlockedOn;
  /** What that party has to do next. Null when nothing is pending. */
  nextAction: string | null;
  stage: AgreementStage;
  /** Position on the happy path (1-5), or null for off-path states. */
  step: number | null;
  tone: StatusTone;
  /**
   * True for the states defined for later (internal approval and the
   * countersign are not built yet), so no live code path produces them.
   * Hidden from filters.
   */
  later?: boolean;
}

export const AGREEMENT_STATUS_META: Record<WebAgreementStatus, AgreementStatusMeta> = {
  SUBMITTED: {
    label: "Awaiting participant",
    // The state a row is BORN in: the ERM sent it, the participant hasn't
    // sent anything back yet.
    meaning:
      "The ERM created the agreement and emailed the participant. The participant has not filled or signed it yet.",
    blockedOn: "Participant",
    nextAction: "Fill the agreement and sign.",
    stage: "with_participant",
    step: 1,
    tone: "waiting",
  },

  REVISION_REQUESTED: {
    label: "Changes requested",
    meaning:
      "The ERM sent the agreement back to the participant for corrections — either specific sections, the signature, or a document re-upload.",
    blockedOn: "Participant",
    nextAction: "Make the requested corrections and re-sign.",
    stage: "with_participant",
    step: 1,
    tone: "attention",
  },

  VERIFIED: {
    // Set the instant the participant signs; nobody has verified anything
    // yet. describeStatus refines it with consultantCopyReleased.
    label: "Signed by participant",
    meaning:
      "The participant filled the agreement and signed it. Their signature, IP and timestamps are captured. Nobody at Sage has reviewed it yet.",
    blockedOn: "ERM",
    nextAction: "Review the agreement, then verify it or request changes.",
    stage: "with_erm",
    step: 2,
    tone: "action",
  },

  CANCELLED: {
    label: "Cancelled",
    meaning: "The ERM cancelled the agreement. This is final — there is no way back.",
    blockedOn: null,
    nextAction: null,
    stage: "closed",
    step: null,
    tone: "neutral",
  },

  // ── Later ──────────────────────────────────────────────────────
  // Internal approval and the countersign come later. Defined so a row in
  // one of these still renders something honest; hidden from filters.

  AWAITING_APPROVALS: {
    label: "In approval",
    meaning:
      "The ERM routed the agreement to its required approvers. It is waiting on their decision.",
    blockedOn: "Manager",
    nextAction: "Approve, or decline with a note.",
    stage: "with_approvers",
    step: 3,
    tone: "review",
    later: true,
  },

  APPROVAL_REVISION_REQUESTED: {
    label: "Declined by approver",
    meaning:
      "An approver declined their gate with a note. This sits with the ERM, not the participant — the participant is not notified and cannot act.",
    blockedOn: "ERM",
    nextAction:
      "Address the note, then re-send for approval or bounce it to the participant.",
    stage: "with_erm",
    step: 3,
    tone: "attention",
    later: true,
  },

  READY_TO_SIGN: {
    label: "Ready to countersign",
    meaning:
      "Every required approval is in. The agreement is waiting on the ERM's countersignature.",
    blockedOn: "ERM",
    nextAction: "Countersign to execute the agreement.",
    stage: "with_erm",
    step: 4,
    tone: "ready",
    later: true,
  },

  COMPLETED: {
    label: "Fully executed",
    meaning:
      "The ERM countersigned. The agreement is executed for the current phase.",
    blockedOn: null,
    nextAction: null,
    stage: "executed",
    step: 5,
    tone: "done",
    later: true,
  },
};

/** Every status a live website agreement can actually be in — drives filter chips. */
export const LIVE_STATUSES: WebAgreementStatus[] = [
  "SUBMITTED",
  "REVISION_REQUESTED",
  "VERIFIED",
  "CANCELLED",
];

export interface StatusContext {
  /**
   * Whether the ERM has verified the signed agreement. VERIFIED hides two
   * different situations behind one enum value and this flag is the only
   * thing that separates them:
   *
   *   released = false -> signed by the participant; the ERM is checking it
   *   released = true  -> verified by the ERM
   *
   * When it is absent the label stays at the unrefined "Signed by
   * participant".
   */
  consultantCopyReleased?: boolean | null;
  /** 1 or 2. Phase 2 adds the Accounts approval gate (later). */
  phase?: number | null;
}

/**
 * Resolve a status to its display metadata, refined by whatever context the
 * caller has. Always returns something — an unknown status degrades to a
 * readable title-cased form.
 */
export function describeStatus(
  status: WebAgreementStatus | string | null | undefined,
  ctx: StatusContext = {},
): AgreementStatusMeta {
  const base = status
    ? AGREEMENT_STATUS_META[status as WebAgreementStatus]
    : undefined;

  if (!base) {
    return {
      label: humanize(status),
      meaning: "Unrecognized status — there is no definition for it.",
      blockedOn: "Operator",
      nextAction: null,
      stage: "stuck",
      step: null,
      tone: "danger",
    };
  }

  // The hidden sub-state inside VERIFIED.
  if (status === "VERIFIED" && ctx.consultantCopyReleased) {
    return {
      ...base,
      label: "Verified",
      meaning:
        "The participant signed and the ERM checked and verified the agreement. Internal approval comes next.",
      blockedOn: null,
      nextAction: null,
      stage: "with_approvers",
      step: 3,
      tone: "done",
    };
  }

  // Phase 2 opens a second approval gate.
  if (status === "AWAITING_APPROVALS" && ctx.phase != null && ctx.phase >= 2) {
    return { ...base, blockedOn: "Manager + Accounts" };
  }

  return base;
}

/** Convenience: just the label. */
export function statusLabel(
  status: WebAgreementStatus | string | null | undefined,
  ctx: StatusContext = {},
): string {
  return describeStatus(status, ctx).label;
}

function humanize(raw: string | null | undefined): string {
  if (!raw) return "Unknown";
  const s = raw.replace(/_/g, " ").toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}
