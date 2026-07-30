import type { ApprovalDecision, ConsultantApplicationStatus } from "@/lib/api";

/**
 * Single source of truth for what a consultant-agreement status MEANS and
 * what it is called.
 *
 * Before this module every surface invented its own wording, so one row was
 * simultaneously "Verified" (status pill), "Ready for your review" (detail
 * page action bar), "In revision" (approver filter tab) and "Sent for
 * verification" (consultant portal). The worst offender was VERIFIED: the
 * backend sets it the moment the CONSULTANT SIGNS -- nobody at Sage verifies
 * anything to reach it -- yet every operator surface called it "Verified",
 * which reads as staff having checked the agreement.
 *
 *   ConsultantApplicationService.java:1242
 *     //   VERIFIED  -- consultant signed; awaiting ERM review
 *
 * Labels here are OPERATOR-facing (super-admin / ERM / approver consoles).
 * The consultant sees a softer vocabulary, owned by the backend at
 * ConsultantApplicationController#ConsultantAgreementSummary.from.
 *
 * Rule of thumb for every label below: name what HAPPENED and who owes the
 * next move, never the internal enum spelling.
 */

/** Which desk the agreement is sitting on right now. */
export type AgreementStage =
  | "with_consultant"
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
 * Solid fill for the same tone — used by the stage meter and stat tiles.
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
  | "Consultant"
  | "ERM"
  | "Manager"
  | "Manager + Accounts"
  | "Operator"
  | null;

export interface AgreementStatusMeta {
  /** Short operator-facing label. Goes in the pill. */
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
   * True when no live code path can produce this value. Retired states are
   * kept so historical rows still render something honest, but they are
   * hidden from filters and flagged in the lifecycle reference.
   */
  retired?: boolean;
  /** Why it is retired / how a row could still be sitting in it. */
  retiredNote?: string;
}

/**
 * The five steps of the happy path. Everything else is a loop back to one of
 * these or a terminal off-ramp.
 */
