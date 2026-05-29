"use client";

import { useEffect, useState } from "react";
import { Download, IndianRupee, Loader2 } from "lucide-react";

import {
  downloadAdminCsv,
  getRevenueSummary,
  getRevenueTransactions,
  type RevenueSummary,
  type RevenueTransaction,
} from "@/lib/api";

function inr(n: number | null | undefined): string {
  if (n == null) return "—";
  return n.toLocaleString("en-IN", { style: "currency", currency: "INR" });
}

export function AdminRevenueTab() {
  const [summary, setSummary] = useState<RevenueSummary | null>(null);
  const [txs, setTxs] = useState<RevenueTransaction[]>([]);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    Promise.all([getRevenueSummary(), getRevenueTransactions()])
      .then(([s, t]) => {
        if (cancelled) return;
        setSummary(s);
        setTxs(t ?? []);
      })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "Couldn't load"); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  const exportCsv = async () => {
    setExporting(true);
    try {
      await downloadAdminCsv("revenue");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Export failed");
    } finally {
      setExporting(false);
    }
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-5 gap-4 flex-wrap">
        <h1 className="text-2xl font-bold text-zinc-900">Revenue</h1>
        <button
          onClick={exportCsv}
          disabled={exporting}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
        >
          {exporting ? <Loader2 size={12} className="animate-spin" /> : <Download size={12} />}
          Export CSV
        </button>
      </div>

      {error && (
        <div className="mb-4 px-4 py-2.5 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
          {error}
        </div>
      )}

      {loading ? (
        <div className="text-center py-10">
          <Loader2 size={20} className="animate-spin text-sage-navy inline" />
        </div>
      ) : (
        <>
          {summary && (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-6">
              <StatCard label="Total Revenue" value={inr(summary.totalRevenue)} />
              <StatCard label="This Month" value={inr(summary.revenueThisMonth)} accent="emerald" />
              <StatCard label="Last Month" value={inr(summary.revenueLastMonth)} />
              <StatCard label="Avg. Order" value={inr(summary.avgOrderValue)} />
            </div>
          )}

          {summary && summary.topCoursesByRevenue.length > 0 && (
            <div className="mb-6">
              <h2 className="text-sm font-bold text-zinc-700 mb-3">Top courses by revenue</h2>
              <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-zinc-200 text-zinc-600">
                      <th className="text-left px-4 py-3 font-medium">Course</th>
                      <th className="text-left px-4 py-3 font-medium">Type</th>
                      <th className="text-right px-4 py-3 font-medium">Enrollments</th>
                      <th className="text-right px-4 py-3 font-medium">Revenue</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary.topCoursesByRevenue.map((c) => (
                      <tr key={c.courseId} className="border-b border-zinc-100">
                        <td className="px-4 py-3 text-zinc-900">{c.courseTitle}</td>
                        <td className="px-4 py-3 text-xs text-zinc-500">{c.type}</td>
                        <td className="px-4 py-3 text-right tabular-nums text-zinc-700">{c.enrollments}</td>
                        <td className="px-4 py-3 text-right tabular-nums font-semibold text-sage-navy">{inr(c.revenue)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          <h2 className="text-sm font-bold text-zinc-700 mb-3">Transactions ({txs.length})</h2>
          <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-zinc-200 text-zinc-600">
                    <th className="text-left px-4 py-3 font-medium">Student</th>
                    <th className="text-right px-4 py-3 font-medium">Amount</th>
                    <th className="text-left px-4 py-3 font-medium">Status</th>
                    <th className="text-left px-4 py-3 font-medium">Payment ID</th>
                    <th className="text-left px-4 py-3 font-medium">Date</th>
                  </tr>
                </thead>
                <tbody>
                  {txs.length === 0 ? (
                    <tr>
                      <td colSpan={5} className="px-4 py-6 text-center text-zinc-400 italic">
                        No transactions yet.
                      </td>
                    </tr>
                  ) : (
                    txs.map((t) => (
                      <tr key={t.id} className="border-b border-zinc-100">
                        <td className="px-4 py-3">
                          <div className="font-medium text-zinc-900">{t.studentName ?? "—"}</div>
                          <div className="text-xs text-zinc-500">{t.studentEmail ?? ""}</div>
                        </td>
                        <td className="px-4 py-3 text-right tabular-nums">{inr(t.amount)}</td>
                        <td className="px-4 py-3 text-xs text-zinc-600">{t.status ?? "—"}</td>
                        <td className="px-4 py-3 font-mono text-xs text-zinc-500">{t.razorpayPaymentId ?? "—"}</td>
                        <td className="px-4 py-3 text-zinc-500 text-xs">
                          {t.createdAt ? new Date(t.createdAt).toLocaleDateString("en-IN") : "—"}
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function StatCard({ label, value, accent }: { label: string; value: string; accent?: "emerald" }) {
  const accentClass = accent === "emerald" ? "text-emerald-700" : "text-sage-navy";
  return (
    <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-4">
      <div className="flex items-center gap-2 mb-2">
        <IndianRupee size={14} className="text-zinc-400" />
        <span className="text-[11px] uppercase tracking-wider font-semibold text-zinc-500">{label}</span>
      </div>
      <p className={"text-xl font-bold " + accentClass}>{value}</p>
    </div>
  );
}
