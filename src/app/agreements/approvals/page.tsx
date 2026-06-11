"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AlertCircle,
  CheckCircle2,
  ClipboardCheck,
  FileText,
  Loader2,
  RotateCcw,
} from "lucide-react";

import AgreementErmShell from "@/components/agreement-erm/AgreementErmShell";
import AgreementStatusPill from "@/components/agreement-erm/AgreementStatusPill";
import {
  approverApprove,
  approverFetchQueue,
  approverRequestRevision,
  fetchApproverPreviewPdfBlob,
  fetchMe,
  getAgreementErmToken,
  type ApproverQueueItem,
  type ApproverRole,
} from "@/lib/api";
import { formatUsDate } from "@/lib/dates";

/**
 * 3B — Manager / Accounts approver console. Lists the agreements awaiting
 * the signed-in approver's gate, lets them preview the consultant-signed
 * PDF, and Approve or Request Revision (with a required note). The ERM
 * countersign + management actions live on the separate /agreements
 * surface; approvers never see them.
 */
export default function ApprovalsPage() {
  const router = useRouter();
  const [checked, setChecked] = useState(false);
  const [role, setRole] = useState<ApproverRole | null>(null);
  const [items, setItems] = useState<ApproverQueueItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const q = await approverFetchQueue();
      setItems(q);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load your queue.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!getAgreementErmToken()) {
      router.replace("/agreements/login");
      return;
    }
    let cancelled = false;
    fetchMe()
      .then((me) => {
        if (cancelled) return;
        if (me.role === "MANAGER" || me.role === "ACCOUNTS") {
          setRole(me.role);
          setChecked(true);
          void load();
        } else {
          // ERM / super-admin belong on the main console.
          router.replace("/agreements");
        }
      })
      .catch(() => router.replace("/agreements/login"));
    return () => {
      cancelled = true;
    };
  }, [router, load]);

  if (!checked) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <AgreementErmShell
      title="Approvals"
      subtitle={`Agreements awaiting your ${role === "ACCOUNTS" ? "Accounts" : "Manager"} approval.`}
      Icon={ClipboardCheck}
    >
      {error && (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 inline-flex items-start gap-2 text-sm text-red-700">
          <AlertCircle size={14} className="mt-0.5 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {loading ? (
        <div className="py-16 flex items-center justify-center text-gray-400">
          <Loader2 size={22} className="animate-spin" />
        </div>
      ) : items.length === 0 ? (
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-10 text-center">
          <CheckCircle2 size={28} className="mx-auto text-emerald-500" />
          <p className="mt-3 text-sm font-semibold text-gray-700">
            Nothing awaiting your approval
          </p>
          <p className="mt-1 text-xs text-gray-500">
            New agreements appear here when an ERM sends them for approval.
          </p>
        </div>
      ) : (
        <div className="space-y-4 max-w-3xl">
          {items.map((item) => (
            <ApprovalCard
              key={item.application.applicationId}
              item={item}
              role={role!}
              onDone={load}
            />
          ))}
        </div>
      )}
    </AgreementErmShell>
  );
}

