"use client";

import { useState } from "react";

import { reviewFinanceCheck, type FinanceCheckRow } from "@/lib/api";
import { moneyFmt } from "./FinanceParts";

export function FinanceChecksTab({
  checks,
  onRefresh,
}: {
  checks: FinanceCheckRow[];
  onRefresh: () => Promise<void>;
}) {
  const [busy, setBusy] = useState<number | null>(null);
  const [openImage, setOpenImage] = useState<string | null>(null);

  const review = async (id: number, status: "APPROVED" | "REJECTED") => {
    setBusy(id);
    try {
      await reviewFinanceCheck(id, status);
      await onRefresh();
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Check soft-copies</h1>
      <p className="text-sm text-gray-500">
        Finance and authorised operations admins are the only roles permitted
        to view un-redacted check images. Approve to record receipt, reject to
        request a re-upload from the participant.
      </p>
      <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden">
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
                <tr key={c.id}>
                  <td className="px-4 py-2">
                    <div className="font-medium text-gray-900">
                      {c.participantName ?? "--"}
                    </div>
                    <div className="font-mono text-[10px] text-gray-400">
                      {c.participantId ?? "--"}
                    </div>
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-gray-700">
                    {c.checkNumber ?? "--"}
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
                  </td>
                  <td className="px-4 py-2">
                    {c.fileUrl ? (
                      <button
                        onClick={() => setOpenImage(c.fileUrl)}
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
                        onClick={() => review(c.id, "REJECTED")}
                        disabled={busy === c.id || c.reviewStatus === "REJECTED"}
                        className="px-2 py-1 rounded-md text-[10px] font-bold bg-red-700 text-white hover:bg-red-800 disabled:opacity-50 cursor-pointer"
                      >
                        Reject
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {openImage && (
        <div
          className="fixed inset-0 z-40 flex items-center justify-center bg-black/70 p-4"
          onClick={() => setOpenImage(null)}
        >
          <div
            className="bg-white rounded-2xl shadow-xl max-w-3xl w-full p-4"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-3">
              <p className="text-xs font-semibold text-gray-700">
                Check image (unmasked -- finance view)
              </p>
              <button
                onClick={() => setOpenImage(null)}
                className="text-xs text-gray-500 hover:text-red-700 cursor-pointer"
              >
                Close
              </button>
            </div>
            {openImage.match(/\.(png|jpe?g|gif|webp)$/i) ? (
              /* eslint-disable-next-line @next/next/no-img-element */
              <img src={openImage} alt="Check image" className="w-full rounded-lg" />
            ) : (
              <a
                href={openImage}
                target="_blank"
                rel="noreferrer"
                className="text-sm font-semibold text-sage-navy hover:underline"
              >
                Open in new tab →
              </a>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
