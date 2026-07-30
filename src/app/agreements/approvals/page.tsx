"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AlertCircle,
  CheckCircle2,
  ClipboardCheck,
  FileText,
  Loader2,
  RotateCcw,
  Search,
  X,
} from "lucide-react";

import AgreementErmShell from "@/components/agreement-erm/AgreementErmShell";
import AgreementStatusPill from "@/components/agreement-erm/AgreementStatusPill";
import {
  approverApprove,
  approverFetchApplications,
  approverFetchApproved,
  approverFetchQueue,
  approverRequestRevision,
  fetchApproverLatestVersionPreviewImages,
  fetchApproverVersionPreviewImages,
  fetchApproverSignedPreviewImages,
  fetchApproverPhase1SignedPreviewImages,
  fetchMe,
  getAgreementErmToken,
  type ApproverApprovedItem,
  type ApproverQueueItem,
  type ApproverRole,
  type ConsultantApplication,
  type ConsultantApplicationStatus,
} from "@/lib/api";
import { computePendingAppendices } from "@/lib/pending-appendix";
import { AGREEMENT_STATUS_META, STAGE_META } from "@/lib/agreement-status";
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
  // Build L — Pending queue vs the read-only "Approved" record. Build AI adds
  // an "All agreements" status table (every agreement routed to me).
  const [tab, setTab] = useState<"pending" | "all" | "approved">("pending");
  const [approved, setApproved] = useState<ApproverApprovedItem[]>([]);
  const [approvedLoading, setApprovedLoading] = useState(false);
  const [approvedLoaded, setApprovedLoaded] = useState(false);
  // Build AI — the full status list (lazy-loaded on first open).
  const [allApps, setAllApps] = useState<ConsultantApplication[]>([]);
  const [allLoading, setAllLoading] = useState(false);
  const [allLoaded, setAllLoaded] = useState(false);

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

  const loadApproved = useCallback(async () => {
    setApprovedLoading(true);
    setError("");
    try {
      setApproved(await approverFetchApproved());
      setApprovedLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load your approved record.");
    } finally {
      setApprovedLoading(false);
    }
  }, []);

  const loadAll = useCallback(async () => {
    setAllLoading(true);
    setError("");
    try {
      setAllApps(await approverFetchApplications());
      setAllLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load your agreements.");
    } finally {
      setAllLoading(false);
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

  // Build L — lazily load the approved record the first time that tab opens.
  useEffect(() => {
    if (checked && tab === "approved" && !approvedLoaded) {
      void loadApproved();
    }
  }, [checked, tab, approvedLoaded, loadApproved]);

  // Build AI — lazily load the full status list the first time that tab opens.
  useEffect(() => {
    if (checked && tab === "all" && !allLoaded) {
      void loadAll();
    }
  }, [checked, tab, allLoaded, loadAll]);

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

      {/* Build L — Pending queue / Approved record tabs. */}
      <div className="mb-5 inline-flex rounded-lg border border-stone-200 bg-white p-0.5">
        <button
          type="button"
          onClick={() => setTab("pending")}
          className={
            "px-3.5 py-1.5 rounded-md text-xs font-semibold cursor-pointer " +
            (tab === "pending" ? "bg-sage-navy text-white" : "text-gray-600 hover:bg-gray-50")
          }
        >
          Pending approval
        </button>
        <button
          type="button"
          onClick={() => setTab("all")}
          className={
            "px-3.5 py-1.5 rounded-md text-xs font-semibold cursor-pointer " +
            (tab === "all" ? "bg-sage-navy text-white" : "text-gray-600 hover:bg-gray-50")
          }
        >
          All agreements
        </button>
        <button
          type="button"
          onClick={() => setTab("approved")}
          className={
            "px-3.5 py-1.5 rounded-md text-xs font-semibold cursor-pointer " +
            (tab === "approved" ? "bg-sage-navy text-white" : "text-gray-600 hover:bg-gray-50")
          }
        >
          Approved agreements
        </button>
      </div>

      {tab === "pending" ? (
        loading ? (
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
        )
      ) : tab === "all" ? (
        <AllAgreementsTable loading={allLoading} rows={allApps} />
      ) : (
        <ApprovedRecord
          loading={approvedLoading}
          items={approved}
          // PART D — the Accounts approver spans all ERMs, so split the
          // record by originating ERM; the Manager sees a flat list.
          groupByErm={role === "ACCOUNTS"}
        />
      )}
    </AgreementErmShell>
  );
}

// Build AI — the approver's read-only "All agreements" status table: every
// agreement routed to them, filterable by status + searchable, mirroring the
// ERM's ConsultantsListView (minus owner-only actions). Actions live on the
// Pending tab; this view is informational.
/** Caption straight from the shared vocabulary — never spelled out here. */
const L = (s: ConsultantApplicationStatus) => AGREEMENT_STATUS_META[s].label;

/**
 * Tabs re-derived from src/lib/agreement-status.ts. Two faults are fixed here:
 *
 *  - the captions were this page's own invention, so a row whose pill read
 *    "Signed by consultant" was filed under a tab called "In revision";
 *  - that bucket merged VERIFIED (consultant signed, nothing wrong with it)
 *    and UPDATED (a stalled legacy state) in with the two genuine revision
 *    states, hiding exactly the distinction an approver is scanning for.
 *
 * Every single-status bucket takes its caption from AGREEMENT_STATUS_META, so
 * a tab can no longer drift from the pill beneath it. The two multi-status
 * buckets are named for what their members share: an approver declining a
 * gate IS a request for changes, and CANCELLED/EXPIRED are both stage
 * "closed" — that caption comes from STAGE_META rather than a literal.
 *
 * SIGNED and EXPIRED are retired values, but they still bucket: this table
 * renders whatever the API returns and historical rows can carry them.
 * UPDATED is deliberately unbucketed — it is a stuck state an approver has no
 * action for, so it surfaces under "All" only.
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
  {
    id: "COMPLETED",
    label: L("COMPLETED"),
    match: (s) => ["COMPLETED", "SIGNED"].includes(s),
  },
  {
    id: "CLOSED",
    label: STAGE_META.closed.label,
    match: (s) => ["CANCELLED", "EXPIRED"].includes(s),
  },
];

function AllAgreementsTable({
  loading,
  rows,
}: {
  loading: boolean;
  rows: ConsultantApplication[];
}) {
  const [statusTab, setStatusTab] = useState("ALL");
  const [search, setSearch] = useState("");
  // Build AJ — inline preview of the latest consultant version (reuses the
  // approver preview modal; no scroll-gate since this view is read-only).
  const [previewPages, setPreviewPages] = useState<string[] | null>(null);
  const [previewTitle, setPreviewTitle] = useState<string | undefined>(undefined);
  const [previewBusy, setPreviewBusy] = useState<string | null>(null);
  const [previewErr, setPreviewErr] = useState("");

  const openPreview = async (appId: string, name: string) => {
    setPreviewBusy(appId);
    setPreviewErr("");
    try {
      const data = await fetchApproverLatestVersionPreviewImages(appId);
      setPreviewPages(data.pages);
      setPreviewTitle(`Latest version — ${name}`);
    } catch (e) {
      setPreviewErr(e instanceof Error ? e.message : "Couldn't load the preview.");
    } finally {
      setPreviewBusy(null);
    }
  };

  const active = APPROVER_STATUS_TABS.find((t) => t.id === statusTab) ?? APPROVER_STATUS_TABS[0];
  const q = search.trim().toLowerCase();
  const filtered = rows.filter((r) => {
    if (!active.match(r.status)) return false;
    if (!q) return true;
    return [r.consultantEmail, r.consultantName ?? "", r.applicationId, r.ownerName ?? ""].some(
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
              className={
                "px-2.5 py-1 rounded-md font-semibold cursor-pointer " +
                (statusTab === t.id ? "bg-sage-navy text-white" : "text-gray-600 hover:text-sage-navy")
              }
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="relative">
          <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400" />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search email, name, ID…"
            className="pl-8 pr-3 py-1.5 text-xs rounded-md border border-gray-200 w-56 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
          />
        </div>
      </div>

      {previewErr && (
        <p className="inline-flex items-center gap-1.5 text-xs text-red-700">
          <AlertCircle size={12} /> {previewErr}
        </p>
      )}

      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Consultant</th>
              <th className="text-left px-4 py-2">ERM</th>
              <th className="text-left px-4 py-2">Status</th>
              <th className="text-left px-4 py-2">Pending Appendix</th>
              <th className="text-left px-4 py-2">Manager</th>
              <th className="text-left px-4 py-2">Accounts</th>
              <th className="text-left px-4 py-2">Sent on</th>
              <th className="text-left px-4 py-2">Created</th>
              <th className="text-right px-4 py-2">Preview</th>
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
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-700">{r.ownerName || "—"}</td>
                  <td className="px-4 py-2">
                    <AgreementStatusPill status={r.status} />
                  </td>
                  <td className="px-4 py-2 align-top">
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
                  <td className="px-4 py-2 text-xs text-gray-500">
                    {r.sentForApprovalAt ? formatUsDate(r.sentForApprovalAt) : "—"}
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-500">
                    {r.createdAt ? formatUsDate(r.createdAt) : "—"}
                  </td>
                  <td className="px-4 py-2 text-right">
                    <button
                      type="button"
                      onClick={() =>
                        openPreview(r.applicationId, r.consultantName || r.consultantEmail)
                      }
                      disabled={previewBusy !== null}
                      className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold border border-stone-300 bg-white text-sage-navy hover:bg-stone-50 disabled:opacity-50 cursor-pointer"
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

// Mirrors ConsultantsListView's compact appendix summary (chips that expand to
// the per-appendix "not sent / awaiting signature" detail).
function PendingAppendixCell({ app }: { app: ConsultantApplication }) {
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
          <span className="inline-flex items-center rounded-full border border-gray-200 bg-gray-50 px-2 py-0.5 text-[10px] font-semibold text-gray-600">
            {notSent} not sent
          </span>
        )}
        {awaiting > 0 && (
          <span className="inline-flex items-center rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-[10px] font-semibold text-amber-700">
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

// Compact Manager/Accounts gate-status badge (mirrors ConsultantsListView).
function ApprovalBadge({ status }: { status: string | null | undefined }) {
  if (!status) return <span className="text-[11px] text-gray-400">—</span>;
  const map: Record<string, { label: string; cls: string }> = {
    APPROVED: { label: "Approved", cls: "bg-emerald-50 text-emerald-700 border-emerald-200" },
    PENDING: { label: "Pending", cls: "bg-amber-50 text-amber-700 border-amber-200" },
    REVISION_REQUESTED: { label: "Revision", cls: "bg-rose-50 text-rose-700 border-rose-200" },
  };
  const m = map[status] ?? { label: status, cls: "bg-gray-50 text-gray-600 border-gray-200" };
  return (
    <span
      className={
        "inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-semibold " + m.cls
      }
    >
      {m.label}
    </span>
  );
}

// Build L — read-only record of agreements this approver has approved.
function ApprovedRecord({
  loading,
  items,
  groupByErm,
}: {
  loading: boolean;
  items: ApproverApprovedItem[];
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

  // PART D — group by originating ERM (preserve first-seen order).
  const groups = new Map<string, ApproverApprovedItem[]>();
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
          <h3 className="text-xs font-bold uppercase tracking-wider text-sage-navy mb-2 inline-flex items-center gap-1.5">
            <FileText size={12} /> {erm}
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
  rows: ApproverApprovedItem[];
  showErm: boolean;
}) {
  // Build O — non-copyable preview of the FINAL ERM-signed agreement,
  // available once the agreement is COMPLETED. Shared modal with the
  // pending queue; one preview open at a time across the table.
  const [previewPages, setPreviewPages] = useState<string[] | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [previewErr, setPreviewErr] = useState("");

  const openPreview = async (appId: string) => {
    setBusyId(appId);
    setPreviewErr("");
    try {
      const data = await fetchApproverSignedPreviewImages(appId);
      setPreviewPages(data.pages);
    } catch (e) {
      setPreviewErr(e instanceof Error ? e.message : "Couldn't load the signed agreement.");
    } finally {
      setBusyId(null);
    }
  };

  // Build S — preview the durable Phase-1 signed agreement (Manager only),
  // independent of the current phase/COMPLETED state. Shares the one modal.
  const openPhase1Preview = async (appId: string) => {
    setBusyId(appId + ":p1");
    setPreviewErr("");
    try {
      const data = await fetchApproverPhase1SignedPreviewImages(appId);
      setPreviewPages(data.pages);
    } catch (e) {
      setPreviewErr(
        e instanceof Error ? e.message : "Couldn't load the Phase 1 signed agreement.");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="bg-white rounded-2xl border border-stone-200 shadow-sm overflow-x-auto">
      {previewErr && (
        <p className="px-4 pt-3 text-xs text-red-600 inline-flex items-center gap-1">
          <AlertCircle size={12} /> {previewErr}
        </p>
      )}
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-[11px] uppercase tracking-wider text-gray-500 border-b border-stone-100">
            <th className="px-4 py-2.5 font-semibold">Consultant</th>
            {showErm && <th className="px-4 py-2.5 font-semibold">ERM</th>}
            <th className="px-4 py-2.5 font-semibold">Phase</th>
            <th className="px-4 py-2.5 font-semibold">Approved</th>
            <th className="px-4 py-2.5 font-semibold">Current status</th>
            <th className="px-4 py-2.5 font-semibold">Document</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const completed = r.status === "COMPLETED";
            return (
              <tr key={r.appId} className="border-b border-stone-50 last:border-0">
                <td className="px-4 py-2.5">
                  <span className="font-medium text-gray-900">
                    {r.consultantName || "(consultant)"}
                  </span>
                  {r.consultantEmail && (
                    <span className="block text-[11px] text-gray-500">{r.consultantEmail}</span>
                  )}
                </td>
                {showErm && (
                  <td className="px-4 py-2.5 text-gray-700">{r.ermName}</td>
                )}
                <td className="px-4 py-2.5 text-gray-700">Phase {r.phase ?? 1}</td>
                <td className="px-4 py-2.5 text-gray-600">
                  {r.decidedAt ? formatUsDate(r.decidedAt) : "—"}
                </td>
                <td className="px-4 py-2.5">
                  <AgreementStatusPill status={r.status as ConsultantApplicationStatus} />
                </td>
                <td className="px-4 py-2.5">
                  <div className="flex flex-col items-start gap-1.5">
                    {/* Build S — durable Phase-1 signed agreement (Manager
                        only; shown once in/past Phase 2, where the live final
                        preview no longer reflects the Phase-1 signed copy). */}
                    {r.hasPhase1Signed && (r.phase ?? 1) >= 2 && (
                      <button
                        type="button"
                        onClick={() => openPhase1Preview(r.appId)}
                        disabled={busyId !== null}
                        className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold border border-stone-300 bg-white text-sage-navy hover:bg-stone-50 disabled:opacity-50 cursor-pointer"
                      >
                        {busyId === r.appId + ":p1" ? (
                          <Loader2 size={12} className="animate-spin" />
                        ) : (
                          <FileText size={12} />
                        )}
                        Phase 1 signed
                      </button>
                    )}
                    {completed ? (
                      <button
                        type="button"
                        onClick={() => openPreview(r.appId)}
                        disabled={busyId !== null}
                        className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold border border-stone-300 bg-white text-sage-navy hover:bg-stone-50 disabled:opacity-50 cursor-pointer"
                      >
                        {busyId === r.appId ? (
                          <Loader2 size={12} className="animate-spin" />
                        ) : (
                          <FileText size={12} />
                        )}
                        {(r.phase ?? 1) >= 2 ? "Phase 2 signed" : "Preview"}
                      </button>
                    ) : (
                      <span
                        className="text-[11px] text-gray-400 italic"
                        title="Available once the ERM has signed the agreement."
                      >
                        Awaiting ERM signature
                      </span>
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
  // Build Y — in-app non-copyable preview (watermarked PNGs, no PDF tab).
  const [previewPages, setPreviewPages] = useState<string[] | null>(null);
  // Build V — the frozen consultant version this round is reviewing (null when
  // the round predates versioning and falls back to a live render).
  const [previewVersion, setPreviewVersion] = useState<number | null>(null);
  // Build AE — the approver must scroll through the FULL agreement preview
  // before Approve / Request revision unlock (mirrors the consultant gate).
  const [previewedFully, setPreviewedFully] = useState(false);

  const previewAgreement = async () => {
    setBusy("preview");
    setErr("");
    try {
      // Build V — review the FROZEN version the ERM routed for this round
      // (the backend falls back to a live render for legacy rounds).
      const data = await fetchApproverVersionPreviewImages(app.applicationId);
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
      </div>

      {previewPages !== null && (
        <ApproverPreviewModal
          pages={previewPages}
          title={
            previewVersion != null
              ? `Agreement preview — Version V${previewVersion}`
              : undefined
          }
          onScrolledToEnd={() => setPreviewedFully(true)}
          onClose={() => {
            setPreviewPages(null);
            setPreviewVersion(null);
          }}
        />
      )}

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
        <div className="mt-4">
          {/* Build AE — order: 1. Preview  2. Request revision  3. Approve.
              Approve + Request revision stay disabled until the approver has
              scrolled through the whole agreement preview. */}
          <div className="flex items-center gap-2 flex-wrap">
            <button
              type="button"
              onClick={previewAgreement}
              disabled={busy !== null}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-semibold border border-stone-300 bg-white text-sage-navy hover:bg-stone-50 disabled:opacity-50 cursor-pointer"
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
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-semibold border border-stone-300 text-gray-700 hover:bg-stone-50 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
            >
              <RotateCcw size={12} /> Request revision
            </button>
            <button
              type="button"
              onClick={approve}
              disabled={busy !== null || !previewedFully}
              title={!previewedFully ? "Preview the full agreement first" : undefined}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 disabled:cursor-not-allowed cursor-pointer"
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
            <p className="mt-2 text-[11px] text-sage-copper-deep inline-flex items-center gap-1.5">
              <AlertCircle size={12} /> Open the preview and scroll to the end of
              the agreement to enable Approve / Request revision.
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

/**
 * Build Y — non-copyable approver preview. Renders the watermarked PNG
 * pages in a modal with text-selection, copy/cut, context-menu, and drag
 * all blocked (deterrent only — won't stop screenshots). No PDF/file
 * ever reaches the browser, so there is nothing to "save as".
 */
function ApproverPreviewModal({
  pages,
  onClose,
  title,
  onScrolledToEnd,
}: {
  pages: string[];
  onClose: () => void;
  title?: string;
  onScrolledToEnd?: () => void;
}) {
  const scrollerRef = useRef<HTMLDivElement | null>(null);
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  // Build AE — fire onScrolledToEnd when the end-sentinel enters the modal's
  // scroll viewport (the approver reached the bottom), or immediately when a
  // short agreement already fits without scrolling.
  useEffect(() => {
    const root = scrollerRef.current;
    const sentinel = sentinelRef.current;
    if (!root || !sentinel || !onScrolledToEnd) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) onScrolledToEnd();
      },
      { root, threshold: 0 },
    );
    io.observe(sentinel);
    return () => io.disconnect();
  }, [pages, onScrolledToEnd]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-2 sm:p-3"
      onClick={onClose}
    >
      <div
        className="bg-white w-full max-w-6xl h-[96vh] rounded-xl shadow-xl flex flex-col overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="shrink-0 flex items-center justify-between px-4 py-3 border-b border-stone-100">
          <p className="text-sm font-bold text-sage-navy inline-flex items-center gap-1.5">
            <FileText size={14} /> {title ?? "Agreement preview"}
          </p>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close preview"
            className="text-gray-500 hover:text-sage-navy cursor-pointer"
          >
            <X size={16} />
          </button>
        </header>
        <div
          ref={scrollerRef}
          className="bg-stone-100 p-4 flex-1 min-h-0 overflow-y-auto space-y-3 select-none"
          style={{ userSelect: "none", WebkitUserSelect: "none", MozUserSelect: "none" }}
          onContextMenu={(e) => e.preventDefault()}
          onCopy={(e) => e.preventDefault()}
          onCut={(e) => e.preventDefault()}
          onDragStart={(e) => e.preventDefault()}
        >
          {pages.length === 0 ? (
            <p className="text-center text-xs text-gray-500 py-12">
              Nothing to preview.
            </p>
          ) : (
            <>
              {pages.map((b64, i) => (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  key={i}
                  src={`data:image/png;base64,${b64}`}
                  alt={`Agreement page ${i + 1}`}
                  draggable={false}
                  onDragStart={(e) => e.preventDefault()}
                  onContextMenu={(e) => e.preventDefault()}
                  className="block w-full rounded-md border border-stone-200 shadow-sm"
                  style={{ userSelect: "none", WebkitUserSelect: "none" }}
                />
              ))}
              {/* Build AE — read-to-end sentinel (drives the approve/revise gate). */}
              <div ref={sentinelRef} aria-hidden className="h-2 w-full" />
            </>
          )}
        </div>
      </div>
    </div>
  );
}
