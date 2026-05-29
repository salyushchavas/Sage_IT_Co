"use client";

import { useEffect, useState } from "react";

import {
  getFinanceTrackings,
  updateTrackingStatus,
  type FinanceTrackingRow,
} from "@/lib/api";
import { Pill, Spinner } from "./FinanceParts";

export function FinanceTrackingTab() {
  const [rows, setRows] = useState<FinanceTrackingRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<number | null>(null);

  const refresh = async () => setRows(await getFinanceTrackings());

  useEffect(() => {
    let cancelled = false;
    refresh().finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const updateStatus = async (
    id: number,
    status: "RECEIVED" | "EXCEPTION" | "IN_TRANSIT",
  ) => {
    setBusy(id);
    try {
      await updateTrackingStatus(
        id,
        status,
        status === "RECEIVED"
          ? new Date().toISOString().slice(0, 10)
          : undefined,
      );
      await refresh();
    } finally {
      setBusy(null);
    }
  };

  if (loading) return <Spinner />;

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">
        Physical check tracking
      </h1>
      <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Participant</th>
              <th className="text-left px-4 py-2">Check #</th>
              <th className="text-left px-4 py-2">Carrier</th>
              <th className="text-left px-4 py-2">Tracking ID</th>
              <th className="text-left px-4 py-2">Mailed</th>
              <th className="text-left px-4 py-2">Status</th>
              <th className="text-right px-4 py-2">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {rows.length === 0 ? (
              <tr>
                <td
                  colSpan={7}
                  className="px-4 py-6 text-center text-sm text-gray-400 italic"
                >
                  No tracking entries.
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr key={r.id}>
                  <td className="px-4 py-2">
                    <div className="font-medium text-gray-900">
                      {r.participantName ?? "--"}
                    </div>
                    <div className="font-mono text-[10px] text-gray-400">
                      {r.participantId ?? "--"}
                    </div>
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-gray-700">
                    {r.checkNumber ?? "--"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {r.carrier ?? "--"}
                  </td>
                  <td className="px-4 py-2 font-mono text-[10px] text-gray-500">
                    {r.trackingId ?? "--"}
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-gray-700">
                    {r.mailedDate ?? "--"}
                  </td>
                  <td className="px-4 py-2">
                    <Pill>{r.status}</Pill>
                  </td>
                  <td className="px-4 py-2 text-right">
                    <div className="inline-flex gap-1">
                      <button
                        onClick={() => updateStatus(r.id, "RECEIVED")}
                        disabled={busy === r.id}
                        className="px-2 py-1 rounded-md text-[10px] font-bold bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50 cursor-pointer"
                      >
                        Received
                      </button>
                      <button
                        onClick={() => updateStatus(r.id, "EXCEPTION")}
                        disabled={busy === r.id}
                        className="px-2 py-1 rounded-md text-[10px] font-bold bg-red-700 text-white hover:bg-red-800 disabled:opacity-50 cursor-pointer"
                      >
                        Exception
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
