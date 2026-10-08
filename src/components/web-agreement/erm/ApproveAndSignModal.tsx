"use client";

import { useEffect, useState } from "react";
import { AlertCircle, Loader2, PenLine } from "lucide-react";

import SignaturePad from "@/components/common/SignaturePad";
import { webApproveAndSign } from "@/lib/api";
import { ModalShell } from "@/components/web-agreement/ui/ModalShell";

/**
 * The ERM's countersignature (READY_TO_SIGN → COMPLETED). The website's copy
 * of the console's ApproveAndSignModal (ConsultantDetailView.tsx). Name and
 * title are prefilled from the signer's own account (the signer profile)
 * and stay fully editable; the server then generates the final PDF, which
 * takes 10–30 seconds.
 *
 * No email: the console's "…and email it to both parties" is dropped.
 */
export default function ApproveAndSignModal({
  appId,
  defaultName = "",
  defaultTitle = "",
  onClose,
  onDone,
}: {
  appId: string;
  defaultName?: string;
  defaultTitle?: string;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  const [ermName, setErmName] = useState(defaultName);
  const [ermTitle, setErmTitle] = useState(defaultTitle);
  const [signature, setSignature] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  // The signer profile may arrive AFTER this modal opened (useState only
  // takes the initial value at mount). Backfill it into any field the
  // signer hasn't typed into yet — never overwrite their input.
  useEffect(() => {
    if (defaultName) setErmName((v) => v || defaultName);
    if (defaultTitle) setErmTitle((v) => v || defaultTitle);
  }, [defaultName, defaultTitle]);

  const canSubmit =
    ermName.trim().length > 0
    && ermTitle.trim().length > 0
    && !!signature
    && !busy;

  const submit = async () => {
    if (!canSubmit || !signature) return;
    setBusy(true);
    setError("");
    try {
      await webApproveAndSign(appId, {
        ermName: ermName.trim(),
        ermTitle: ermTitle.trim(),
        ermSignatureBase64: signature,
      });
      await onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't approve + sign.");
      setBusy(false);
    }
  };

  return (
    <ModalShell
      title="Approve and sign agreement"
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
            disabled={!canSubmit}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 disabled:cursor-not-allowed cursor-pointer"
          >
            {busy ? <Loader2 size={12} className="animate-spin" /> : <PenLine size={12} />}
            {busy ? "Generating final PDF…" : "Confirm & generate PDF"}
          </button>
        </>
      }
    >
      <div className="rounded-md border border-sage-navy/15 bg-sage-navy/5 p-3 text-xs text-sage-navy">
        Signing will apply your signature to all 8 signature blocks and generate
        the final PDF.
      </div>

      <div className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-3">
        <div>
          <label
            htmlFor="erm-sign-name"
            className="block text-[11px] font-semibold text-gray-600 mb-1"
          >
            Your name <span className="text-red-500">*</span>
          </label>
          <input
            id="erm-sign-name"
            type="text"
            value={ermName}
            onChange={(e) => setErmName(e.target.value)}
            disabled={busy}
            placeholder="Sarah Johnson"
            autoComplete="off"
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy disabled:bg-gray-50"
          />
        </div>
        <div>
          <label
            htmlFor="erm-sign-title"
            className="block text-[11px] font-semibold text-gray-600 mb-1"
          >
            Your title <span className="text-red-500">*</span>
          </label>
          <input
            id="erm-sign-title"
            type="text"
            value={ermTitle}
            onChange={(e) => setErmTitle(e.target.value)}
            disabled={busy}
            placeholder="Director of Operations"
            autoComplete="off"
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy disabled:bg-gray-50"
          />
        </div>
      </div>

      <div className="mt-4">
        <SignaturePad
          suggestedName={ermName}
          onChange={setSignature}
          disabled={busy}
          fileInputId="erm-sig-upload"
        />
      </div>

      {busy && (
        <div className="mt-4 rounded-md border border-sage-navy/15 bg-white p-3 text-xs text-sage-navy flex items-start gap-2">
          <Loader2 size={14} className="animate-spin shrink-0 mt-0.5" />
          <div>
            <p className="font-semibold">Generating final PDF…</p>
            <p className="text-[11px] text-gray-500 mt-0.5">
              This usually takes 10–30 seconds. Please don&apos;t close this
              window.
            </p>
          </div>
        </div>
      )}

      {error && (
        <p className="mt-3 inline-flex items-start gap-1.5 text-sm text-red-600">
          <AlertCircle size={14} className="mt-0.5 shrink-0" /> {error}
        </p>
      )}
    </ModalShell>
  );
}
