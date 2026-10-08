"use client";

import { useState } from "react";
import { AlertCircle, ClipboardCheck, Download, FileText, Loader2 } from "lucide-react";

import {
  WebAgreementApiError,
  webApproverDownloadPdf,
  webApproverPhase1SignedImages,
  webApproverSignedImages,
  type WebApproverApprovedItem,
  type WebApproverDownloadDoc,
} from "@/lib/api";
import { formatUsDayCt } from "@/lib/datetime";
import AgreementStatusPill from "@/components/web-agreement/erm/AgreementStatusPill";
import {
  STICKY_ACTION_TD,
  STICKY_ACTION_TH,
} from "@/components/web-agreement/ui/primitives";
import ApproverPreviewModal from "./ApproverPreviewModal";

/**
 * The approver's read-only record of the agreements they approved (the
 * Approved agreements tab), one row per agreement from their latest
 * approval. The website's copy of the console approvals page's
 * ApprovedRecord and ApprovedTable (src/app/agreements/approvals/page.tsx).
 *
 * A Manager sees one flat table with an ERM column; Accounts approvers work
 * across ERMs, so their record is split by the agreement's ERM.
 *
 * The Document column holds the only PDFs an approver ever gets:
 *   - "Phase 1 signed" (a Manager, once the row is Phase 2): the stored
 *     Phase 1 executed copy, as a preview and a download;
 *   - executed: a preview of the signed agreement ("Phase 2 signed" in
 *     Phase 2) and its download;
 *   - otherwise "Awaiting ERM signature" and "Download approved copy" (the
 *     latest version, which may be newer than the one they approved).
 *
 * Phones: the table scrolls sideways inside its card with Document pinned on
 * the right; Current status moves under the participant's name below `sm`.
 */
export default function ApproverApprovedRecord({
  loading,
  items,
  groupByErm,
}: {
  loading: boolean;
  items: WebApproverApprovedItem[];
  /** Accounts: one table per ERM. Manager: one flat table. */
  groupByErm: boolean;
}) {
  if (loading) {
    return (
      <div className="py-16 flex items-center justify-center text-gray-400">
        <Loader2 size={22} className="animate-spin" />
      </div>
    );
  }
  if (items.length === 0) {
    return (
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-10 text-center">
        <ClipboardCheck size={28} className="mx-auto text-stone-400" />
        <p className="mt-3 text-sm font-semibold text-gray-700">
          No approved agreements yet
        </p>
        <p className="mt-1 text-xs text-gray-500">
          Agreements you approve will be recorded here.
        </p>
      </div>
    );
  }

  if (!groupByErm) {
    return <ApprovedTable rows={items} showErm />;
  }

  // One group per ERM, in the order they first appear (newest approval first).
  const groups = new Map<string, WebApproverApprovedItem[]>();
  for (const it of items) {
    const key = it.ermName || "(unassigned ERM)";
    const arr = groups.get(key);
    if (arr) arr.push(it);
    else groups.set(key, [it]);
  }
  return (
    <div className="space-y-6 max-w-4xl">
      {Array.from(groups.entries()).map(([erm, rows]) => (
        <div key={erm}>
          <h3 className="text-xs font-bold uppercase tracking-wider text-sage-navy mb-2 flex flex-wrap items-center gap-1.5">
            <FileText size={12} className="shrink-0" /> <span className="break-words">{erm}</span>
            <span className="text-gray-400 font-medium normal-case tracking-normal">
              · {rows.length} agreement{rows.length === 1 ? "" : "s"}
            </span>
          </h3>
          <ApprovedTable rows={rows} showErm={false} />
        </div>
      ))}
    </div>
  );
}

