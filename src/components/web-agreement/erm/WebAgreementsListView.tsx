"use client";

import { useEffect, useMemo, useState } from "react";
import { AlertCircle, Loader2, Search } from "lucide-react";

import {
  listWebAgreements,
  type WebAgreement,
  type WebAgreementPage,
  type WebAgreementStatus,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import AgreementStatusPill from "./AgreementStatusPill";
import { formatUsDate } from "@/lib/dates";
import { computePendingAppendices } from "@/lib/pending-appendix";
import {
  AGREEMENT_STATUS_META,
  LIVE_STATUSES,
} from "@/lib/web-agreement-status";

/**
 * The website agreement's copy of the console's agreements list
 * (src/components/agreement-erm/ConsultantsListView.tsx), without the
 * approval columns (Manager, Accounts, Sent on) and without the link-expiry
 * resend: the participant signs in to the website, so there is no link to
 * expire. Rows open the detail in place (onOpen) instead of navigating.
 *
 * Chips are generated from the shared vocabulary, never written here, so a
 * chip caption can't drift from the pill on the row it selects. Filtering
 * itself sends the raw enum to the API.
 */
const FILTERS: ReadonlyArray<{ id: "ALL" | WebAgreementStatus; label: string }> = [
  { id: "ALL", label: "All" },
  ...LIVE_STATUSES.map((id) => ({ id, label: AGREEMENT_STATUS_META[id].label })),
];

const PAGE_SIZE = 20;

/** Roles that see every ERM's agreements (the console's super-admin). */
const ADMIN_ROLES = new Set(["OPERATIONS_ADMIN", "SYSTEM_ADMIN"]);

export default function WebAgreementsListView({
  onOpen,
}: {
  onOpen: (applicationId: string) => void;
}) {
  const { user } = useAuth();
  const [filter, setFilter] = useState<"ALL" | WebAgreementStatus>("ALL");
  const [page, setPage] = useState(0);
  const [pageData, setPageData] = useState<WebAgreementPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");
  // Admin oversight: an "Owner" column + an owner filter, both shown only to
  // Operations / System admins (an ERM's list is all theirs, so the column
  // would be redundant).
  const isAdmin = ADMIN_ROLES.has((user?.role ?? "").toUpperCase());
  const [ownerFilter, setOwnerFilter] = useState("ALL");

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listWebAgreements({
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
          setError(e instanceof Error ? e.message : "Couldn't load agreements");
          setPageData(null);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [filter, page]);

  // Distinct owner names in the current page, for the admin's owner
  // dropdown (client-side filter at this data volume).
  const ownerOptions = useMemo<string[]>(() => {
    const names = new Set<string>();
    (pageData?.content ?? []).forEach((r) => {
      if (r.ownerName) names.add(r.ownerName);
    });
    return Array.from(names).sort();
  }, [pageData]);

  const filtered = useMemo<WebAgreement[]>(() => {
    let rows = pageData?.content ?? [];
    if (isAdmin && ownerFilter !== "ALL") {
      rows = rows.filter((r) => (r.ownerName ?? "") === ownerFilter);
    }
    const q = search.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((r) =>
      [r.consultantEmail, r.consultantName ?? "", r.applicationId, r.ownerName ?? ""]
        .some((v) => v.toLowerCase().includes(q)),
    );
  }, [pageData, search, isAdmin, ownerFilter]);

  // Base 5 cols (Participant, Agreement ID, Status, Pending Appendix,
  // Created); admins add the Owner column.
  const colCount = isAdmin ? 6 : 5;

  return (
    <div className="space-y-4">
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
        <div className="flex items-center gap-2 flex-wrap">
          {isAdmin && ownerOptions.length > 0 && (
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
              className="pl-8 pr-3 py-1.5 text-xs rounded-md border border-gray-200 w-56 max-w-full focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </div>
        </div>
      </div>

      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}

      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Participant</th>
              {isAdmin && <th className="text-left px-4 py-2">Owner</th>}
              <th className="text-left px-4 py-2">Agreement ID</th>
              <th className="text-left px-4 py-2">Status</th>
              <th className="text-left px-4 py-2">Pending Appendix</th>
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
                  No agreements match this view.
                </td>
              </tr>
            ) : (
              filtered.map((r) => (
                <tr key={r.applicationId} className="hover:bg-gray-50">
                  <td className="px-4 py-2">
                    <button
                      type="button"
                      onClick={() => onOpen(r.applicationId)}
                      className="block text-left cursor-pointer"
                    >
                      <div className="font-medium text-gray-900">
                        {r.consultantName || "—"}
                      </div>
                      <div className="text-[11px] text-gray-500">
                        {r.consultantEmail}
                      </div>
                    </button>
                  </td>
                  {isAdmin && (
                    <td className="px-4 py-2 text-xs text-gray-700">
                      {r.ownerName || "—"}
                    </td>
                  )}
                  <td className="px-4 py-2 font-mono text-[11px] text-gray-700">
                    <button
                      type="button"
                      onClick={() => onOpen(r.applicationId)}
                      className="cursor-pointer hover:text-sage-navy"
                    >
                      {r.applicationId.slice(0, 8)}…
                    </button>
                  </td>
                  <td className="px-4 py-2">
                    {/* List rows carry the refining fields, so the pill can
                        resolve the sub-state ("Verified" vs "Signed by
                        participant") instead of the coarse enum label. */}
                    <AgreementStatusPill
                      status={r.status}
                      context={{
                        consultantCopyReleased: r.consultantCopyReleased,
                        phase: r.phase,
                      }}
                    />
                  </td>
                  <td className="px-4 py-2 align-top">
                    <PendingAppendixCell app={r} />
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
  // US MM-DD-YYYY, like the rest of the agreement screens.
  return iso ? formatUsDate(iso) : "—";
}

// "None" when all five appendices are sent + signed; otherwise a compact chip
// summary ("N not sent" / "N awaiting") that expands to the per-appendix
// detail. The full Appendix 1–5 set is considered; not-required appendices
// appear as "Not sent".
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
          <li
            key={p.n}
            className="flex items-start gap-1.5 text-[11px] text-gray-600"
          >
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
