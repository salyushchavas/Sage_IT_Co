"use client";

import { useCallback, useEffect, useState } from "react";
import { AlertCircle, CheckCircle2, Eye, FileText, Loader2, ShieldCheck, Undo2 } from "lucide-react";
import { formatDateTime } from "@/lib/datetime";
import {
  confirmParticipantDocuments,
  getVerificationDetail,
  getVerificationQueue,
  sendDocumentBack,
  viewParticipantDocument,
  type VerificationDetail,
  type VerificationRow,
} from "@/lib/api";

const STATUS_LABEL: Record<string, string> = {
  PENDING: "Waiting",
  APPROVED: "Approved",
  REJECTED: "Sent back",
  EXCEPTION_REQUESTED: "Not applicable (asked)",
  EXCEPTION_APPROVED: "Not applicable (approved)",
  EXCEPTION_DECLINED: "Not applicable (declined)",
  NOT_APPLICABLE: "Not applicable",
};

/**
 * After a participant submits their documents, an ERM opens every one and
 * confirms them, or sends one back with a reason. Confirming opens the
 * participant's program step and emails them; it's only possible once
 * each document has been opened here.
 */
export function DocumentVerificationQueue() {
  const [filter, setFilter] = useState<"WAITING" | "VERIFIED">("WAITING");
  const [rows, setRows] = useState<VerificationRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);
  const [openId, setOpenId] = useState<number | null>(null);

  const load = useCallback(async (status: "WAITING" | "VERIFIED") => {
    setRows(await getVerificationQueue(status));
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError("");
    load(filter)
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "Couldn't load the list"); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [filter, load]);

  const done = async (text: string, ok: boolean) => {
    setNotice({ ok, text });
    setOpenId(null);
    await load(filter).catch(() => {});
  };

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Verify documents</h1>
      <p className="text-sm text-gray-500">
        Participants who submitted their documents. Open each document and confirm, or send one back with a
        reason. They can choose their program once you confirm.
      </p>

      <div className="flex flex-wrap items-center gap-1.5">
        {([["WAITING", "Waiting for you"], ["VERIFIED", "Confirmed"]] as const).map(([id, label]) => (
          <button
            key={id}
            type="button"
            onClick={() => { setFilter(id); setOpenId(null); setNotice(null); }}
            className={
              "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold transition cursor-pointer "
              + (filter === id
                ? "bg-sage-navy text-white shadow-sm"
                : "bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy")
            }
          >
            {label}
          </button>
        ))}
      </div>

      {notice && (
        <p className={"inline-flex items-center gap-1.5 text-sm " + (notice.ok ? "text-emerald-700" : "text-red-700")}>
          {notice.ok ? <CheckCircle2 size={14} /> : <AlertCircle size={14} />} {notice.text}
        </p>
      )}
      {error && (
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
                <th className="text-left px-4 py-2">Participant</th>
                <th className="text-left px-4 py-2">Course</th>
                <th className="text-left px-4 py-2">Documents</th>
                <th className="text-left px-4 py-2">Last upload</th>
                <th className="text-right px-4 py-2">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {rows.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-4 py-6 text-center text-sm text-gray-400 italic">
                    {filter === "WAITING" ? "No documents waiting to be checked." : "Nobody is waiting to choose their program."}
                  </td>
                </tr>
              ) : (
                rows.flatMap((r) => {
                  const lines = [
                    <tr key={r.userId}>
                      <td className="px-4 py-2">
                        <div className="font-medium text-gray-900">{r.fullName ?? "—"}</div>
                        <div className="text-xs text-gray-500 break-all">{r.email ?? ""}</div>
                        <div className="font-mono text-[10px] text-gray-400">{r.participantId ?? ""}</div>
                      </td>
                      <td className="px-4 py-2 text-gray-700">{r.course ?? "—"}</td>
                      <td className="px-4 py-2 text-gray-700">
                        {r.documentCount}
                        {r.sentBack > 0 && (
                          <span className="ml-2 px-1.5 py-0.5 rounded bg-red-50 text-red-700 text-[10px] font-bold uppercase tracking-wider whitespace-nowrap">
                            {r.sentBack} sent back
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-2 text-xs text-gray-500 whitespace-nowrap">
                        {r.submittedAt ? formatDateTime(r.submittedAt) : "—"}
                      </td>
                      <td className="px-4 py-2 text-right whitespace-nowrap">
                        <button
                          type="button"
                          onClick={() => { setOpenId(openId === r.userId ? null : r.userId); setNotice(null); }}
                          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy transition cursor-pointer"
                        >
                          <Eye size={12} /> {openId === r.userId ? "Close" : filter === "WAITING" ? "Review" : "View"}
                        </button>
                      </td>
                    </tr>,
                  ];
                  if (openId === r.userId) {
                    lines.push(
                      <tr key={`${r.userId}-detail`}>
                        <td colSpan={5} className="px-4 py-4 bg-gray-50/60">
                          <ReviewPanel userId={r.userId} canConfirm={filter === "WAITING"} onDone={done} />
                        </td>
                      </tr>,
                    );
                  }
                  return lines;
                })
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function ReviewPanel({ userId, canConfirm, onDone }: {
  userId: number;
  canConfirm: boolean;
  onDone: (text: string, ok: boolean) => void;
}) {
  const [d, setD] = useState<VerificationDetail | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [opened, setOpened] = useState<Set<number>>(new Set());
  const [backFor, setBackFor] = useState<number | null>(null);
  const [reason, setReason] = useState("");

  useEffect(() => {
    let cancelled = false;
    getVerificationDetail(userId)
      .then((x) => { if (!cancelled) setD(x); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "Couldn't load the details"); });
    return () => { cancelled = true; };
  }, [userId]);

  const view = async (docId: number) => {
    setError("");
    try {
      await viewParticipantDocument(docId);
      setOpened((s) => new Set(s).add(docId));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't open the document");
    }
  };

  const confirm = async () => {
    setBusy(true);
    setError("");
    try {
      const r = await confirmParticipantDocuments(userId);
      onDone(r.message, r.emailSent);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't confirm");
      setBusy(false);
    }
  };

  const sendBack = async (docId: number) => {
    setBusy(true);
    setError("");
    try {
      await sendDocumentBack(userId, docId, reason.trim());
      onDone("Sent back. The participant was emailed the reason and sees it on their dashboard.", true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't send it back");
      setBusy(false);
    }
  };

  if (!d) {
    return error ? (
      <p className="inline-flex items-center gap-1.5 text-sm text-red-700"><AlertCircle size={14} /> {error}</p>
    ) : (
      <Loader2 size={18} className="animate-spin text-sage-navy" />
    );
  }

  // Every file the ERM must look at before confirming (not the "not applicable" requests).
  const files = d.documents.filter((x) => !x.notApplicable && x.reviewStatus !== "REJECTED");
  const unopened = files.filter((x) => x.reviewStatus === "PENDING" && !opened.has(x.id));
  // Sent back and not yet replaced: the participant answers it before anything is confirmed.
  const waitingOnParticipant = d.documents.some((x) =>
    x.documentType !== "SSN_DOCUMENT" && (x.reviewStatus === "REJECTED" || x.reviewStatus === "EXCEPTION_DECLINED"));

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-gray-200 bg-white p-3 text-xs space-y-1">
        <p className="text-gray-900 font-semibold">{d.fullName}</p>
        <p className="text-gray-600 break-all">{d.email}{d.phone ? ` · ${d.phone}` : ""}{d.location ? ` · ${d.location}` : ""}</p>
        <p className="text-gray-600">Course: {d.course ?? "—"}</p>
        <p className="font-mono text-gray-500">{d.participantId}</p>
      </div>

      <div className="rounded-xl border border-gray-200 bg-white divide-y divide-gray-100">
        {d.documents.length === 0 && (
          <p className="px-3 py-3 text-xs text-gray-400 italic">No documents.</p>
        )}
        {d.documents.map((doc) => (
          <div key={doc.id} className="px-3 py-2.5 text-xs">
            <div className="flex flex-wrap items-center gap-2">
              <FileText size={13} className="text-gray-400 shrink-0" />
              <span className="font-semibold text-gray-900">{doc.label}</span>
              <span className="text-gray-500 truncate max-w-[14rem]">{doc.notApplicable ? "Not applicable" : doc.fileName}</span>
              <span className="px-1.5 py-0.5 rounded bg-gray-100 text-gray-700 text-[10px] font-bold uppercase tracking-wider">
                {STATUS_LABEL[doc.reviewStatus] ?? doc.reviewStatus}
              </span>
              {opened.has(doc.id) && (
                <span className="inline-flex items-center gap-1 text-[10px] font-semibold text-emerald-700">
                  <CheckCircle2 size={11} /> Opened
                </span>
              )}
              <span className="ml-auto flex items-center gap-1.5">
                {!doc.notApplicable && (
                  <button
                    type="button"
                    onClick={() => view(doc.id)}
                    className="inline-flex items-center gap-1 px-2 py-1 rounded-lg font-semibold text-sage-navy hover:bg-sage-navy/10 cursor-pointer"
                  >
                    <Eye size={12} /> View
                  </button>
                )}
                {canConfirm && !doc.notApplicable && doc.reviewStatus === "PENDING" && backFor !== doc.id && (
                  <button
                    type="button"
                    onClick={() => { setBackFor(doc.id); setReason(""); }}
                    className="inline-flex items-center gap-1 px-2 py-1 rounded-lg font-semibold text-gray-500 hover:text-red-700 hover:bg-red-50 cursor-pointer"
                  >
                    <Undo2 size={12} /> Send back
                  </button>
                )}
              </span>
            </div>
            {doc.exceptionReason && <p className="mt-1 text-gray-500 italic">Their reason: {doc.exceptionReason}</p>}
            {doc.reviewerNotes && <p className="mt-1 text-gray-500 italic">Note: {doc.reviewerNotes}</p>}
            {backFor === doc.id && (
              <div className="mt-2 space-y-1.5">
                <label htmlFor={`back-${doc.id}`} className="block text-[11px] text-gray-600">
                  What needs fixing? The participant gets this by email and on their dashboard.
                </label>
                <textarea
                  id={`back-${doc.id}`}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  rows={2}
                  maxLength={1000}
                  placeholder="For example: the photo is blurry, please upload a clearer copy."
                  className="w-full px-3 py-2 text-xs rounded-lg border border-gray-200 bg-white text-gray-900 placeholder-gray-400 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy resize-none"
                />
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => sendBack(doc.id)}
                    disabled={busy || reason.trim().length < 5}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-red-600 text-white hover:bg-red-700 disabled:opacity-60 cursor-pointer"
                  >
                    {busy && <Loader2 size={12} className="animate-spin" />} Send back
                  </button>
                  <button
                    type="button"
                    onClick={() => setBackFor(null)}
                    className="px-3 py-1.5 rounded-lg text-xs font-semibold text-gray-500 hover:text-gray-800 cursor-pointer"
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}
          </div>
        ))}
      </div>

      {d.missing.length > 0 && (
        <p className="text-xs text-amber-800">Still missing: {d.missing.join(", ")}</p>
      )}
      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700"><AlertCircle size={14} /> {error}</p>
      )}

      {canConfirm ? (
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={confirm}
            disabled={busy || unopened.length > 0 || d.missing.length > 0 || waitingOnParticipant}
            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {busy ? <Loader2 size={14} className="animate-spin" /> : <ShieldCheck size={14} />}
            Confirm documents
          </button>
          {waitingOnParticipant ? (
            <span className="text-xs text-gray-500">Sent back. Waiting for the participant&apos;s new copy.</span>
          ) : unopened.length > 0 && (
            <span className="text-xs text-gray-500">
              Open {unopened.length === 1 ? "the remaining document" : `the ${unopened.length} remaining documents`} first.
            </span>
          )}
        </div>
      ) : (
        <p className="text-xs text-gray-500">Confirmed. They can choose their program.</p>
      )}
    </div>
  );
}
