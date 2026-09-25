"use client";

import { useState } from "react";
import { AlertCircle, CheckCircle2, Download, Loader2 } from "lucide-react";

import { downloadSignedAgreement } from "@/lib/api";

interface Props {
  participantId: string | null;
}

export default function AgreementTab({ participantId }: Props) {
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState("");

  const handleDownload = async () => {
    setDownloading(true);
    setError("");
    try {
      await downloadSignedAgreement();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't download the agreement.");
    } finally {
      setDownloading(false);
    }
  };

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
          step. You can also download it here at any time.
        </p>
        <button
          type="button"
          onClick={handleDownload}
          disabled={downloading}
          className="mt-3 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 disabled:cursor-not-allowed transition cursor-pointer"
        >
          {downloading ? <Loader2 size={12} className="animate-spin" /> : <Download size={12} />}
          Download signed agreement
        </button>
        {error && (
          <p className="mt-2 inline-flex items-center gap-1 text-[11px] text-red-600">
            <AlertCircle size={11} /> {error}
          </p>
        )}
      </div>
    </div>
  );
}
