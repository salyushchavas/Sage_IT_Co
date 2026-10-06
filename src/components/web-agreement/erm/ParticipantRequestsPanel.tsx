"use client";

import { useEffect, useState } from "react";
import { AlertCircle, ChevronRight, Loader2, UserCheck } from "lucide-react";
import { listWebAgreementRequests, type WebAgreementReadyRow } from "@/lib/api";
import { formatUsDate } from "@/lib/dates";

/**
 * Participants who signed their consent and said "I'm ready to sign the
 * agreement", with no open agreement yet. "Start agreement" opens the create
 * form with their details filled in. Every ERM sees the whole list (nobody
 * owns an agreement until they create it).
 *
 * Unlike the console panel this copy comes from, it stays on screen when the
 * list is empty: here it is how an agreement starts.
 */
export default function ParticipantRequestsPanel({
  onStart,
}: {
  onStart: (userId: number) => void;
}) {
  const [rows, setRows] = useState<WebAgreementReadyRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    listWebAgreementRequests()
      .then((r) => { if (!cancelled) setRows(r); })
      .catch((e) => {
        // The agreements list below still works.
        if (!cancelled) setError(e instanceof Error ? e.message : "Couldn't load the participants");
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  return (
    <div className="rounded-xl border border-sage-navy/15 bg-sage-navy/5 p-4">
      <p className="inline-flex items-center gap-2 text-sm font-semibold text-sage-navy">
        <UserCheck size={16} /> Participants ready for their agreement{loading ? "" : ` (${rows.length})`}
      </p>
      {loading ? (
        <div className="mt-3">
          <Loader2 size={16} className="animate-spin text-sage-navy" />
        </div>
      ) : error ? (
        <p className="mt-3 inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      ) : rows.length === 0 ? (
        <p className="mt-2 text-xs text-gray-500">
          Nobody is waiting for their agreement right now. Participants appear here when they say
          they&apos;re ready to sign it.
        </p>
      ) : (
        <ul className="mt-3 divide-y divide-sage-navy/10">
          {rows.map((r) => (
            <li key={r.userId} className="flex flex-wrap items-center justify-between gap-3 py-2.5 text-sm">
              <div className="min-w-0">
                <p className="font-semibold text-gray-900">{r.fullName || r.email}</p>
                <p className="text-xs text-gray-600 break-all">
                  {r.email}
                  {r.participantId ? ` · ${r.participantId}` : ""}
                  {r.program ? ` · ${r.program}` : ""}
                  {r.requestedAt ? ` · ready since ${formatUsDate(r.requestedAt)}` : ""}
                </p>
              </div>
              <button
                type="button"
                onClick={() => onStart(r.userId)}
                className="inline-flex items-center gap-1 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
              >
                Start agreement <ChevronRight size={12} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