function ApprovedTable({
  rows,
  showErm,
}: {
  rows: WebApproverApprovedItem[];
  showErm: boolean;
}) {
  // One preview open at a time across the table.
  const [previewPages, setPreviewPages] = useState<string[] | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [previewErr, setPreviewErr] = useState("");
  const [downloadId, setDownloadId] = useState<string | null>(null);
  const [downloadErr, setDownloadErr] = useState("");
  const anyBusy = busyId !== null || downloadId !== null;

  // The executed agreement (COMPLETED), as page images.
  const openPreview = async (appId: string) => {
    setBusyId(appId);
    setPreviewErr("");
    try {
      const data = await webApproverSignedImages(appId);
      setPreviewPages(data.pages);
    } catch (e) {
      setPreviewErr(e instanceof Error ? e.message : "Couldn't load the signed agreement.");
    } finally {
      setBusyId(null);
    }
  };

  // The stored Phase 1 executed copy (Manager only), whatever the agreement
  // is doing now.
  const openPhase1Preview = async (appId: string) => {
    setBusyId(appId + ":p1");
    setPreviewErr("");
    try {
      const data = await webApproverPhase1SignedImages(appId);
      setPreviewPages(data.pages);
    } catch (e) {
      setPreviewErr(
        e instanceof Error ? e.message : "Couldn't load the Phase 1 signed agreement.");
    } finally {
      setBusyId(null);
    }
  };

  /**
   * Saves the PDF. The bytes come through the server under the approver's
   * sign-in, so no document URL reaches the page: they land in a blob: URL
   * that is revoked a minute later.
   */
  const download = async (r: WebApproverApprovedItem, doc: WebApproverDownloadDoc) => {
    setDownloadId(r.appId + ":" + doc);
    setDownloadErr("");
    try {
      const blob = await webApproverDownloadPdf(r.appId, doc);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = approverPdfFilename(r, doc);
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      // A 4xx reason is written for the approver (it is the message); a 5xx
      // reason is the raw render failure — console only.
      if (e instanceof WebAgreementApiError && e.status >= 500 && e.previewError) {
        console.error(`Approver download failed on the server — ${e.previewError}`);
      }
      setDownloadErr(e instanceof Error ? e.message : "Couldn't download the agreement.");
    } finally {
      setDownloadId(null);
    }
  };

  return (
    <div className="bg-white rounded-2xl border border-stone-200 shadow-sm overflow-x-auto">
      {previewErr && (
        <p className="px-4 pt-3 text-xs text-red-600 flex items-start gap-1">
          <AlertCircle size={12} className="mt-0.5 shrink-0" /> <span>{previewErr}</span>
        </p>
      )}
      {downloadErr && (
        <p className="px-4 pt-3 text-xs text-red-600 flex items-start gap-1">
          <AlertCircle size={12} className="mt-0.5 shrink-0" /> <span>{downloadErr}</span>
        </p>
      )}
      <table className="w-full text-sm">
        <thead className="bg-gray-50">
          <tr className="text-left text-[11px] uppercase tracking-wider text-gray-500 border-b border-stone-100">
            <th className="px-4 py-2.5 font-semibold">Participant</th>
            {showErm && <th className="px-4 py-2.5 font-semibold">ERM</th>}
            <th className="px-4 py-2.5 font-semibold">Phase</th>
            <th className="px-4 py-2.5 font-semibold">Approved</th>
            <th className="hidden sm:table-cell px-4 py-2.5 font-semibold whitespace-nowrap">Current status</th>
            <th className={`px-4 py-2.5 font-semibold ${STICKY_ACTION_TH}`}>Document</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const completed = r.status === "COMPLETED";
            return (
              <tr key={r.appId} className="border-b border-stone-50 last:border-0">
                <td className="px-4 py-2.5">
                  <span className="font-medium text-gray-900">
                    {r.consultantName || "(participant)"}
                  </span>
                  {r.consultantEmail && (
                    <span className="block text-[11px] text-gray-500">{r.consultantEmail}</span>
                  )}
                  {/* Phones: the Current status column is hidden, so the pill sits here. */}
                  <div className="mt-1 sm:hidden">
                    <AgreementStatusPill status={r.status} />
                  </div>
                </td>
                {showErm && (
                  <td className="px-4 py-2.5 text-gray-700">{r.ermName}</td>
                )}
                <td className="px-4 py-2.5 text-gray-700 whitespace-nowrap">Phase {r.phase ?? 1}</td>
                <td className="px-4 py-2.5 text-gray-600 whitespace-nowrap">
                  {r.decidedAt ? formatUsDayCt(r.decidedAt) : "—"}
                </td>
                <td className="hidden sm:table-cell px-4 py-2.5">
                  <AgreementStatusPill status={r.status} />
                </td>
                <td className={`px-4 py-2.5 ${STICKY_ACTION_TD}`}>
                  <div className="flex flex-col items-start gap-1.5">
                    {/* The Phase 1 executed copy (Manager only), offered once
                        the row is Phase 2: the live signed preview no longer
                        shows the Phase 1 copy there. */}
                    {r.hasPhase1Signed && (r.phase ?? 1) >= 2 && (
                      <div className="flex flex-wrap items-center gap-1.5">
                        <button
                          type="button"
                          onClick={() => openPhase1Preview(r.appId)}
                          disabled={anyBusy}
                          className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold border border-stone-300 bg-white text-sage-navy hover:bg-stone-50 disabled:opacity-50 cursor-pointer whitespace-nowrap"
                        >
                          {busyId === r.appId + ":p1" ? (
                            <Loader2 size={12} className="animate-spin" />
                          ) : (
                            <FileText size={12} />
                          )}
                          Phase 1 signed
                        </button>
                        <DownloadPdfButton
                          label="Download"
                          busy={downloadId === r.appId + ":phase1"}
                          disabled={anyBusy}
                          onClick={() => download(r, "phase1")}
                        />
                      </div>
                    )}
                    {completed ? (
                      <div className="flex flex-wrap items-center gap-1.5">
                        <button
                          type="button"
                          onClick={() => openPreview(r.appId)}
                          disabled={anyBusy}
                          className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold border border-stone-300 bg-white text-sage-navy hover:bg-stone-50 disabled:opacity-50 cursor-pointer whitespace-nowrap"
                        >
                          {busyId === r.appId ? (
                            <Loader2 size={12} className="animate-spin" />
                          ) : (
                            <FileText size={12} />
                          )}
                          {(r.phase ?? 1) >= 2 ? "Phase 2 signed" : "Preview"}
                        </button>
                        <DownloadPdfButton
                          label="Download"
                          busy={downloadId === r.appId + ":final"}
                          disabled={anyBusy}
                          onClick={() => download(r, "final")}
                          title="Save the fully signed agreement as a PDF."
                        />
                      </div>
                    ) : (
                      // The executed copy doesn't exist until the ERM
                      // countersigns; the approver can still take away the
                      // approved version.
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span
                          className="text-[11px] text-gray-400 italic whitespace-nowrap"
                          title="The fully signed copy is available once the ERM has signed the agreement."
                        >
                          Awaiting ERM signature
                        </span>
                        <DownloadPdfButton
                          label="Download approved copy"
                          busy={downloadId === r.appId + ":approved"}
                          disabled={anyBusy}
                          onClick={() => download(r, "approved")}
                          title="Save the version you approved (the ERM signature is still blank)."
                        />
                      </div>
                    )}
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {previewPages !== null && (
        <ApproverPreviewModal
          pages={previewPages}
          onClose={() => setPreviewPages(null)}
        />
      )}
    </div>
  );
}

// The one Download button for every document in the record, so the three
// places can't drift apart.
function DownloadPdfButton({
  label,
  busy,
  disabled,
  onClick,
  title,
}: {
  label: string;
  busy: boolean;
  disabled: boolean;
  onClick: () => void;
  title?: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={title}
      className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold border border-stone-300 bg-white text-sage-navy hover:bg-stone-50 disabled:opacity-50 cursor-pointer whitespace-nowrap"
    >
      {busy ? (
        <Loader2 size={12} className="animate-spin" />
      ) : (
        <Download size={12} />
      )}
      {label}
    </button>
  );
}

/**
 * The saved file's name. Content-Disposition isn't readable here, so the
 * link names the file itself: the server's SageITCO-Agreement_{Name}.pdf
 * shape without the technology-track part (the record carries only the
 * participant's name), with the same prefix per document as the server.
 */
function approverPdfFilename(
  r: WebApproverApprovedItem,
  doc: WebApproverDownloadDoc,
): string {
  const slug = (r.consultantName ?? "")
    .trim()
    .replace(/\s+/g, "-")
    .replace(/[^A-Za-z0-9_-]/g, "")
    .replace(/[-_]+/g, "-")
    .replace(/^[-_]+|[-_]+$/g, "");
  const prefix =
    doc === "phase1"
      ? "phase1-signed-"
      : doc === "approved"
        ? "approved-copy-"
        : "signed-";
  return `${prefix}SageITCO-Agreement_${slug || r.appId}.pdf`;
}
