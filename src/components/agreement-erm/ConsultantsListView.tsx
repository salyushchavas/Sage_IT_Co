"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { AlertCircle, Loader2, Plus, RefreshCw, Search } from "lucide-react";

import {
  fetchMe,
  listConsultantApplications,
  resendConsultantInvite,
  type ConsultantApplication,
  type ConsultantApplicationStatus,
  type ConsultantApplicationsPage,
} from "@/lib/api";
import AgreementStatusPill from "./AgreementStatusPill";
import { formatUsDate } from "@/lib/dates";

const FILTERS: ReadonlyArray<{ id: "ALL" | ConsultantApplicationStatus; label: string }> = [
  { id: "ALL",                label: "All" },
  { id: "DRAFT",              label: "Draft" },
  { id: "SUBMITTED",          label: "Submitted" },
  { id: "REVISION_REQUESTED", label: "Revision" },
  { id: "UPDATED",            label: "Updated" },
  { id: "VERIFIED",           label: "Verified" },
  { id: "SIGNED",             label: "Signed" },
  { id: "COMPLETED",          label: "Completed" },
  { id: "CANCELLED",          label: "Cancelled" },
  // Build Q — no "Expired" filter: agreements never expire (only the
  // consultant link does, surfaced as a per-row "Link expired · Resend").
];

const PAGE_SIZE = 20;

