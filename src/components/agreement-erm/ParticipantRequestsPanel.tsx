"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ChevronRight, UserCheck } from "lucide-react";
import { listParticipantAgreementRequests, type ParticipantAgreementRequest } from "@/lib/api";
import { formatUsDate } from "@/lib/dates";

/**
 * Participants (from the main portal) who signed their consent and said
 * "I'm ready to sign the agreement", with no open agreement yet. "Start
 * agreement" opens the usual create form with their details filled in.
 * Shows nothing when nobody is waiting.
 */
export default function ParticipantRequestsPanel() {
  const [rows, setRows] = useState<ParticipantAgreementRequest[]>([]);

  useEffect(() => {
    let cancelled = false;
    listParticipantAgreementRequests()
      .then((r) => { if (!cancelled) setRows(r); })
      .catch(() => { /* the agreements list below still works */ });
    return () => { cancelled = true; };
  }, []);

  if (rows.length === 0) return null;

  return (
    <div className="rounded-xl border border-sage-navy/15 bg-sage-navy/5 p-4">
      <p className="inline-flex items-center gap-2 text-sm font-semibold text-sage-navy">
        <UserCheck size={16} /> Participants ready for their agreement ({rows.length})
      </p>
      <ul className="mt-3 divide-y divide-sage-navy/10">
        {rows.map((r) => (
          <li key={r.userId} className="flex flex-wrap items-center justify-between gap-3 py-2.5 text-sm">
            <div className="min-w-0">
              <p className="font-semibold text-gray-900">{r.fullName}</p>
              <p className="text-xs text-gray-600 break-all">
                {r.email}
                {r.participantId ? ` · ${r.participantId}` : ""}
                {r.program ? ` · ${r.program}` : ""}
                {r.requestedAt ? ` · ready since ${formatUsDate(r.requestedAt)}` : ""}
              </p>
            </div>
            <Link
              href={`/agreements/new?participant=${r.userId}`}
              className="inline-flex items-center gap-1 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
            >
              Start agreement <ChevronRight size={12} />
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
