"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { AlertCircle, CheckCircle2, ChevronRight, Download, Loader2 } from "lucide-react";

import {
  downloadSignedAgreement,
  getAgreementRequestStatus,
  type AgreementRequestStatus,
} from "@/lib/api";
import { formatDateMedium } from "@/lib/datetime";
import { agreementPartDone } from "@/lib/profile-progress";
import { Phase2Chip } from "../MasterAgreementStep";

interface Props {
  participantId: string | null;
}

export default function AgreementTab({ participantId }: Props) {
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState("");
  // The real agreement (Step 6 on the profile), separate from the consent.
  const [agreement, setAgreement] = useState<AgreementRequestStatus | null>(null);

  useEffect(() => {
    let cancelled = false;
    getAgreementRequestStatus()
      .then((a) => { if (!cancelled) setAgreement(a); })
      .catch(() => { /* the line just stays hidden */ });
    return () => { cancelled = true; };
  }, []);

  const handleDownload = async () => {
    setDownloading(true);
    setError("");
    try {
      await downloadSignedAgreement();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't download the consent.");
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Agreement</h1>
      <div className="rounded-2xl border border-emerald-200 bg-emerald-50/40 p-5">
        <div className="inline-flex items-center gap-2 text-emerald-800 text-sm font-semibold">
          <CheckCircle2 size={16} /> Consent signed and on file
        </div>
        {participantId && (
          <p className="text-xs text-gray-600 mt-2">
            Filed under participant ID{" "}
            <span className="font-mono">{participantId}</span>.
          </p>
        )}
        <p className="text-xs text-gray-500 mt-2">
          A signed PDF copy was emailed to you when you completed the consent
          step. You can also download it here at any time.
        </p>
        <button
          type="button"
          onClick={handleDownload}
          disabled={downloading}
          className="mt-3 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 disabled:cursor-not-allowed transition cursor-pointer"
        >
          {downloading ? <Loader2 size={12} className="animate-spin" /> : <Download size={12} />}
          Download signed consent
        </button>
        {error && (
          <p className="mt-2 inline-flex items-center gap-1 text-[11px] text-red-600">
            <AlertCircle size={11} /> {error}
          </p>
        )}
      </div>
      {agreement && <AgreementLine state={agreement} />}
    </div>
  );
}

/**
 * Where the real agreement stands, in the words Step 6 on the profile uses,
 * with the same "Phase 2" chip once it's at Phase 2.
 */
function AgreementLine({ state }: { state: AgreementRequestStatus }) {
  const ag = state.agreement;
  let text: string;
  let link: { href: string; label: string };
  if (ag) {
    text = agreementPartDone(state) ? `Completed. ${ag.stage}` : `Step ${ag.step} of ${ag.totalSteps}: ${ag.stage}`;
    link = { href: ag.link, label: ag.yourTurn ? "Open your agreement" : "View" };
  } else if (state.requested) {
    text = `Requested${state.requestedAt ? ` on ${formatDateMedium(state.requestedAt)}` : ""}. `
      + "Your ERM is preparing your agreement; it will show up here when it's ready.";
    link = { href: "/dashboard/agreement", label: "View" };
  } else {
    text = "Not started. When you're ready, let us know from Step 6 on your profile and your ERM will prepare your agreement.";
    link = { href: "/dashboard?tab=complete-profile&step=MASTER_AGREEMENT", label: "Go to Step 6" };
  }
  return (
    <div className="rounded-2xl border border-gray-200 bg-white p-5">
      <p className="text-sm font-semibold text-gray-900">Your agreement</p>
      <div className="mt-1 flex items-center gap-2 flex-wrap">
        <p className="text-xs text-gray-600">{text}</p>
        <Phase2Chip phase={ag?.phase} />
      </div>
      <Link
        href={link.href}
        className="mt-3 inline-flex items-center gap-1 text-xs font-semibold text-sage-navy hover:underline"
      >
        {link.label} <ChevronRight size={12} />
      </Link>
    </div>
  );
}
