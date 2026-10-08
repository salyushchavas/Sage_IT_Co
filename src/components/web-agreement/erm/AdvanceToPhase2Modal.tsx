"use client";

import { useState } from "react";
import { AlertCircle, ArrowUpRight, Loader2 } from "lucide-react";

import { webAdvanceToPhase2, type WebAgreement, type WebPhase2Promotion } from "@/lib/api";
import { ModalShell } from "@/components/web-agreement/ui/ModalShell";

/**
 * Reopens an executed Phase 1 agreement for Phase 2 (COMPLETED → SUBMITTED,
 * phase 2) on the same document. The website's copy of the console's
 * AdvanceToPhase2Modal (ConsultantDetailView.tsx).
 *
 * One checkbox per appendix plus the SSN flag. Every section that is still
 * optional starts ticked (the SSN too when it is not required yet); a
 * section already required in Phase 1 is shown ticked and can't be changed.
 * All six values are always sent, so the server takes them as an explicit
 * choice. No email: the console's invite-window and email sentences are
 * dropped.
 */
export default function AdvanceToPhase2Modal({
  app,
  onClose,
  onDone,
}: {
  app: WebAgreement;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  const initial: Required<WebPhase2Promotion> = {
    appendix1: !app.requireAppendix1,
    appendix2: !app.requireAppendix2,
    appendix3: !app.requireAppendix3,
    appendix4: !app.requireAppendix4,
    appendix5: !app.requireAppendix5,
    ssn: !app.requireSsn,
  };
  const [promote, setPromote] = useState<Required<WebPhase2Promotion>>(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const rows: Array<{
    key: keyof WebPhase2Promotion;
    label: string;
    alreadyRequired: boolean;
  }> = [
    { key: "appendix1", label: "Appendix 1 — Employment Confirmation", alreadyRequired: Boolean(app.requireAppendix1) },
    { key: "appendix2", label: "Appendix 2 — ACH Payment Authorization", alreadyRequired: Boolean(app.requireAppendix2) },
    { key: "appendix3", label: "Appendix 3 — Background Check", alreadyRequired: Boolean(app.requireAppendix3) },
    { key: "appendix4", label: "Appendix 4 — Portal & Account Access", alreadyRequired: Boolean(app.requireAppendix4) },
    { key: "appendix5", label: "Appendix 5 — Security Cheque", alreadyRequired: Boolean(app.requireAppendix5) },
    { key: "ssn", label: "Require SSN (within Appendix 3)", alreadyRequired: Boolean(app.requireSsn) },
  ];

  const submit = async () => {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await webAdvanceToPhase2(app.applicationId, promote);
      await onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't advance to Phase 2.");
      setBusy(false);
    }
  };

  return (
    <ModalShell
      title="Advance to Phase 2"
      subtitle="Reopens this agreement for the participant on the same document. Phase-1 data is preserved."
      onClose={onClose}
      closeable={!busy}
      footer={
        <>
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            className="px-3 py-1.5 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={busy}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-copper-deep text-white hover:opacity-90 disabled:opacity-60 cursor-pointer"
          >
            {busy ? <Loader2 size={12} className="animate-spin" /> : <ArrowUpRight size={12} />}
            {busy ? "Advancing…" : "Advance to Phase 2"}
          </button>
        </>
      }
    >
      <p className="text-xs text-gray-600">
        Tick the sections the participant must now complete. The participant&apos;s
        previously filled values stay put; both signatures + every section
        affirmation will clear so they re-sign the now-final document.
      </p>
      <ul className="mt-4 space-y-2">
        {rows.map((row) => (
          <li
            key={row.key}
            className={
              "flex items-start gap-2 rounded-md border p-3 "
              + (row.alreadyRequired
                ? "border-gray-200 bg-gray-50"
                : "border-stone-200 bg-white")
            }
          >
            <input
              id={`phase2-${row.key}`}
              type="checkbox"
              disabled={busy || row.alreadyRequired}
              checked={row.alreadyRequired || Boolean(promote[row.key])}
              onChange={(e) =>
                setPromote((prev) => ({ ...prev, [row.key]: e.target.checked }))
              }
              className="mt-0.5 h-3.5 w-3.5 shrink-0 accent-sage-navy"
            />
            <label
              htmlFor={`phase2-${row.key}`}
              className="text-xs text-gray-800 flex-1 min-w-0 cursor-pointer"
            >
              <span className="font-semibold">{row.label}</span>
              {row.alreadyRequired && (
                <span className="block text-[10px] text-gray-500 mt-0.5">
                  Already required in Phase 1 — stays required.
                </span>
              )}
            </label>
          </li>
        ))}
      </ul>
      <p className="mt-4 text-[11px] text-gray-500">
        On confirm: status flips COMPLETED → SUBMITTED and both signatures clear.
      </p>
      {error && (
        <p className="mt-3 inline-flex items-start gap-1.5 text-sm text-red-600">
          <AlertCircle size={14} className="mt-0.5 shrink-0" /> {error}
        </p>
      )}
    </ModalShell>
  );
}
