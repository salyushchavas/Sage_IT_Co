"use client";

import { useState } from "react";
import { AlertCircle, FileText, Loader2, Search } from "lucide-react";

import {
  webApproverLatestVersionImages,
  type WebAgreement,
  type WebAgreementStatus,
  type WebApprovalDecision,
} from "@/lib/api";
import { formatUsDayCt } from "@/lib/datetime";
import { computePendingAppendices } from "@/lib/web-agreement-pending-appendix";
import {
  AGREEMENT_STATUS_META,
  APPROVAL_DECISION_META,
  STAGE_META,
} from "@/lib/web-agreement-status";
import AgreementStatusPill from "@/components/web-agreement/erm/AgreementStatusPill";
import {
  Chip,
  STICKY_ACTION_TD,
  STICKY_ACTION_TH,
} from "@/components/web-agreement/ui/primitives";
import ApproverPreviewModal from "./ApproverPreviewModal";

/**
 * The approver's read-only "All agreements" table: every agreement ever
 * routed to them in their gate, any round and any status. The website's copy
 * of the console approvals page's AllAgreementsTable
 * (src/app/agreements/approvals/page.tsx). Decisions live on the Pending
 * tab; here the approver only looks.
 *
 * Preview shows the LATEST version, not necessarily the one they reviewed,
 * and has no scroll gate (as in the console).
 *
 * Phones: the table scrolls sideways inside its card with Preview pinned on
 * the right; Status moves under the participant's name below `sm`, and
 * Pending Appendix, Sent on and Created hide below `md`.
 */

/** Caption straight from the shared vocabulary — never spelled out here. */
const L = (s: WebAgreementStatus) => AGREEMENT_STATUS_META[s].label;

/**
 * Status tabs, as the console derives them from its status file. Every
 * single-status tab takes its caption from AGREEMENT_STATUS_META, so a tab
 * can't drift from the pill under it ("Signed by participant" is the plain
 * VERIFIED label). A decline by an approver is a request for changes too, so
 * it shares that tab; CANCELLED is the "closed" stage. The console's SIGNED,
 * EXPIRED and UPDATED values don't exist on the website.
 */
const APPROVER_STATUS_TABS: ReadonlyArray<{
  id: string;
  label: string;
  match: (status: string) => boolean;
}> = [
  { id: "ALL", label: "All", match: () => true },
  {
    id: "AWAITING",
    label: L("AWAITING_APPROVALS"),
    match: (s) => s === "AWAITING_APPROVALS",
  },
  { id: "VERIFIED", label: L("VERIFIED"), match: (s) => s === "VERIFIED" },
  {
    id: "REVISION",
    label: L("REVISION_REQUESTED"),
    match: (s) => ["REVISION_REQUESTED", "APPROVAL_REVISION_REQUESTED"].includes(s),
  },
  { id: "READY", label: L("READY_TO_SIGN"), match: (s) => s === "READY_TO_SIGN" },
  { id: "COMPLETED", label: L("COMPLETED"), match: (s) => s === "COMPLETED" },
  {
    id: "CLOSED",
    label: STAGE_META.closed.label,
    match: (s) => s === "CANCELLED",
  },
];

