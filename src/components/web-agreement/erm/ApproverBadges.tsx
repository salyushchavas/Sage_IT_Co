"use client";

import type { WebAgreementApproval, WebApprovalRole } from "@/lib/api";

/**
 * One badge per approval gate of the CURRENT round (Manager, then Accounts),
 * shown in the ERM detail's action bar so each gate's state is visible on its
 * own. The website's copy of the console's ApproverBadges
 * (ConsultantDetailView.tsx), same wording and colours.
 *
 * The rows come oldest first and two gates of one send can share a
 * created_at, so the order is set here, not taken from the list.
 */
export default function ApproverBadges({ approvals }: { approvals: WebAgreementApproval[] }) {
  if (!approvals || approvals.length === 0) return null;
  const maxRound = approvals.reduce((m, a) => Math.max(m, a.round), 0);
  const current = approvals
    .filter((a) => a.round === maxRound)
    .slice()
    .sort((a, b) => {
      const order: WebApprovalRole[] = ["MANAGER", "ACCOUNTS"];
      return order.indexOf(a.role) - order.indexOf(b.role);
    });
  if (current.length === 0) return null;
  return (
    <div className="flex flex-wrap gap-2">
      {current.map((a) => {
        const label = a.role === "ACCOUNTS" ? "Accounts" : "Manager";
        const tone =
          a.status === "APPROVED"
            ? "bg-emerald-50 border-emerald-200 text-emerald-700"
            : a.status === "REVISION_REQUESTED"
              ? "bg-orange-50 border-orange-200 text-orange-700"
              : "bg-gray-50 border-gray-200 text-gray-600";
        const word =
          a.status === "APPROVED"
            ? "Approved"
            : a.status === "REVISION_REQUESTED"
              ? "Revision requested"
              : "Pending";
        return (
          <span
            key={a.id}
            className={
              "inline-flex flex-wrap items-center gap-1.5 max-w-full rounded-full border px-2.5 py-1 text-[11px] font-semibold "
              + tone
            }
          >
            {label}: {word}
            {a.decidedByName && (
              <span className="font-normal text-gray-500">· {a.decidedByName}</span>
            )}
          </span>
        );
      })}
    </div>
  );
}
