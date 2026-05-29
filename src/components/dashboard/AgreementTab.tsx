"use client";

import { CheckCircle2 } from "lucide-react";

interface Props {
  participantId: string | null;
}

export default function AgreementTab({ participantId }: Props) {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Agreement</h1>
      <div className="rounded-2xl border border-emerald-200 bg-emerald-50/40 p-5">
        <div className="inline-flex items-center gap-2 text-emerald-800 text-sm font-semibold">
          <CheckCircle2 size={16} /> Agreement signed and on file
        </div>
        {participantId && (
          <p className="text-xs text-gray-600 mt-2">
            Filed under participant ID{" "}
            <span className="font-mono">{participantId}</span>.
          </p>
        )}
        <p className="text-xs text-gray-500 mt-2">
          A signed PDF copy was emailed to you when you completed the agreement
          step. Contact your ERM if you need another copy.
        </p>
      </div>
    </div>
  );
}
