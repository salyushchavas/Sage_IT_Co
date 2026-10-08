"use client";

import { useCallback, useState } from "react";
import {
  AlertCircle,
  CheckCircle2,
  FileText,
  Loader2,
  RotateCcw,
} from "lucide-react";

import {
  webApproverApprove,
  webApproverRequestRevision,
  webApproverVersionImages,
  type WebApprovalRole,
  type WebApproverQueueItem,
} from "@/lib/api";
import { formatUsDate } from "@/lib/dates";
import { approverRoleLabel } from "@/lib/roles";
import AgreementStatusPill from "@/components/web-agreement/erm/AgreementStatusPill";
import ApproverPreviewModal from "./ApproverPreviewModal";

/**
 * One agreement waiting on the approver's gate (the Pending approval tab):
 * the website's copy of the console approvals page's ApprovalCard
 * (src/app/agreements/approvals/page.tsx).
 *
 * The approver previews the version the ERM routed for this round (page
 * images; an agreement verified before versions existed falls back to a live
 * render), then Approves in one click or requests a revision with a required
 * note. Both decisions stay disabled until the preview has been scrolled to
 * the end. That is checked in the browser only, and reloading the page
 * starts it over, as in the console.
 *
 * A decision goes back to the ERM, never to the participant, and sends no
 * email. `onDone` reloads the queue; the decided card drops out of it.
 */
export default function ApprovalCard({
  item,
  role,
  onDone,
}: {
  item: WebApproverQueueItem;
  role: WebApprovalRole;
  onDone: () => void;
}) {
  const app = item.application;
  const [busy, setBusy] = useState<"approve" | "revise" | "preview" | null>(null);
  const [showRevise, setShowRevise] = useState(false);
  const [note, setNote] = useState("");
  const [err, setErr] = useState("");
  const [previewPages, setPreviewPages] = useState<string[] | null>(null);
  // The version this round reviews (V{n}); null when none was routed and
  // the server rendered the agreement live.
  const [previewVersion, setPreviewVersion] = useState<number | null>(null);
  // Approve / Request revision unlock once the whole preview was scrolled.
  const [previewedFully, setPreviewedFully] = useState(false);
  const markPreviewed = useCallback(() => setPreviewedFully(true), []);

  const previewAgreement = async () => {
    setBusy("preview");
    setErr("");
    try {
      const data = await webApproverVersionImages(app.applicationId);
      setPreviewPages(data.pages);
      setPreviewVersion(data.versionNumber ?? null);
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
      // One click: no confirm and no note, as in the console.
      await webApproverApprove(app.applicationId, {});
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
      await webApproverRequestRevision(app.applicationId, { note: note.trim() });
      onDone();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Couldn't request a revision.");
      setBusy(null);
    }
  };

  // The other gate's decisions (Phase 2 runs both gates side by side). Every
  // row of that role, all rounds, exactly as the console shows them.
  const otherGates = (item.approvals ?? []).filter((a) => a.role !== role);

  return (
    <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-4 sm:p-5">
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="min-w-0">
          <h3 className="font-serif text-lg text-gray-900 leading-tight break-words">
            {app.consultantName || app.consultantEmail}
          </h3>
          <p className="text-xs text-gray-500 mt-0.5 break-all">{app.consultantEmail}</p>
          <div className="mt-2 flex items-center gap-2 flex-wrap text-[11px] text-gray-500">
            <AgreementStatusPill status={app.status} size="xs" />
            <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 font-semibold text-gray-600">
              Phase {app.phase ?? 1}
            </span>
            {app.technologyTrack && <span>· {app.technologyTrack}</span>}
            {app.effectiveDate && <span>· Eff. {formatUsDate(app.effectiveDate)}</span>}
          </div>
        </div>
      </div>

      {previewPages !== null && (
        <ApproverPreviewModal
          pages={previewPages}
          title={
            previewVersion != null
              ? `Agreement preview — Version V${previewVersion}`
              : undefined
          }
          onScrolledToEnd={markPreviewed}
          onClose={() => {
            setPreviewPages(null);
            setPreviewVersion(null);
          }}
        />
      )}

      {otherGates.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {otherGates.map((g) => (
            <span
              key={g.id}
              className="inline-flex items-center gap-1 rounded-full bg-stone-50 border border-stone-200 px-2 py-0.5 text-[11px] text-stone-600"
            >
              {approverRoleLabel(g.role)}:{" "}
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
        <p className="mt-3 text-xs text-red-600 inline-flex items-start gap-1">
          <AlertCircle size={12} className="mt-0.5 shrink-0" /> <span>{err}</span>
        </p>
      )}

      {!showRevise ? (
        <div className="mt-4">
          {/* Order: 1. Preview  2. Request revision  3. Approve. The last two
              stay disabled until the whole preview has been scrolled. */}
          <div className="flex items-center gap-2 flex-wrap">
            <button
              type="button"
              onClick={previewAgreement}
              disabled={busy !== null}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-semibold border border-stone-300 bg-white text-sage-navy hover:bg-stone-50 disabled:opacity-50 cursor-pointer whitespace-nowrap"
            >
              {busy === "preview" ? (
                <Loader2 size={12} className="animate-spin" />
              ) : previewedFully ? (
                <CheckCircle2 size={12} className="text-emerald-600" />
              ) : (
                <FileText size={12} />
              )}
              {previewedFully ? "Previewed" : "Preview agreement"}
            </button>
            <button
              type="button"
              onClick={() => setShowRevise(true)}
              disabled={busy !== null || !previewedFully}
              title={!previewedFully ? "Preview the full agreement first" : undefined}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-semibold border border-stone-300 text-gray-700 hover:bg-stone-50 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer whitespace-nowrap"
            >
              <RotateCcw size={12} /> Request revision
            </button>
            <button
              type="button"
              onClick={approve}
              disabled={busy !== null || !previewedFully}
              title={!previewedFully ? "Preview the full agreement first" : undefined}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 disabled:cursor-not-allowed cursor-pointer whitespace-nowrap"
            >
              {busy === "approve" ? (
                <Loader2 size={12} className="animate-spin" />
              ) : (
                <CheckCircle2 size={12} />
              )}
              Approve
            </button>
          </div>
          {!previewedFully && (
            <p className="mt-2 text-[11px] text-sage-copper-deep inline-flex items-start gap-1.5">
              <AlertCircle size={12} className="mt-px shrink-0" />
              <span>
                Open the preview and scroll to the end of the agreement to enable
                Approve / Request revision.
              </span>
            </p>
          )}
        </div>
      ) : (
        <div className="mt-4 space-y-2">
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={3}
            placeholder="What needs to change before this can be approved? (required)"
            aria-label="What needs to change before this can be approved?"
            className="w-full px-3 py-2 text-sm rounded-md border border-stone-300 focus:outline-none focus:ring-2 focus:ring-sage-copper/40 focus:border-sage-copper"
          />
          <div className="flex items-center gap-2 flex-wrap">
            <button
              type="button"
              onClick={requestRevision}
              disabled={busy !== null}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-bold bg-sage-copper text-white hover:bg-sage-copper-deep disabled:opacity-60 cursor-pointer whitespace-nowrap"
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
