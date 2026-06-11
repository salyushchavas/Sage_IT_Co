"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ClipboardCheck, Loader2 } from "lucide-react";

import {
  fetchApprovalBoard,
  type AgreementApproval,
  type ApprovalBoardItem,
  type ApproverRole,
} from "@/lib/api";
import AgreementStatusPill from "./AgreementStatusPill";

/**
 * 3B — ERM dashboard status boards. Splits the agreements currently in the
 * approval gate into a Phase 1 board and a Phase 2 board, each showing the
 * overall status + the per-approver badges ([Manager], and for Phase 2
 * also [Accounts]) so the ERM sees each gate independently.
 */
export default function ApprovalStatusBoards() {
  const [items, setItems] = useState<ApprovalBoardItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    fetchApprovalBoard()
      .then((data) => {
        if (!cancelled) setItems(data);
      })
      .catch(() => {
        /* non-fatal: the list below still renders */
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) {
    return (
      <div className="mb-6 flex items-center gap-2 text-xs text-gray-400">
        <Loader2 size={14} className="animate-spin" /> Loading approvals…
      </div>
    );
  }
  if (items.length === 0) return null;

  const phase1 = items.filter((i) => (i.application.phase ?? 1) < 2);
  const phase2 = items.filter((i) => (i.application.phase ?? 1) >= 2);

  return (
    <div className="mb-8 grid grid-cols-1 lg:grid-cols-2 gap-4">
      <Board title="Phase 1 approvals" subtitle="Manager gate" items={phase1} />
      <Board title="Phase 2 approvals" subtitle="Manager + Accounts gates" items={phase2} />
    </div>
  );
}

function Board({
  title,
  subtitle,
  items,
}: {
  title: string;
  subtitle: string;
  items: ApprovalBoardItem[];
}) {
  return (
    <section className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
      <header className="px-5 pt-4 pb-3 border-b border-gray-100 flex items-center gap-2">
        <ClipboardCheck size={15} className="text-sage-navy" />
        <div>
          <h3 className="text-sm font-bold text-gray-900">{title}</h3>
          <p className="text-[11px] text-gray-500">{subtitle}</p>
        </div>
        <span className="ml-auto text-[11px] font-semibold text-gray-400">
          {items.length}
        </span>
      </header>
      <div className="divide-y divide-gray-50">
        {items.length === 0 ? (
          <p className="px-5 py-6 text-xs text-gray-400 italic">
            Nothing in the approval gate.
          </p>
        ) : (
          items.map((item) => (
            <Link
              key={item.application.applicationId}
              href={`/agreements/${item.application.applicationId}`}
              className="block px-5 py-3 hover:bg-gray-50/70 transition-colors"
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-sm font-semibold text-gray-900 truncate">
                    {item.application.consultantName
                      || item.application.consultantEmail}
                  </p>
                  <p className="text-[11px] text-gray-500 truncate">
                    {item.application.consultantEmail}
                  </p>
                </div>
                <AgreementStatusPill status={item.application.status} size="xs" />
              </div>
              <div className="mt-2">
                <ApproverBadgeRow approvals={item.approvals} />
              </div>
            </Link>
          ))
        )}
      </div>
    </section>
  );
}

function ApproverBadgeRow({ approvals }: { approvals: AgreementApproval[] }) {
  if (!approvals || approvals.length === 0) return null;
  const maxRound = approvals.reduce((m, a) => Math.max(m, a.round), 0);
  const current = approvals
    .filter((a) => a.round === maxRound)
    .slice()
    .sort((a, b) => {
      const order: ApproverRole[] = ["MANAGER", "ACCOUNTS"];
      return order.indexOf(a.role) - order.indexOf(b.role);
    });
  return (
    <div className="flex flex-wrap gap-1.5">
      {current.map((a) => {
        const label = a.role === "ACCOUNTS" ? "Accounts" : "Manager";
        const tone =
          a.status === "APPROVED"
            ? "bg-emerald-50 text-emerald-700 border-emerald-200"
            : a.status === "REVISION_REQUESTED"
              ? "bg-orange-50 text-orange-700 border-orange-200"
              : "bg-gray-50 text-gray-600 border-gray-200";
        const word =
          a.status === "APPROVED"
            ? "Approved"
            : a.status === "REVISION_REQUESTED"
              ? "Revision"
              : "Pending";
        return (
          <span
            key={a.id}
            className={
              "inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-semibold "
              + tone
            }
          >
            {label}: {word}
          </span>
        );
      })}
    </div>
  );
}
