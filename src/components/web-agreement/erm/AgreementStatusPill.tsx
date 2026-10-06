"use client";

import type { WebAgreementStatus } from "@/lib/api";
import {
  describeStatus,
  TONE_CLASSES,
  type StatusContext,
} from "@/lib/web-agreement-status";

/**
 * The website agreement's copy of the console's status badge
 * (src/components/agreement-erm/AgreementStatusPill.tsx), reading the
 * website's own status vocabulary. All wording and colour come from
 * src/lib/web-agreement-status.ts — do not add labels here.
 *
 * Pass whatever context you have. On list rows that only carry a status the
 * pill shows the honest unrefined label; where consultantCopyReleased is
 * available, VERIFIED resolves to the sharper one ("Verified" rather than
 * "Signed by participant").
 */
export default function AgreementStatusPill({
  status,
  size = "sm",
  context,
  showTooltip = true,
}: {
  status: WebAgreementStatus;
  size?: "sm" | "xs";
  context?: StatusContext;
  /** Native tooltip explaining the state. Off for dense grids. */
  showTooltip?: boolean;
}) {
  const meta = describeStatus(status, context);
  const pad = size === "xs" ? "px-2 py-0.5 text-[10px]" : "px-2.5 py-0.5 text-[11px]";
  const tip = meta.blockedOn
    ? `${meta.meaning} Next: ${meta.blockedOn} — ${meta.nextAction ?? ""}`.trim()
    : meta.meaning;

  return (
    <span
      title={showTooltip ? tip : undefined}
      className={`inline-flex items-center whitespace-nowrap rounded-full font-bold ${pad} ${TONE_CLASSES[meta.tone]}`}
    >
      {meta.label}
    </span>
  );
}

/**
 * Status pill plus a muted "· waiting on X" suffix. Use where the reader is
 * triaging a queue and needs to know whose desk the row is on.
 */
export function AgreementStatusWithOwner({
  status,
  context,
  size = "sm",
}: {
  status: WebAgreementStatus;
  context?: StatusContext;
  size?: "sm" | "xs";
}) {
  const meta = describeStatus(status, context);
  return (
    <span className="inline-flex items-center gap-1.5 flex-wrap">
      <AgreementStatusPill status={status} size={size} context={context} />
      {meta.blockedOn && (
        <span className="text-[10px] font-semibold text-gray-400">
          waiting on {meta.blockedOn}
        </span>
      )}
    </span>
  );
}