export const AGREEMENT_PIPELINE: ReadonlyArray<{
  step: number;
  title: string;
  owner: string;
  summary: string;
  statuses: ConsultantApplicationStatus[];
}> = [
  {
    step: 1,
    title: "Sent to consultant",
    owner: "Consultant",
    summary:
      "The ERM created the agreement and emailed the fill invitation. The consultant fills the wizard and signs. The access link lapses after 7 days and needs a resend.",
    statuses: ["SUBMITTED", "REVISION_REQUESTED"],
  },
  {
    step: 2,
    title: "Signed by consultant",
    owner: "ERM",
    summary:
      "The consultant's signature is captured. The ERM reviews it, releases the consultant version, then routes the agreement to approvers.",
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

export const AGREEMENT_STATUS_META: Record<
  ConsultantApplicationStatus,
  AgreementStatusMeta
> = {
  SUBMITTED: {
    label: "Awaiting consultant",
    // Named for the ERM's submit, not the consultant's -- this is the state a
    // row is BORN in (ConsultantApplicationService.java:248), so "Submitted"
    // read as if the consultant had already sent something back.
    meaning:
      "The ERM created the agreement and emailed the invitation. The consultant has not filled or signed it yet.",
    blockedOn: "Consultant",
    nextAction: "Fill the wizard and sign.",
    stage: "with_consultant",
    step: 1,
    tone: "waiting",
  },

  REVISION_REQUESTED: {
    label: "Changes requested",
    meaning:
      "The ERM sent the agreement back to the consultant for corrections — either specific sections, the signature, or a document re-upload.",
    blockedOn: "Consultant",
    nextAction: "Make the requested corrections and re-sign.",
    stage: "with_consultant",
    step: 1,
    tone: "attention",
  },

  VERIFIED: {
    // The headline fix. The backend sets this the instant the consultant
    // signs; no staff member has verified anything at this point.
    label: "Signed by consultant",
    meaning:
      "The consultant filled the agreement and signed it. Their signature, IP and timestamps are captured. Nobody at Sage has reviewed it yet.",
    blockedOn: "ERM",
    nextAction:
      "Release the consultant version, then send the agreement for approval.",
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
      "An approver declined their gate with a note. This sits with the ERM, not the consultant — the consultant is not notified and cannot act.",
    blockedOn: "ERM",
    nextAction:
      "Address the note, then re-send for approval or bounce it to the consultant.",
    stage: "with_erm",
    step: 3,
    tone: "attention",
  },

  READY_TO_SIGN: {
    // "Ready to sign" never said WHO signs. The consultant signed long ago;
    // this is the ERM's countersignature.
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

  // ── Retired states ─────────────────────────────────────────────
  // Kept so historical rows render something truthful, hidden from filters.

  DRAFT: {
    label: "Draft",
    meaning:
      "Never used. Agreements are created directly in “Awaiting consultant”; nothing in the system writes this value.",
    blockedOn: null,
    nextAction: null,
    stage: "closed",
    step: null,
    tone: "neutral",
    retired: true,
    retiredNote:
      "No code path produces it. The lifecycle starts at “Awaiting consultant”.",
  },

  UPDATED: {
    label: "Edited by ERM — stalled",
    meaning:
      "An ERM edited the agreement's contact details through the legacy update endpoint. The consultant is locked out of a row in this state and no console button can move it forward.",
    blockedOn: "Operator",
    nextAction: "Needs a manual fix — the consultant cannot proceed.",
    stage: "stuck",
    step: null,
    tone: "danger",
    retired: true,
    retiredNote:
      "Produced only by a legacy endpoint the console never calls. If you see one, it is stuck.",
  },

  SIGNED: {
    label: "Signed — filing failed",
    meaning:
      "A legacy sign path completed but the final PDF failed to generate, leaving the row stranded. No endpoint accepts an agreement in this state.",
    blockedOn: "Operator",
    nextAction: "Needs a manual fix — cancel and revision are both refused.",
    stage: "stuck",
    step: null,
    tone: "danger",
    retired: true,
    retiredNote:
      "Transient in the legacy flow; it only persists when PDF generation threw.",
  },

  EXPIRED: {
    label: "Expired",
    meaning:
      "Retired. Agreements no longer expire — only the consultant's 7-day access link does, which is shown separately as “Link expired”.",
    blockedOn: null,
    nextAction: null,
    stage: "closed",
    step: null,
    tone: "neutral",
    retired: true,
    retiredNote:
      "The expiry job was removed and historical rows were migrated back on boot.",
  },
};

/** Every status a live agreement can actually be in — drives filter chips. */
export const LIVE_STATUSES: ConsultantApplicationStatus[] = (
  Object.keys(AGREEMENT_STATUS_META) as ConsultantApplicationStatus[]
).filter((s) => !AGREEMENT_STATUS_META[s].retired);

export const STAGE_META: Record<
  AgreementStage,
  { label: string; tone: StatusTone; blurb: string }
> = {
  with_consultant: {
    label: "With consultant",
    tone: "waiting",
    blurb: "Waiting on the consultant to fill or re-sign.",
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
 * their gate. "Revision" implied the consultant had been sent something to fix,
 * which is not what happens — the ball goes to the ERM.
 *
 * This lives here because four surfaces render the same gate — the ERM
 * dashboard board, the consultants list directly beneath it, the approver queue
 * and the admin console — and they had drifted into two different names and two
 * different palettes for one fact.
 */
export const APPROVAL_DECISION_META: Record<
  ApprovalDecision,
  { label: string; tone: StatusTone }
> = {
  APPROVED: { label: "Approved", tone: "done" },
  PENDING: { label: "Pending", tone: "waiting" },
  REVISION_REQUESTED: { label: "Declined", tone: "danger" },
};

export interface StatusContext {
  /**
   * Whether the ERM has released the consultant version. VERIFIED hides two
   * materially different situations behind one enum value and this flag is
   * the only thing that separates them:
   *
   *   released = false -> the ERM still has to approve the consultant version
   *   released = true  -> "Send for approval" is unlocked
   *
   * Absent on list DTOs (AgreementSummaryDto does not carry it), in which
   * case the label stays at the honest, unrefined "Signed by consultant".
   */
  consultantCopyReleased?: boolean | null;
  /** 1 or 2. Phase 2 adds the Accounts approval gate. */
  phase?: number | null;
  /** Derived 7-day link lapse. Only ever meaningful while awaiting the consultant. */
  linkExpired?: boolean | null;
}

/**
 * Resolve a status to its display metadata, refined by whatever context the
 * caller has. Always returns something — an unknown status degrades to a
 * readable title-cased form rather than silently rendering "Draft".
 */
export function describeStatus(
  status: ConsultantApplicationStatus | string | null | undefined,
  ctx: StatusContext = {},
): AgreementStatusMeta {
  const base = status
    ? AGREEMENT_STATUS_META[status as ConsultantApplicationStatus]
    : undefined;

  if (!base) {
    return {
      label: humanize(status),
      meaning: "Unrecognized status — the console has no definition for it.",
      blockedOn: "Operator",
      nextAction: null,
      stage: "stuck",
      step: null,
      tone: "danger",
    };
  }

  // A lapsed invitation link outranks the status: the consultant physically
  // cannot act, so showing "Awaiting consultant" alone would be misleading.
  if (status === "SUBMITTED" && ctx.linkExpired) {
    return {
      ...base,
      label: "Link expired",
      meaning:
        "The consultant's 7-day access link lapsed before they signed. They cannot open the agreement until it is resent.",
      blockedOn: "ERM",
      nextAction: "Resend the invitation link.",
      tone: "attention",
    };
  }

  // The hidden sub-state inside VERIFIED.
  if (status === "VERIFIED" && ctx.consultantCopyReleased != null) {
    return ctx.consultantCopyReleased
      ? {
          ...base,
          label: "Signed — ready to route",
          meaning:
            "The consultant signed and the ERM released their copy. The agreement is ready to go to approvers.",
          nextAction: "Send for approval.",
        }
      : {
          ...base,
          label: "Signed — awaiting ERM review",
          meaning:
            "The consultant signed. The ERM has not released the consultant version yet, so approvals cannot start.",
          nextAction: "Review and release the consultant version.",
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
  status: ConsultantApplicationStatus | string | null | undefined,
  ctx: StatusContext = {},
): string {
  return describeStatus(status, ctx).label;
}

function humanize(raw: string | null | undefined): string {
  if (!raw) return "Unknown";
  const s = raw.replace(/_/g, " ").toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}
