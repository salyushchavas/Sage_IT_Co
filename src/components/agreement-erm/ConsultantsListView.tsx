"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { AlertCircle, Loader2, Plus, Search } from "lucide-react";

import {
  listConsultantApplications,
  type ConsultantApplication,
  type ConsultantApplicationStatus,
  type ConsultantApplicationsPage,
} from "@/lib/api";
import AgreementStatusPill from "./AgreementStatusPill";

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
  { id: "EXPIRED",            label: "Expired" },
];

const PAGE_SIZE = 20;

export default function ConsultantsListView() {
  const [filter, setFilter] = useState<"ALL" | ConsultantApplicationStatus>("ALL");
  const [page, setPage] = useState(0);
  const [pageData, setPageData] = useState<ConsultantApplicationsPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");

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
  }, [filter, page]);

  const filtered = useMemo<ConsultantApplication[]>(() => {
    const rows = pageData?.content ?? [];
    const q = search.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((r) =>
      [r.consultantEmail, r.consultantName ?? "", r.applicationId]
        .some((v) => v.toLowerCase().includes(q)),
    );
  }, [pageData, search]);

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
              <th className="text-left px-4 py-2">Application ID</th>
              <th className="text-left px-4 py-2">Status</th>
              <th className="text-left px-4 py-2">Created</th>
              <th className="text-left px-4 py-2">Expires</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={5} className="text-center py-8">
                  <Loader2 size={18} className="animate-spin text-sage-navy inline" />
                </td>
              </tr>
            ) : filtered.length === 0 ? (
              <tr>
                <td
                  colSpan={5}
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
                  <td className="px-4 py-2 font-mono text-[11px] text-gray-700">
                    <Link href={`/agreements/${r.applicationId}`}>
                      {r.applicationId.slice(0, 8)}…
                    </Link>
                  </td>
                  <td className="px-4 py-2">
                    <AgreementStatusPill status={r.status} />
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-500">
                    {formatDate(r.createdAt)}
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-500">
                    {formatDate(r.expiresAt)}
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
  if (!iso) return "—";
  return new Date(iso).toLocaleString("en-IN", {
    timeZone: "Asia/Kolkata",
    dateStyle: "medium",
  });
}
