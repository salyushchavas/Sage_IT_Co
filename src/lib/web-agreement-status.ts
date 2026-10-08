import type { WebAgreementStatus, WebApprovalDecision } from "@/lib/api";

export type { WebApprovalDecision };

/**
 * The website agreement's copy of src/lib/agreement-status.ts, under the
 * same export names, so the website's copies of the console views only
 * change their import path. Kept separate so a change here never reaches
 * the console.
 *
 * Single source of truth for what a website-agreement status MEANS and what
 * it is called on the staff screens (ERM list and detail, the approval
 * board, the approver dashboard and the System Admin's Agreements tab).
 *
 * VERIFIED is set the moment the PARTICIPANT SIGNS; nobody at Sage has
 * checked anything yet. The ERM's "Verify" action then sets
 * consultantCopyReleased, which is the only thing separating "Signed by
 * participant" (the ERM is checking it) from "Verified" (checked, ready to
 * send for approval).
 *
 * Labels here are STAFF-facing (ERM / Operations / approvers). The
 * participant sees the softer wording MasterAgreementService sends with the
 * dashboard step.
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

/**
 * Solid fill for the same tone — used by the stage meter, stat tiles, the
 * filter chips' dot and a card's accent edge.
 *
 * These are the 600 steps, not the 400/500 ones the pills imply. The five
 * hues that share the stage meter (amber / sky / violet / emerald / red) were
 * checked as an adjacent set and pass the lightness band, chroma floor,
 * deuteranopia + tritanopia separation, the normal-vision floor and 3:1
 * contrast against a light surface. The lighter steps failed CVD separation
 * and contrast, so do not "soften" these without re-validating.
 *
 * Gray is deliberately outside that set: it marks the closed bucket, which is
 * always directly labelled and never has to be told apart by hue alone.
 */
export const TONE_ACCENT: Record<StatusTone, string> = {
  neutral: "bg-gray-400",
  waiting: "bg-amber-600",
  action: "bg-sky-600",
  review: "bg-violet-600",
  attention: "bg-orange-600",
  ready: "bg-teal-600",
  done: "bg-emerald-600",
  danger: "bg-red-600",
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
}

/**
 * The five steps of the happy path, for the System Admin's Lifecycle tab.
 * Everything else is a loop back to one of these or a terminal off-ramp.
 * The console's copy (AGREEMENT_PIPELINE) with the website's wording: there
 * is no invitation email and no access link to lapse, and the ERM's
 * "Verify" is the console's "release the consultant version".
 */
export const WEB_AGREEMENT_PIPELINE: ReadonlyArray<{
  step: number;
  title: string;
  owner: string;
  summary: string;
  statuses: WebAgreementStatus[];
}> = [
  {
    step: 1,
    title: "Sent to participant",
    owner: "Participant",
    summary:
      "The ERM created the agreement. The participant fills it from their dashboard and signs.",
    statuses: ["SUBMITTED", "REVISION_REQUESTED"],
  },
  {
    step: 2,
    title: "Signed by participant",
    owner: "ERM",
    summary:
      "The participant's signature is captured. The ERM reviews it, verifies it, then routes the agreement to approvers.",
    statuses: ["VERIFIED"],
  },
  {
    step: 3,
    title: "Internal approval",
    owner: "Manager / Accounts",
    summary:
      "Phase 1 needs Manager approval. Phase 2 needs Manager and Accounts. Any approver can decline with a note, which sends it back to the ERM.",
    statuses: ["AWAITING_APPROVALS", "APPROVAL_REVISION_REQUESTED"],
  },
  {
    step: 4,
    title: "Countersignature",
    owner: "ERM",
    summary:
      "Every required approval is in. The ERM countersigns with name, title and signature to execute the agreement.",
    statuses: ["READY_TO_SIGN"],
  },
  {
    step: 5,
    title: "Executed",
    owner: "—",
    summary:
      "The ERM countersigned. The agreement is executed for the current phase and can be reopened for Phase 2.",
    statuses: ["COMPLETED"],
  },
];