function ApprovalCard({
  item,
  role,
  onDone,
}: {
  item: ApproverQueueItem;
  role: ApproverRole;
  onDone: () => void;
}) {
  const app = item.application;
  const [busy, setBusy] = useState<"approve" | "revise" | "preview" | null>(null);
  const [showRevise, setShowRevise] = useState(false);
  const [note, setNote] = useState("");
  const [err, setErr] = useState("");

  const previewPdf = async () => {
    setBusy("preview");
    setErr("");
    try {
      const res = await fetchApproverPreviewPdfBlob(app.applicationId);
      if (!res.ok) throw new Error("Couldn't load the preview.");
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      window.open(url, "_blank", "noopener,noreferrer");
      // Revoke a little later so the new tab has time to load it.
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Couldn't load the preview.");
    } finally {
      setBusy(null);
    }
  };

  const approve = async () => {
    setBusy("approve");
    setErr("");
    try {
      await approverApprove(app.applicationId, {});
      onDone();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Couldn't approve.");
      setBusy(null);
    }
  };

  const requestRevision = async () => {
    if (note.trim().length === 0) {
      setErr("A note is required when requesting a revision.");
      return;
    }
    setBusy("revise");
    setErr("");
    try {
      await approverRequestRevision(app.applicationId, { note: note.trim() });
      onDone();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Couldn't request a revision.");
      setBusy(null);
    }
  };

  const otherGates = (item.approvals ?? []).filter(
    (a) => a.role !== role,
  );

  return (
    <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-5">
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div>
          <h3 className="font-serif text-lg text-gray-900 leading-tight">
            {app.consultantName || app.consultantEmail}
          </h3>
          <p className="text-xs text-gray-500 mt-0.5">{app.consultantEmail}</p>
          <div className="mt-2 flex items-center gap-2 flex-wrap text-[11px] text-gray-500">
            <AgreementStatusPill status={app.status} size="xs" />
            <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 font-semibold text-gray-600">
              Phase {app.phase ?? 1}
            </span>
            {app.technologyTrack && <span>· {app.technologyTrack}</span>}
            {app.effectiveDate && <span>· Eff. {formatUsDate(app.effectiveDate)}</span>}
          </div>
        </div>
        <button
          type="button"
          onClick={previewPdf}
          disabled={busy !== null}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold border border-stone-300 bg-white text-sage-navy hover:bg-stone-50 disabled:opacity-50 cursor-pointer"
        >
          {busy === "preview" ? (
            <Loader2 size={12} className="animate-spin" />
          ) : (
            <FileText size={12} />
          )}
          Preview agreement
        </button>
      </div>

      {/* Other approvers' status (Phase 2 parallel gate visibility). */}
      {otherGates.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {otherGates.map((g) => (
            <span
              key={g.id}
              className="inline-flex items-center gap-1 rounded-full bg-stone-50 border border-stone-200 px-2 py-0.5 text-[11px] text-stone-600"
            >
              {g.role === "ACCOUNTS" ? "Accounts" : "Manager"}:{" "}
              {g.status === "APPROVED"
                ? "approved"
                : g.status === "REVISION_REQUESTED"
                  ? "revision requested"
                  : "pending"}
            </span>
          ))}
        </div>
      )}

      {err && (
        <p className="mt-3 text-xs text-red-600 inline-flex items-center gap-1">
          <AlertCircle size={12} /> {err}
        </p>
      )}

      {!showRevise ? (
        <div className="mt-4 flex items-center gap-2">
          <button
            type="button"
            onClick={approve}
            disabled={busy !== null}
            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {busy === "approve" ? (
              <Loader2 size={12} className="animate-spin" />
            ) : (
              <CheckCircle2 size={12} />
            )}
            Approve
          </button>
          <button
            type="button"
            onClick={() => setShowRevise(true)}
            disabled={busy !== null}
            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-semibold border border-stone-300 text-gray-700 hover:bg-stone-50 disabled:opacity-50 cursor-pointer"
          >
            <RotateCcw size={12} /> Request revision
          </button>
        </div>
      ) : (
        <div className="mt-4 space-y-2">
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={3}
            placeholder="What needs to change before this can be approved? (required)"
            className="w-full px-3 py-2 text-sm rounded-md border border-stone-300 focus:outline-none focus:ring-2 focus:ring-sage-copper/40 focus:border-sage-copper"
          />
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={requestRevision}
              disabled={busy !== null}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-bold bg-sage-copper text-white hover:bg-sage-copper-deep disabled:opacity-60 cursor-pointer"
            >
              {busy === "revise" ? (
                <Loader2 size={12} className="animate-spin" />
              ) : (
                <RotateCcw size={12} />
              )}
              Send revision request
            </button>
            <button
              type="button"
              onClick={() => {
                setShowRevise(false);
                setNote("");
                setErr("");
              }}
              disabled={busy !== null}
              className="px-3 py-2 rounded-md text-xs font-semibold text-gray-500 hover:text-gray-800 cursor-pointer"
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