export default function ApproverAllAgreements({
  loading,
  rows,
}: {
  loading: boolean;
  rows: WebAgreement[];
}) {
  const [statusTab, setStatusTab] = useState("ALL");
  const [search, setSearch] = useState("");
  const [previewPages, setPreviewPages] = useState<string[] | null>(null);
  const [previewTitle, setPreviewTitle] = useState<string | undefined>(undefined);
  const [previewBusy, setPreviewBusy] = useState<string | null>(null);
  const [previewErr, setPreviewErr] = useState("");

  const openPreview = async (appId: string, name: string) => {
    setPreviewBusy(appId);
    setPreviewErr("");
    try {
      const data = await webApproverLatestVersionImages(appId);
      setPreviewPages(data.pages);
      setPreviewTitle(`Latest version — ${name}`);
    } catch (e) {
      setPreviewErr(e instanceof Error ? e.message : "Couldn't load the preview.");
    } finally {
      setPreviewBusy(null);
    }
  };

  const active =
    APPROVER_STATUS_TABS.find((t) => t.id === statusTab) ?? APPROVER_STATUS_TABS[0];
  const q = search.trim().toLowerCase();
  const filtered = rows.filter((r) => {
    if (!active.match(r.status)) return false;
    if (!q) return true;
    return [r.consultantEmail ?? "", r.consultantName ?? "", r.applicationId, r.ownerName ?? ""].some(
      (v) => v.toLowerCase().includes(q),
    );
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div className="inline-flex flex-wrap rounded-lg border border-gray-200 bg-gray-50 p-1 text-xs">
          {APPROVER_STATUS_TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => setStatusTab(t.id)}
              aria-pressed={statusTab === t.id}
              className={
                "px-2.5 py-1 rounded-md font-semibold cursor-pointer " +
                (statusTab === t.id ? "bg-sage-navy text-white" : "text-gray-600 hover:text-sage-navy")
              }
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="relative max-w-full">
          <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400" />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search email, name, ID…"
            aria-label="Search by email, name, agreement ID or ERM"
            className="pl-8 pr-3 py-1.5 text-xs rounded-md border border-gray-200 w-56 max-w-full focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
          />
        </div>
      </div>

      {previewErr && (
        <p className="inline-flex items-start gap-1.5 text-xs text-red-700">
          <AlertCircle size={12} className="mt-0.5 shrink-0" /> <span>{previewErr}</span>
        </p>
      )}

      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Participant</th>
              <th className="text-left px-4 py-2">ERM</th>
              <th className="hidden sm:table-cell text-left px-4 py-2">Status</th>
              <th className="hidden md:table-cell text-left px-4 py-2">Pending Appendix</th>
              <th className="text-left px-4 py-2">Manager</th>
              <th className="text-left px-4 py-2">Accounts</th>
              <th className="hidden md:table-cell text-left px-4 py-2 whitespace-nowrap">Sent on</th>
              <th className="hidden md:table-cell text-left px-4 py-2">Created</th>
              <th className={`text-right px-4 py-2 ${STICKY_ACTION_TH}`}>Preview</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={9} className="text-center py-8">
                  <Loader2 size={18} className="animate-spin text-sage-navy inline" />
                </td>
              </tr>
            ) : filtered.length === 0 ? (
              <tr>
                <td colSpan={9} className="px-4 py-6 text-center text-sm text-gray-400 italic">
                  No agreements match this view.
                </td>
              </tr>
            ) : (
              filtered.map((r) => (
                <tr key={r.applicationId} className="hover:bg-gray-50">
                  <td className="px-4 py-2">
                    <div className="font-medium text-gray-900">{r.consultantName || "—"}</div>
                    <div className="text-[11px] text-gray-500">{r.consultantEmail}</div>
                    {/* Phones: the Status column is hidden, so the pill sits here. */}
                    <div className="mt-1 sm:hidden">
                      <AgreementStatusPill status={r.status} />
                    </div>
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-700">{r.ownerName || "—"}</td>
                  <td className="hidden sm:table-cell px-4 py-2">
                    <AgreementStatusPill status={r.status} />
                  </td>
                  <td className="hidden md:table-cell px-4 py-2 align-top">
                    <PendingAppendixCell app={r} />
                  </td>
                  <td className="px-4 py-2">
                    <ApprovalBadge status={r.managerStatus} />
                  </td>
                  <td className="px-4 py-2">
                    {(r.phase ?? 1) >= 2 ? (
                      <ApprovalBadge status={r.accountsStatus} />
                    ) : (
                      <span className="text-[11px] text-gray-400">N/A</span>
                    )}
                  </td>
                  <td className="hidden md:table-cell px-4 py-2 text-xs text-gray-500 whitespace-nowrap">
                    {r.sentForApprovalAt ? formatUsDayCt(r.sentForApprovalAt) : "—"}
                  </td>
                  <td className="hidden md:table-cell px-4 py-2 text-xs text-gray-500 whitespace-nowrap">
                    {r.createdAt ? formatUsDayCt(r.createdAt) : "—"}
                  </td>
                  <td className={`px-4 py-2 text-right ${STICKY_ACTION_TD}`}>
                    <button
                      type="button"
                      onClick={() =>
                        openPreview(r.applicationId, r.consultantName || r.consultantEmail)
                      }
                      disabled={previewBusy !== null}
                      className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold border border-stone-300 bg-white text-sage-navy hover:bg-stone-50 disabled:opacity-50 cursor-pointer whitespace-nowrap"
                    >
                      {previewBusy === r.applicationId ? (
                        <Loader2 size={12} className="animate-spin" />
                      ) : (
                        <FileText size={12} />
                      )}
                      Preview
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {previewPages !== null && (
        <ApproverPreviewModal
          pages={previewPages}
          title={previewTitle}
          onClose={() => setPreviewPages(null)}
        />
      )}
    </div>
  );
}

// The ERM list's compact appendix summary: "None" when all five appendices
// are sent and signed, otherwise chips ("N not sent" / "N awaiting") that
// expand to the per-appendix detail.
function PendingAppendixCell({ app }: { app: WebAgreement }) {
  const pending = computePendingAppendices(app);
  if (pending.length === 0) {
    return <span className="text-[11px] text-gray-400">None</span>;
  }
  const notSent = pending.filter((p) => p.state === "not-sent").length;
  const awaiting = pending.filter((p) => p.state === "awaiting").length;
  return (
    <details className="group">
      <summary className="flex flex-wrap items-center gap-1 cursor-pointer list-none [&::-webkit-details-marker]:hidden">
        {notSent > 0 && (
          <span className="inline-flex items-center whitespace-nowrap rounded-full border border-gray-200 bg-gray-50 px-2 py-0.5 text-[10px] font-semibold text-gray-600">
            {notSent} not sent
          </span>
        )}
        {awaiting > 0 && (
          <span className="inline-flex items-center whitespace-nowrap rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-[10px] font-semibold text-amber-700">
            {awaiting} awaiting
          </span>
        )}
      </summary>
      <ul className="mt-1.5 space-y-1">
        {pending.map((p) => (
          <li key={p.n} className="flex items-start gap-1.5 text-[11px] text-gray-600">
            <span
              className={
                "inline-flex shrink-0 items-center rounded-full border px-1.5 py-0.5 text-[9px] font-semibold " +
                (p.state === "not-sent"
                  ? "border-gray-200 bg-gray-50 text-gray-500"
                  : "border-amber-200 bg-amber-50 text-amber-700")
              }
            >
              {p.state === "not-sent" ? "Not sent" : "Awaiting signature"}
            </span>
            <span>{p.label}</span>
          </li>
        ))}
      </ul>
    </details>
  );
}

// A Manager / Accounts gate's latest decision. Wording and colour come from
// APPROVAL_DECISION_META, so the approver's table names a decision exactly as
// the ERM's board and list do; "—" when the gate was never opened.
function ApprovalBadge({ status }: { status: string | null | undefined }) {
  if (!status) return <span className="text-[11px] text-gray-400">—</span>;
  const m = APPROVAL_DECISION_META[status as WebApprovalDecision];
  if (!m) return <Chip>{status}</Chip>;
  return <Chip tone={m.tone}>{m.label}</Chip>;
}