export const AGREEMENT_STATUS_META: Record<WebAgreementStatus, AgreementStatusMeta> = {
  SUBMITTED: {
    label: "Awaiting participant",
    // The state a row is BORN in: the ERM sent it, the participant hasn't
    // sent anything back yet.
    meaning:
      "The ERM created the agreement; the participant fills it from their dashboard. They have not filled or signed it yet.",
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

  AWAITING_APPROVALS: {
    label: "In approval",
    meaning:
      "The ERM routed the agreement to its required approvers. It is waiting on their decision.",
    blockedOn: "Manager",
    nextAction: "Approve, or decline with a note.",
    stage: "with_approvers",
    step: 3,
    tone: "review",
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
  },

  READY_TO_SIGN: {
    // The participant signed long ago; this is the ERM's countersignature.
    label: "Ready to countersign",
    meaning:
      "Every required approval is in. The agreement is waiting on the ERM's countersignature.",
    blockedOn: "ERM",
    nextAction: "Countersign to execute the agreement.",
    stage: "with_erm",
    step: 4,
    tone: "ready",
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
};

/** Every status a live website agreement can be in — drives filter chips. */
export const LIVE_STATUSES: WebAgreementStatus[] = [
  "SUBMITTED",
  "REVISION_REQUESTED",
  "VERIFIED",
  "AWAITING_APPROVALS",
  "APPROVAL_REVISION_REQUESTED",
  "READY_TO_SIGN",
  "COMPLETED",
  "CANCELLED",
];

export const STAGE_META: Record<
  AgreementStage,
  { label: string; tone: StatusTone; blurb: string }
> = {
  with_participant: {
    label: "With participant",
    tone: "waiting",
    blurb: "Waiting on the participant to fill or re-sign.",
  },
  with_erm: {
    label: "With ERM",
    tone: "action",
    blurb: "Waiting on the owning ERM to route or countersign.",
  },
  with_approvers: {
    label: "With approvers",
    tone: "review",
    blurb: "Waiting on Manager or Accounts sign-off.",
  },
  executed: {
    label: "Executed",
    tone: "done",
    blurb: "Countersigned and complete for the current phase.",
  },
  closed: { label: "Closed", tone: "neutral", blurb: "Cancelled or retired." },
  stuck: {
    label: "Needs attention",
    tone: "danger",
    blurb: "Stalled in a state no one can move — needs an operator.",
  },
};

/**
 * Per-approver gate decisions (Manager / Accounts), in the same vocabulary as
 * the lifecycle statuses above.
 *
 * REVISION_REQUESTED is "Declined" for exactly the reason
 * APPROVAL_REVISION_REQUESTED is "Declined by approver": the approver refused
 * their gate. The ball goes to the ERM, not the participant.
 *
 * The approval board, the agreements list, the approver dashboard and the
 * System Admin's Agreements tab all render the same gate from here.
 */
export const APPROVAL_DECISION_META: Record<
  WebApprovalDecision,
  { label: string; tone: StatusTone }
> = {
  APPROVED: { label: "Approved", tone: "done" },
  PENDING: { label: "Pending", tone: "waiting" },
  REVISION_REQUESTED: { label: "Declined", tone: "danger" },
};

export interface StatusContext {
  /**
   * Whether the ERM has verified the signed agreement. VERIFIED hides two
   * different situations behind one enum value and this flag is the only
   * thing that separates them:
   *
   *   released = false -> signed by the participant; the ERM is checking it
   *   released = true  -> verified by the ERM; "Send for approval" is next
   *
   * When it is absent the label stays at the unrefined "Signed by
   * participant".
   */
  consultantCopyReleased?: boolean | null;
  /** 1 or 2. Phase 2 adds the Accounts approval gate. */
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

  // The hidden sub-state inside VERIFIED. A verified agreement that has not
  // been sent yet is still on the ERM's desk, as in the console: the ERM's
  // next move is "Send for approval", and it keeps VERIFIED's desk colour.
  if (status === "VERIFIED" && ctx.consultantCopyReleased) {
    return {
      ...base,
      label: "Verified",
      meaning:
        "The participant signed and the ERM checked and verified the agreement. Internal approval comes next.",
      nextAction: "Send for approval.",
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
