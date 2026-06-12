"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  AlertCircle,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Loader2,
  Trash2,
  Users,
} from "lucide-react";

import {
  adminDeleteApplication,
  adminListAgreements,
  type AgreementSummaryDto,
} from "@/lib/api";
import AgreementStatusPill from "./AgreementStatusPill";
import { formatUsDate } from "@/lib/dates";

interface ErmGroup {
  key: string;
  name: string;
  rows: AgreementSummaryDto[];
}

/**
 * Super-admin "Agreements by ERM" view: every live (non-archived)
 * application grouped under its owning ERM. Cancelled rows can be
 * archived (soft-delete) from here.
 */
export default function AgreementsByErmView() {
  const [rows, setRows] = useState<AgreementSummaryDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [archivingId, setArchivingId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await adminListAgreements());
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load agreements.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const groups = useMemo<ErmGroup[]>(() => {
    const map = new Map<string, ErmGroup>();
    for (const r of rows) {
      const key = r.ownerErmId ?? "__none__";
      const name = r.ownerName ?? "Unassigned";
      if (!map.has(key)) map.set(key, { key, name, rows: [] });
      map.get(key)!.rows.push(r);
    }
    // Alphabetical by ERM name; "Unassigned" last.
    return Array.from(map.values()).sort((a, b) => {
      if (a.key === "__none__") return 1;
      if (b.key === "__none__") return -1;
      return a.name.localeCompare(b.name);
    });
  }, [rows]);

  // Auto-clear the success banner so it doesn't linger across actions
  // (mirrors ConsultantDetailView).
  useEffect(() => {
    if (!feedback) return;
    const t = setTimeout(() => setFeedback(""), 8_000);
    return () => clearTimeout(t);
  }, [feedback]);

  // Drop collapse state for groups that no longer exist (e.g. the last
  // row of an ERM was archived) so a future same-key group isn't
  // unexpectedly rendered collapsed.
  useEffect(() => {
    setCollapsed((prev) => {
      if (prev.size === 0) return prev;
      const live = new Set(groups.map((g) => g.key));
      let changed = false;
      const next = new Set<string>();
      prev.forEach((k) => {
        if (live.has(k)) next.add(k);
        else changed = true;
      });
      return changed ? next : prev;
    });
  }, [groups]);

  const toggle = (key: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const handleDelete = async (appId: string) => {
    if (
      !confirm(
        "Delete this agreement?\n\n"
          + "This removes it from all dashboards -- ERM and consultant. "
          + "The row is retained internally for audit; recovery is DB-level only.",
      )
    ) {
      return;
    }
    setArchivingId(appId);
    setError("");
    try {
      await adminDeleteApplication(appId);
      setRows((prev) => prev.filter((r) => r.appId !== appId));
      setFeedback("Agreement deleted.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't delete agreement.");
    } finally {
      setArchivingId(null);
    }
  };

  if (loading) {
    return (
      <div className="py-10 text-center">
        <Loader2 size={20} className="animate-spin text-sage-navy inline" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-500 inline-flex items-center gap-2">
        <Users size={14} className="text-sage-navy" />
        {rows.length} agreement{rows.length === 1 ? "" : "s"} across{" "}
        {groups.length} ERM{groups.length === 1 ? "" : "s"}.
      </p>

      {feedback && (
        <p className="inline-flex items-center gap-1.5 text-sm text-emerald-700">
          <CheckCircle2 size={14} /> {feedback}
        </p>
      )}
      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}

      {groups.length === 0 ? (
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm px-4 py-8 text-center text-sm text-gray-400 italic">
          No agreements yet.
        </div>
      ) : (
        groups.map((g) => {
          const isCollapsed = collapsed.has(g.key);
          return (
            <div
              key={g.key}
              className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden"
            >
              <button
                type="button"
                onClick={() => toggle(g.key)}
                className="w-full flex items-center justify-between px-4 py-3 bg-sage-navy/5 hover:bg-sage-navy/10 cursor-pointer"
              >
                <span className="inline-flex items-center gap-2 font-bold text-sage-navy text-sm">
                  {isCollapsed ? (
                    <ChevronRight size={15} />
                  ) : (
                    <ChevronDown size={15} />
                  )}
                  {g.name}
                </span>
                <span className="text-[11px] font-semibold text-gray-500">
                  {g.rows.length} agreement{g.rows.length === 1 ? "" : "s"}
                </span>
              </button>

              {!isCollapsed && (
                <table className="w-full text-sm">
                  <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
                    <tr>
                      <th className="text-left px-4 py-2">Consultant</th>
                      <th className="text-left px-4 py-2">Status</th>
                      <th className="text-left px-4 py-2">Created</th>
                      <th className="text-right px-4 py-2">Actions</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {g.rows.map((r) => (
                      <tr key={r.appId} className="hover:bg-gray-50">
                        <td className="px-4 py-2">
                          <Link href={`/agreements/${r.appId}`} className="block">
                            <div className="font-medium text-gray-900">
                              {r.consultantName || "—"}
                            </div>
                            <div className="text-[11px] text-gray-500">
                              {r.consultantEmail}
                            </div>
                          </Link>
                        </td>
                        <td className="px-4 py-2">
                          <AgreementStatusPill status={r.status} />
                        </td>
                        <td className="px-4 py-2 text-xs text-gray-500">
                          {formatDate(r.createdAt)}
                        </td>
                        <td className="px-4 py-2">
                          <div className="flex items-center justify-end gap-2">
                            <Link
                              href={`/agreements/${r.appId}`}
                              className="text-[11px] font-semibold text-sage-navy hover:underline"
                            >
                              Open
                            </Link>
                            <button
                              type="button"
                              onClick={() => handleDelete(r.appId)}
                              disabled={archivingId === r.appId}
                              className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-semibold border border-red-200 bg-white hover:bg-red-50 text-red-700 cursor-pointer disabled:opacity-50"
                            >
                              {archivingId === r.appId ? (
                                <Loader2 size={11} className="animate-spin" />
                              ) : (
                                <Trash2 size={11} />
                              )}
                              Delete
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          );
        })
      )}
    </div>
  );
}

function formatDate(iso: string | null | undefined) {
  // Build N — US MM-DD-YYYY (was en-IN DD-MM).
  return iso ? formatUsDate(iso) : "—";
}
