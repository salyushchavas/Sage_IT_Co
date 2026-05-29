"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Loader2 } from "lucide-react";

import {
  getAdminSalesInquiries,
  getAdminSalesStats,
  type SalesInquiry,
  type SalesStats,
} from "@/lib/api";

const STATUS_STYLES: Record<string, string> = {
  NEW: "bg-amber-100 text-amber-700",
  IN_PROGRESS: "bg-blue-100 text-blue-700",
  QUOTED: "bg-violet-100 text-violet-700",
  CONVERTED: "bg-emerald-100 text-emerald-700",
  CLOSED: "bg-zinc-100 text-zinc-600",
  LOST: "bg-zinc-100 text-zinc-500",
};

export function AdminSalesTab() {
  const [stats, setStats] = useState<SalesStats | null>(null);
  const [rows, setRows] = useState<SalesInquiry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>("ALL");

  useEffect(() => {
    let cancelled = false;
    Promise.all([getAdminSalesInquiries(), getAdminSalesStats()])
      .then(([inq, s]) => {
        if (cancelled) return;
        setRows(inq ?? []);
        setStats(s);
      })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "Couldn't load"); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  const visible = statusFilter === "ALL" ? rows : rows.filter((r) => r.status === statusFilter);

  return (
    <div>
      <h1 className="text-2xl font-bold text-zinc-900 mb-5">Sales inquiries</h1>

      {error && (
        <div className="mb-4 px-4 py-2.5 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">{error}</div>
      )}

      {loading ? (
        <div className="text-center py-10"><Loader2 size={20} className="animate-spin text-sage-navy inline" /></div>
      ) : (
        <>
          {stats && (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-6">
              <StatCard label="Total" value={stats.totalInquiries} />
              <StatCard label="New" value={stats.newCount} accent="amber" />
              <StatCard label="Converted" value={stats.convertedCount} accent="emerald" />
              <StatCard label="Conversion rate" value={`${stats.conversionRate.toFixed(1)}%`} />
            </div>
          )}

          <div className="flex items-center gap-2 mb-4 flex-wrap text-xs">
            {["ALL", "NEW", "IN_PROGRESS", "QUOTED", "CONVERTED", "CLOSED", "LOST"].map((s) => (
              <button
                key={s}
                onClick={() => setStatusFilter(s)}
                className={
                  "px-2.5 py-1 rounded-md font-semibold cursor-pointer " +
                  (statusFilter === s ? "bg-sage-navy text-white" : "bg-white/60 border border-zinc-200 text-zinc-600 hover:border-sage-navy")
                }
              >
                {s}
              </button>
            ))}
          </div>

          <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-zinc-200 text-zinc-600">
                    <th className="text-left px-4 py-3 font-medium">Student</th>
                    <th className="text-left px-4 py-3 font-medium">Course</th>
                    <th className="text-left px-4 py-3 font-medium">Instructor</th>
                    <th className="text-left px-4 py-3 font-medium">Status</th>
                    <th className="text-left px-4 py-3 font-medium">Last message</th>
                  </tr>
                </thead>
                <tbody>
                  {visible.length === 0 ? (
                    <tr><td colSpan={5} className="px-4 py-6 text-center text-zinc-400 italic">No inquiries match.</td></tr>
                  ) : (
                    visible.map((i) => (
                      <tr key={i.id} className="border-b border-zinc-100 hover:bg-sage-navy/5">
                        <td className="px-4 py-3">
                          <Link href={`/messages/${i.id}`} className="font-medium text-zinc-900 hover:text-sage-navy">
                            {i.studentName ?? "—"}
                          </Link>
                          <div className="text-xs text-zinc-500">{i.studentEmail ?? ""}</div>
                        </td>
                        <td className="px-4 py-3 text-zinc-700">{i.courseTitle ?? "—"}</td>
                        <td className="px-4 py-3 text-zinc-700">{i.instructorName ?? "—"}</td>
                        <td className="px-4 py-3">
                          <span className={"text-[10px] px-2 py-0.5 rounded-full font-semibold " + (STATUS_STYLES[i.status] ?? "bg-zinc-100 text-zinc-600")}>
                            {i.status}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-xs text-zinc-500">
                          {i.lastMessageAt ? new Date(i.lastMessageAt).toLocaleString("en-IN", { timeZone: "Asia/Kolkata", dateStyle: "medium", timeStyle: "short" }) : "—"}
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

function StatCard({ label, value, accent }: { label: string; value: string | number; accent?: "emerald" | "amber" }) {
  const accentClass = accent === "emerald" ? "text-emerald-700" : accent === "amber" ? "text-amber-700" : "text-sage-navy";
  return (
    <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-4">
      <p className="text-[11px] uppercase tracking-wider font-semibold text-zinc-500 mb-1">{label}</p>
      <p className={"text-xl font-bold " + accentClass}>{value}</p>
    </div>
  );
}
