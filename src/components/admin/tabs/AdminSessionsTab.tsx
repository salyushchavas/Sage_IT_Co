"use client";

import { useEffect, useState } from "react";
import { Download, Loader2 } from "lucide-react";

import { downloadAdminCsv, getAdminSessions, type AdminSessionRow } from "@/lib/api";

const STATUS_STYLES: Record<string, string> = {
  REQUESTED: "bg-amber-100 text-amber-700",
  SCHEDULED: "bg-blue-100 text-blue-700",
  COMPLETED: "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-zinc-100 text-zinc-600",
  NO_SHOW: "bg-red-50 text-red-700",
};

export function AdminSessionsTab() {
  const [rows, setRows] = useState<AdminSessionRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    getAdminSessions()
      .then((data) => { if (!cancelled) setRows(data ?? []); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "Couldn't load"); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  const exportCsv = async () => {
    setExporting(true);
    try {
      await downloadAdminCsv("sessions");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Export failed");
    } finally {
      setExporting(false);
    }
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-5 gap-4 flex-wrap">
        <h1 className="text-2xl font-bold text-zinc-900">All Sessions ({rows.length})</h1>
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
        <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-zinc-200 text-zinc-600">
                  <th className="text-left px-4 py-3 font-medium">Student</th>
                  <th className="text-left px-4 py-3 font-medium">Mentor</th>
                  <th className="text-left px-4 py-3 font-medium">Course</th>
                  <th className="text-left px-4 py-3 font-medium">Topic</th>
                  <th className="text-left px-4 py-3 font-medium">Status</th>
                  <th className="text-left px-4 py-3 font-medium">Scheduled</th>
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="px-4 py-6 text-center text-zinc-400 italic">
                      No sessions recorded.
                    </td>
                  </tr>
                ) : (
                  rows.map((r) => (
                    <tr key={r.sessionId} className="border-b border-zinc-100">
                      <td className="px-4 py-3">
                        <div className="font-medium text-zinc-900">{r.studentName ?? "—"}</div>
                        <div className="text-xs text-zinc-500">{r.studentEmail ?? ""}</div>
                      </td>
                      <td className="px-4 py-3 text-zinc-700">{r.mentorName ?? "—"}</td>
                      <td className="px-4 py-3 text-zinc-700">{r.courseTitle ?? "—"}</td>
                      <td className="px-4 py-3 text-zinc-700 text-xs">{r.topic ?? "—"}</td>
                      <td className="px-4 py-3">
                        <span className={"text-xs px-2 py-0.5 rounded-full font-semibold " + (STATUS_STYLES[r.status] ?? "bg-zinc-100 text-zinc-600")}>
                          {r.status}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-zinc-500 text-xs">
                        {r.scheduledAt ? new Date(r.scheduledAt).toLocaleString("en-IN", { timeZone: "Asia/Kolkata", dateStyle: "medium", timeStyle: "short" }) : "—"}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