export default function ConsultantsListView() {
  const [filter, setFilter] = useState<"ALL" | ConsultantApplicationStatus>("ALL");
  const [page, setPage] = useState(0);
  const [pageData, setPageData] = useState<ConsultantApplicationsPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");
  // Phase B — super-admin oversight: an "Owner" column + an owner
  // filter, both shown only to the super-admin (an ERM's list is all
  // theirs, so the column would be redundant).
  const [isSuperAdmin, setIsSuperAdmin] = useState(false);
  const [ownerFilter, setOwnerFilter] = useState("ALL");
  // Build Q — bumped after a "Resend link" so the list reloads and the
  // refreshed inviteSentAt clears the "Link expired" indicator.
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    fetchMe()
      .then((me) => {
        if (!cancelled) setIsSuperAdmin(me.role === "SUPER_ADMIN");
      })
      .catch(() => {
        /* non-fatal: column simply stays hidden */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listConsultantApplications({
      status: filter === "ALL" ? undefined : filter,
      page,
      size: PAGE_SIZE,
    })
      .then((data) => {
        if (!cancelled) {
          setPageData(data);
          setError("");
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : "Couldn't load applications");
          setPageData(null);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [filter, page, reloadKey]);

  // Distinct owner names in the current page, for the super-admin's
  // "View" dropdown (client-side filter at this data volume).
  const ownerOptions = useMemo<string[]>(() => {
    const names = new Set<string>();
    (pageData?.content ?? []).forEach((r) => {
      if (r.ownerName) names.add(r.ownerName);
    });
    return Array.from(names).sort();
  }, [pageData]);

  const filtered = useMemo<ConsultantApplication[]>(() => {
    let rows = pageData?.content ?? [];
    if (isSuperAdmin && ownerFilter !== "ALL") {
      rows = rows.filter((r) => (r.ownerName ?? "") === ownerFilter);
    }
    const q = search.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((r) =>
      [r.consultantEmail, r.consultantName ?? "", r.applicationId, r.ownerName ?? ""]
        .some((v) => v.toLowerCase().includes(q)),
    );
  }, [pageData, search, isSuperAdmin, ownerFilter]);

  // Build O/Q — base 7 cols (Consultant, App ID, Status, Manager, Accounts,
  // Sent on, Created); super-admin adds the Owner column. Build Q removed
  // the "Expires" column — agreements never expire (only the consultant
  // link does, shown as the "Link expired · Resend" badge in Status).
  const colCount = isSuperAdmin ? 8 : 7;

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between flex-wrap gap-3">
        <p className="text-sm text-gray-500 max-w-xl">
          Send, track, and sign consulting agreements. Each application
          invites the consultant via email to review and sign on a hidden
          URL — the application ID acts as the credential.
        </p>
        <Link
          href="/agreements/new"
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
        >
          <Plus size={12} /> New agreement
        </Link>
      </div>

      <div className="flex items-center justify-between flex-wrap gap-2">
        <div className="inline-flex flex-wrap rounded-lg border border-gray-200 bg-gray-50 p-1 text-xs">
          {FILTERS.map((f) => (
            <button
              key={f.id}
              type="button"
              onClick={() => {
                setFilter(f.id);
                setPage(0);
              }}
              className={
                "px-2.5 py-1 rounded-md font-semibold cursor-pointer " +
                (filter === f.id
                  ? "bg-sage-navy text-white"
                  : "text-gray-600 hover:text-sage-navy")
              }
            >
              {f.label}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-2">
          {isSuperAdmin && ownerOptions.length > 0 && (
            <select
              value={ownerFilter}
              onChange={(e) => setOwnerFilter(e.target.value)}
              title="Filter by owning ERM"
              className="py-1.5 pl-2.5 pr-7 text-xs rounded-md border border-gray-200 bg-white text-gray-700 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy cursor-pointer"
            >
              <option value="ALL">All ERMs</option>
              {ownerOptions.map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
            </select>
          )}
          <div className="relative">
            <Search
              size={13}
              className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400"
            />
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search email, name, ID…"
              className="pl-8 pr-3 py-1.5 text-xs rounded-md border border-gray-200 w-56 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </div>
        </div>
      </div>

      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}

      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Consultant</th>
              {isSuperAdmin && <th className="text-left px-4 py-2">Owner</th>}
              <th className="text-left px-4 py-2">Application ID</th>
              <th className="text-left px-4 py-2">Status</th>
              <th className="text-left px-4 py-2">Manager</th>
              <th className="text-left px-4 py-2">Accounts</th>
              <th className="text-left px-4 py-2">Sent on</th>
              <th className="text-left px-4 py-2">Created</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={colCount} className="text-center py-8">
                  <Loader2 size={18} className="animate-spin text-sage-navy inline" />
                </td>
              </tr>
            ) : filtered.length === 0 ? (
              <tr>
                <td
                  colSpan={colCount}
                  className="px-4 py-6 text-center text-sm text-gray-400 italic"
                >
                  No applications match this view.
                </td>
              </tr>
            ) : (
              filtered.map((r) => (
                <tr key={r.applicationId} className="hover:bg-gray-50">
                  <td className="px-4 py-2">
                    <Link
                      href={`/agreements/${r.applicationId}`}
                      className="block"
                    >
                      <div className="font-medium text-gray-900">
                        {r.consultantName || "—"}
                      </div>
                      <div className="text-[11px] text-gray-500">
                        {r.consultantEmail}
                      </div>
                    </Link>
                  </td>
                  {isSuperAdmin && (
                    <td className="px-4 py-2 text-xs text-gray-700">
                      {r.ownerName || "—"}
                    </td>
                  )}
                  <td className="px-4 py-2 font-mono text-[11px] text-gray-700">
                    <Link href={`/agreements/${r.applicationId}`}>
                      {r.applicationId.slice(0, 8)}…
                    </Link>
                  </td>
                  <td className="px-4 py-2">
                    <AgreementStatusPill status={r.status} />
                    {/* Build Q — derived "Link expired — resend"; the
                        agreement is never hidden/expired by this. */}
                    {r.linkExpired && (
                      <div className="mt-1">
                        <ResendLinkButton
                          appId={r.applicationId}
                          onResent={() => setReloadKey((k) => k + 1)}
                        />
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-2">
                    <ApprovalBadge status={r.managerStatus} />
                  </td>
                  <td className="px-4 py-2">
                    {/* Phase 1 has no Accounts gate → N/A. */}
                    {(r.phase ?? 1) >= 2 ? (
                      <ApprovalBadge status={r.accountsStatus} />
                    ) : (
                      <span className="text-[11px] text-gray-400">N/A</span>
                    )}
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-500">
                    {formatDate(r.sentForApprovalAt)}
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-500">
                    {formatDate(r.createdAt)}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {pageData && pageData.totalPages > 1 && (
        <div className="flex items-center justify-between text-xs text-gray-600">
          <p>
            Page <span className="font-semibold">{pageData.page + 1}</span> of{" "}
            <span className="font-semibold">{pageData.totalPages}</span> ·{" "}
            {pageData.totalElements} total
          </p>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={pageData.page === 0 || loading}
              className="px-2.5 py-1 rounded-md text-xs font-semibold border border-gray-200 bg-white disabled:opacity-50 hover:bg-gray-50 cursor-pointer disabled:cursor-not-allowed"
            >
              ← Prev
            </button>
            <button
              type="button"
              onClick={() => setPage((p) => p + 1)}
              disabled={!pageData.hasNext || loading}
              className="px-2.5 py-1 rounded-md text-xs font-semibold border border-gray-200 bg-white disabled:opacity-50 hover:bg-gray-50 cursor-pointer disabled:cursor-not-allowed"
            >
              Next →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function formatDate(iso: string | null | undefined) {
  // Build N — US MM-DD-YYYY (was en-IN DD-MM).
  return iso ? formatUsDate(iso) : "—";
}

// Build Q — inline "Link expired · Resend" affordance for the ERM list.
// Issues a fresh 7-day consultant link (resets inviteSentAt, re-emails,
// supersedes old OTPs) WITHOUT changing the agreement's state.
function ResendLinkButton({
  appId,
  onResent,
}: {
  appId: string;
  onResent: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [err, setErr] = useState("");

  const resend = async () => {
    setBusy(true);
    setErr("");
    try {
      await resendConsultantInvite(appId);
      setDone(true);
      // Let the parent reload so the refreshed inviteSentAt clears the badge.
      setTimeout(onResent, 900);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Couldn't resend.");
      setBusy(false);
    }
  };

  if (done) {
    return (
      <span className="inline-flex items-center gap-1 text-[10px] font-semibold text-emerald-700">
        Fresh link sent
      </span>
    );
  }
  return (
    <span className="inline-flex flex-col gap-0.5">
      <button
        type="button"
        onClick={resend}
        disabled={busy}
        title="Issue a fresh 7-day consultant link (the agreement is unchanged)"
        className="inline-flex items-center gap-1 rounded-full border border-amber-300 bg-amber-50 px-2 py-0.5 text-[10px] font-semibold text-amber-800 hover:bg-amber-100 disabled:opacity-50 cursor-pointer"
      >
        {busy ? (
          <Loader2 size={10} className="animate-spin" />
        ) : (
          <RefreshCw size={10} />
        )}
        Link expired · Resend
      </button>
      {err && <span className="text-[10px] text-red-600">{err}</span>}
    </span>
  );
}

// Build O — compact Manager/Accounts gate-status badge for the "All" list.
// null status (never sent for that role) renders a neutral dash.
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
        "inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-semibold " +
        m.cls
      }
    >
      {m.label}
    </span>
  );
}
