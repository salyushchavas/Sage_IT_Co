"use client";

import { useCallback, useEffect, useState } from "react";
import { AlertCircle, CheckCircle2, Loader2, Send, XCircle } from "lucide-react";
import { formatDateTime } from "@/lib/datetime";
import {
  confirmApplication,
  declineApplication,
  getApplicationCounts,
  getApplications,
  type ApplicationRow,
  type ApplicationStatus,
} from "@/lib/api";

const FILTERS: { id: ApplicationStatus; label: string; empty: string }[] = [
  { id: "PENDING",    label: "Waiting for you",  empty: "No applications are waiting." },
  { id: "APPROVED",   label: "Link sent",        empty: "Nobody is waiting to register." },
  { id: "REGISTERED", label: "Registered",       empty: "Nobody has registered from an application yet." },
  { id: "DECLINED",   label: "Declined",         empty: "No declined applications." },
];

/**
 * Roadmap step 1, the staff side (ERM, Operations, System admin): the
 * applications that came in from the website. Confirming one emails the
 * applicant a one-time link to register; until they use it, the link can
 * be sent again. Declining needs no email and can be undone by confirming.
 */
export function ApplicationsQueue() {
  const [filter, setFilter] = useState<ApplicationStatus>("PENDING");
  const [rows, setRows] = useState<ApplicationRow[]>([]);
  const [counts, setCounts] = useState<Partial<Record<ApplicationStatus, number>>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  const [declining, setDeclining] = useState<ApplicationRow | null>(null);
  const [reason, setReason] = useState("");

  const load = useCallback(async (status: ApplicationStatus) => {
    const [list, totals] = await Promise.all([getApplications(status), getApplicationCounts()]);
    setRows(list);
    setCounts(totals);
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError("");
    load(filter)
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "Couldn't load the applications");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [filter, load]);

  const confirm = async (row: ApplicationRow, again: boolean) => {
    setBusy(row.id);
    setNotice(null);
    setError("");
    try {
      const r = await confirmApplication(row.id, again);
      // A failed email is shown as a problem, not a success.
      setNotice({ ok: r.emailSent, text: r.message });
      await load(filter);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't confirm the application");
    } finally {
      setBusy(null);
    }
  };

  const decline = async () => {
    if (!declining) return;
    setBusy(declining.id);
    setError("");
    try {
      await declineApplication(declining.id, reason.trim());
      setNotice({ ok: true, text: `Declined ${declining.fullName}'s application.` });
      setDeclining(null);
      setReason("");
      await load(filter);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't decline the application");
    } finally {
      setBusy(null);
    }
  };

  const current = FILTERS.find((f) => f.id === filter)!;

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Applications</h1>
      <p className="text-sm text-gray-500">
        People who applied for a course on the website. Confirm an application to email
        the applicant a link to register; after they register, their roadmap starts.
      </p>

      <div className="flex flex-wrap items-center gap-1.5">
        {FILTERS.map((f) => (
          <button
            key={f.id}
            type="button"
            onClick={() => setFilter(f.id)}
            className={
              "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold transition cursor-pointer " +
              (filter === f.id
                ? "bg-sage-navy text-white shadow-sm"
                : "bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy")
            }
          >
            {f.label}
            <span className={"font-mono " + (filter === f.id ? "text-white/80" : "text-gray-400")}>
              {counts[f.id] ?? 0}
            </span>
          </button>
        ))}
      </div>

      {notice && (
        <p className={"inline-flex items-center gap-1.5 text-sm " + (notice.ok ? "text-emerald-700" : "text-red-700")}>
          {notice.ok ? <CheckCircle2 size={14} /> : <AlertCircle size={14} />} {notice.text}
        </p>
      )}
      {error && !declining && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}

      {loading ? (
        <div className="text-center py-10">
          <Loader2 size={20} className="animate-spin text-sage-navy inline" />
        </div>
      ) : (
        <div className="rounded-2xl border border-gray-100 bg-white overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
              <tr>
                <th className="text-left px-4 py-2">Applicant</th>
                <th className="text-left px-4 py-2">Phone</th>
                <th className="text-left px-4 py-2">Course</th>
                <th className="text-left px-4 py-2">Applied</th>
                <th className="text-left px-4 py-2">Status</th>
                <th className="text-right px-4 py-2">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {rows.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-6 text-center text-sm text-gray-400 italic">
                    {current.empty}
                  </td>
                </tr>
              ) : (
                rows.map((r) => (
                  <tr key={r.id}>
                    <td className="px-4 py-2">
                      <div className="font-medium text-gray-900">{r.fullName}</div>
                      <div className="text-xs text-gray-500 break-all">{r.email}</div>
                      {r.source === "INVITE" && (
                        <div className="text-[10px] font-semibold text-sage-navy">Invited by Operations</div>
                      )}
                    </td>
                    <td className="px-4 py-2 font-mono text-xs text-gray-700 whitespace-nowrap">{r.phone ?? "—"}</td>
                    <td className="px-4 py-2 text-gray-700">{r.selectedTechnology ?? "—"}</td>
                    <td className="px-4 py-2 text-xs text-gray-500 whitespace-nowrap">
                      {r.createdAt ? formatDateTime(r.createdAt) : "—"}
                    </td>
                    <td className="px-4 py-2 text-xs text-gray-600">
                      <StatusNote row={r} />
                    </td>
                    <td className="px-4 py-2 text-right whitespace-nowrap">
                      {(r.status === "PENDING" || r.status === "DECLINED") && (
                        <button
                          type="button"
                          onClick={() => confirm(r, false)}
                          disabled={busy === r.id}
                          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 transition cursor-pointer"
                        >
                          {busy === r.id ? <Loader2 size={12} className="animate-spin" /> : <CheckCircle2 size={12} />}
                          Confirm
                        </button>
                      )}
                      {r.status === "APPROVED" && (
                        <button
                          type="button"
                          onClick={() => confirm(r, true)}
                          disabled={busy === r.id}
                          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy disabled:opacity-60 transition cursor-pointer"
                        >
                          {busy === r.id ? <Loader2 size={12} className="animate-spin" /> : <Send size={12} />}
                          Send link again
                        </button>
                      )}
                      {(r.status === "PENDING" || r.status === "APPROVED") && (
                        <button
                          type="button"
                          onClick={() => { setDeclining(r); setReason(""); setError(""); }}
                          disabled={busy === r.id}
                          className="ml-2 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-gray-500 hover:text-red-700 hover:bg-red-50 disabled:opacity-60 transition cursor-pointer"
                        >
                          <XCircle size={12} /> Decline
                        </button>
                      )}
                      {r.status === "REGISTERED" && <span className="text-xs text-gray-400">—</span>}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}

      {declining && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={() => setDeclining(null)}>
          <div className="bg-white rounded-2xl shadow-xl max-w-md w-full p-6" onClick={(e) => e.stopPropagation()}>
            <h2 className="text-lg font-bold text-gray-900">Decline this application?</h2>
            <p className="text-sm text-gray-500 mt-1">
              {declining.fullName} ({declining.email}) won&apos;t be emailed. A registration link
              already sent stops working. You can confirm the application later.
            </p>
            <label htmlFor="decline-reason" className="block text-xs font-semibold text-gray-700 mt-4 mb-1">
              Reason (optional, for your team)
            </label>
            <textarea
              id="decline-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={3}
              maxLength={500}
              className="w-full px-3 py-2 text-sm rounded-lg border border-gray-200 bg-white text-gray-900 placeholder-gray-400 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy resize-none"
              placeholder="For example: duplicate of another application"
            />
            {error && (
              <p className="mt-2 inline-flex items-center gap-1.5 text-sm text-red-700">
                <AlertCircle size={14} /> {error}
              </p>
            )}
            <div className="mt-5 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setDeclining(null)}
                className="px-4 py-2 rounded-lg text-sm font-semibold text-gray-600 hover:text-gray-900 cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={decline}
                disabled={busy === declining.id}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-red-600 text-white hover:bg-red-700 disabled:opacity-60 cursor-pointer"
              >
                {busy === declining.id && <Loader2 size={14} className="animate-spin" />} Decline
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function StatusNote({ row }: { row: ApplicationRow }) {
  const by = row.reviewedByName ? ` by ${row.reviewedByName}` : "";
  switch (row.status) {
    case "PENDING":
      return <span className="font-semibold text-amber-700">Waiting to be confirmed</span>;
    case "APPROVED":
      return (
        <>
          <span className="font-semibold text-sage-navy">Confirmed{by}</span>
          <br />
          {row.registrationEmailSentAt
            ? `Link emailed ${formatDateTime(row.registrationEmailSentAt)}`
            : <span className="text-red-700">The link email wasn&apos;t sent</span>}
          {row.linkExpiresAt && <><br />Link expires {formatDateTime(row.linkExpiresAt)}</>}
        </>
      );
    case "REGISTERED":
      return (
        <>
          <span className="font-semibold text-emerald-700">Registered</span>
          {row.registeredAt && <><br />{formatDateTime(row.registeredAt)}</>}
        </>
      );
    default:
      return (
        <>
          <span className="font-semibold text-gray-600">Declined{by}</span>
          {row.declineReason && <><br />{row.declineReason}</>}
        </>
      );
  }
}
