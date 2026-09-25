"use client";

import { Fragment, useState } from "react";

import {
  openFinanceCheckImage,
  revealFinanceCheckNumber,
  reviewFinanceCheck,
  type FinanceCheckRow,
} from "@/lib/api";
import { moneyFmt } from "./FinanceParts";

/**
 * Checklist 2.4: check numbers are masked (••••1234); Finance reveals the
 * full number or opens the image through audited calls. Rejecting needs a
 * reason, which is emailed to the participant with a re-upload link.
 */
export function FinanceChecksTab({
  checks,
  onRefresh,
}: {
  checks: FinanceCheckRow[];
  onRefresh: () => Promise<void>;
}) {
  const [busy, setBusy] = useState<number | null>(null);
  const [error, setError] = useState("");
  const [revealed, setRevealed] = useState<Record<number, string>>({});
  const [rejectFor, setRejectFor] = useState<number | null>(null);
  const [reason, setReason] = useState("");

  const review = async (id: number, status: "APPROVED" | "REJECTED") => {
    setBusy(id);
    setError("");
    try {
      await reviewFinanceCheck(id, status, status === "REJECTED" ? reason.trim() : "");
      setRejectFor(null);
      setReason("");
      await onRefresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't save the review");
    } finally {
      setBusy(null);
    }
  };

  const reveal = async (id: number) => {
    setError("");
    try {
      const n = await revealFinanceCheckNumber(id);
      setRevealed((prev) => ({ ...prev, [id]: n }));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't show the number");
    }
  };

  const view = async (id: number) => {
    setError("");
    try {
      await openFinanceCheckImage(id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't open the image");
    }
  };

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Check soft-copies</h1>
      <p className="text-sm text-gray-500">
        Only Finance can view check images and full check numbers; every view
        is recorded. Approve to record receipt, or reject with a reason to ask
        the participant for a new copy.
      </p>
      {error && <p className="text-sm text-red-700">{error}</p>}
      <div className="rounded-2xl border border-gray-100 bg-white overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Participant</th>
              <th className="text-left px-4 py-2">Check #</th>
              <th className="text-left px-4 py-2">Amount</th>
              <th className="text-left px-4 py-2">Date</th>
              <th className="text-left px-4 py-2">Status</th>
              <th className="text-left px-4 py-2">File</th>
              <th className="text-right px-4 py-2">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {checks.length === 0 ? (
              <tr>
                <td
                  colSpan={7}
                  className="px-4 py-6 text-center text-sm text-gray-400 italic"
                >
                  No check copies uploaded yet.
                </td>
              </tr>
            ) : (
              checks.map((c) => (
                <Fragment key={c.id}>
                  <tr>
                    <td className="px-4 py-2">
                      <div className="font-medium text-gray-900">
                        {c.participantName ?? "--"}
                      </div>
                      <div className="font-mono text-[10px] text-gray-400">
                        {c.participantId ?? "--"}
                      </div>
                    </td>
                    <td className="px-4 py-2 font-mono text-xs text-gray-700">
                      {revealed[c.id] ?? c.checkNumber ?? "--"}
                      {!revealed[c.id] && c.checkNumber && (
                        <button
                          onClick={() => reveal(c.id)}
                          className="ml-2 font-sans text-[10px] font-semibold text-sage-navy hover:text-sage-navy-deep cursor-pointer"
                        >
                          Show
                        </button>
                      )}
                      {c.replacesCheckId && (
                        <div className="font-sans text-[10px] text-gray-400">
                          replaces #{c.replacesCheckId}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-2 text-gray-700">
                      {moneyFmt(c.amount)}
                    </td>
                    <td className="px-4 py-2 font-mono text-xs text-gray-700">
                      {c.checkDate ?? "--"}
                    </td>
                    <td className="px-4 py-2">
                      <span
                        className={
                          "px-2 py-0.5 rounded-full text-[10px] font-bold " +
                          (c.reviewStatus === "APPROVED"
                            ? "bg-emerald-50 text-emerald-700"
                            : c.reviewStatus === "REJECTED"
                              ? "bg-red-50 text-red-700"
                              : "bg-amber-50 text-amber-700")
                        }
                      >
                        {c.reviewStatus}
                      </span>
                      {c.reviewStatus === "REJECTED" && c.reviewNotes && (
                        <div className="mt-1 text-[11px] text-red-700 italic">{c.reviewNotes}</div>
                      )}
                    </td>
                    <td className="px-4 py-2">
                      {c.hasFile ? (
                        <button
                          onClick={() => view(c.id)}
                          className="text-xs font-semibold text-sage-navy hover:text-sage-navy-deep cursor-pointer"
                        >
                          View
                        </button>
                      ) : (
                        <span className="text-xs text-gray-400">--</span>
                      )}
                    </td>
                    <td className="px-4 py-2 text-right">
                      <div className="inline-flex gap-1.5">
                        <button
                          onClick={() => review(c.id, "APPROVED")}
                          disabled={busy === c.id || c.reviewStatus === "APPROVED"}
                          className="px-2 py-1 rounded-md text-[10px] font-bold bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50 cursor-pointer"
                        >
                          Approve
                        </button>
                        <button
                          onClick={() => {
                            setRejectFor(c.id);
                            setReason("");
                          }}
                          disabled={busy === c.id || c.reviewStatus === "REJECTED"}
                          className="px-2 py-1 rounded-md text-[10px] font-bold bg-red-700 text-white hover:bg-red-800 disabled:opacity-50 cursor-pointer"
                        >
                          Reject
                        </button>
                      </div>
                    </td>
                  </tr>
                  {rejectFor === c.id && (
                    <tr>
                      <td colSpan={7} className="px-4 pb-3">
                        <div className="flex flex-col sm:flex-row gap-1.5">
                          <input
                            value={reason}
                            onChange={(e) => setReason(e.target.value)}
                            maxLength={1000}
                            placeholder="What's wrong with this copy (emailed to the participant)"
                            className="flex-1 px-2 py-1.5 text-xs rounded-md border border-gray-200"
                          />
                          <div className="flex gap-1.5">
                            <button
                              onClick={() => review(c.id, "REJECTED")}
                              disabled={busy === c.id || !reason.trim()}
                              className="px-2 py-1 rounded-md text-[10px] font-bold bg-red-700 text-white hover:bg-red-800 disabled:opacity-50 cursor-pointer"
                            >
                              Reject and email
                            </button>
                            <button
                              onClick={() => {
                                setRejectFor(null);
                                setReason("");
                              }}
                              className="px-2 py-1 rounded-md text-[10px] font-bold bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy cursor-pointer"
                            >
                              Cancel
                            </button>
                          </div>
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
