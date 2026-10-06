"use client";

import { useState } from "react";
import Link from "next/link";
import { CheckCircle2, ChevronRight, Clock, Loader2, Lock } from "lucide-react";
import { formatDateMedium } from "@/lib/datetime";
import { requestAgreement, type AgreementRequestStatus } from "@/lib/api";

/**
 * The real agreement step, after the consent: the participant says "I'm
 * ready to sign the agreement", their ERM starts it and fills their side,
 * and the participant opens it from here to fill theirs at
 * /dashboard/agreement (the website's own copy; it never touches the
 * office's agreements console). Shows where it is in the five steps.
 */
export default function MasterAgreementStep({ number, state, onChange }: {
  number: number;
  state: AgreementRequestStatus | null;
  onChange: (next: AgreementRequestStatus) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const consentSigned = state?.consentSigned ?? false;
  const ag = state?.agreement ?? null;
  const executed = ag?.executed ?? false;
  const active = consentSigned && !executed;

  const ready = async () => {
    setBusy(true);
    setError("");
    try {
      onChange(await requestAgreement());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't send your request. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <li
      id="step-MASTER_AGREEMENT"
      className={
        "rounded-xl border bg-white p-4 transition scroll-mt-24 "
        + (executed
          ? "border-emerald-200 bg-emerald-50/40"
          : active
            ? "border-sage-navy shadow-md ring-2 ring-sage-navy/10"
            : "border-gray-200 opacity-70")
      }
    >
      <div className="flex items-start gap-3">
        <div className={
          "shrink-0 inline-flex items-center justify-center w-8 h-8 rounded-full text-xs font-bold "
          + (executed ? "bg-emerald-600 text-white" : active ? "bg-sage-navy text-white" : "bg-gray-100 text-gray-400")
        }>
          {executed ? <CheckCircle2 size={14} /> : active ? number : <Lock size={12} />}
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-bold text-gray-900">Step {number}: Agreement</p>
          <p className="text-xs text-gray-500 mt-0.5">Your agreement: your ERM fills their side, then you fill yours and sign</p>

          {!consentSigned && (
            <p className="text-[11px] text-gray-400 mt-1.5 italic">Opens after you sign your consent.</p>
          )}

          {consentSigned && !state?.requested && !ag && (
            <div className="mt-3 space-y-2">
              <p className="text-xs text-gray-600">When you&apos;re ready, let us know and your ERM will prepare your agreement.</p>
              <button
                type="button"
                onClick={ready}
                disabled={busy}
                className="inline-flex items-center gap-1.5 bg-sage-navy hover:bg-sage-navy-deep text-white text-xs font-bold px-4 py-2 rounded-lg shadow-sm transition cursor-pointer disabled:opacity-60"
              >
                {busy && <Loader2 size={12} className="animate-spin" />}
                I&apos;m ready to sign the agreement
              </button>
            </div>
          )}

          {consentSigned && state?.requested && !ag && (
            <p className="mt-3 inline-flex items-start gap-2 text-xs text-sage-navy bg-sage-navy/5 border border-sage-navy/15 rounded-lg px-3 py-2">
              <Clock size={14} className="shrink-0 mt-px" />
              <span>
                Requested{state.requestedAt ? ` on ${formatDateMedium(state.requestedAt)}` : ""}. Your ERM is preparing
                your agreement; it will show up here when it&apos;s ready.
              </span>
            </p>
          )}

          {ag && (
            <div className="mt-3 space-y-2">
              <div className="flex items-center gap-1" aria-hidden="true">
                {Array.from({ length: ag.totalSteps }, (_, i) => (
                  <span key={i} className={"h-1.5 flex-1 rounded-full " + (i < ag.step ? (executed ? "bg-emerald-600" : "bg-sage-navy") : "bg-gray-200")} />
                ))}
              </div>
              <p className={"text-xs font-semibold " + (executed ? "text-emerald-700" : "text-gray-800")}>
                Step {ag.step} of {ag.totalSteps}: {ag.stage}
              </p>
              {ag.yourTurn && (
                <Link
                  href="/dashboard/agreement"
                  className="inline-flex items-center gap-1 bg-sage-navy hover:bg-sage-navy-deep text-white text-xs font-bold px-4 py-2 rounded-lg shadow-sm transition cursor-pointer"
                >
                  Open your agreement <ChevronRight size={12} />
                </Link>
              )}
            </div>
          )}

          {error && <p className="mt-2 text-xs text-red-600">{error}</p>}
        </div>
      </div>
    </li>
  );
}
