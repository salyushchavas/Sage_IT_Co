"use client";

import { useEffect, useState } from "react";
import { AlertCircle, CheckCircle2, Loader2 } from "lucide-react";

import {
  webFetchAgreementVersions,
  webFetchEligibleApprovers,
  type WebAgreement,
  type WebAgreementVersion,
  type WebApproverOption,
  type WebEligibleApprovers,
  type WebSendForApprovalBody,
} from "@/lib/api";
import { formatUsDayCt } from "@/lib/datetime";
import { ModalShell } from "@/components/web-agreement/ui/ModalShell";

/**
 * Routes the verified agreement to its approvers, and re-sends it after an
 * approver declined. The website's copy of the console's
 * SendForApprovalModal (ConsultantDetailView.tsx).
 *
 * The ERM picks one Manager (Phase 1 and 2) and one Accounts approver
 * (Phase 2) from the owning ERM's team: a dropdown when several are
 * assigned, a pre-selected read-only card when exactly one, and a blocking
 * message when none. A pick is required for every gate the phase needs.
 *
 * "Version to review" picks the frozen version (V1, V2, …) the approvers
 * see, defaulting to the latest. With no version at all (an agreement
 * verified before versions existed) nothing is sent and the approvers see a
 * live render.
 *
 * The parent owns the request (its busy state also drives the bar's
 * "Sending…" label); a refusal is shown here as the server worded it.
 */
export default function SendForApprovalModal({
  app,
  busy,
  onClose,
  onSend,
}: {
  app: WebAgreement;
  busy: boolean;
  onClose: () => void;
  onSend: (routing: WebSendForApprovalBody) => Promise<void>;
}) {
  const [eligible, setEligible] = useState<WebEligibleApprovers | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [managerId, setManagerId] = useState<number | null>(null);
  const [accountsId, setAccountsId] = useState<number | null>(null);
  const [sendError, setSendError] = useState("");
  const [versions, setVersions] = useState<WebAgreementVersion[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    webFetchEligibleApprovers(app.applicationId)
      .then((e) => {
        if (cancelled) return;
        setEligible(e);
        if (e.managers.length === 1) setManagerId(e.managers[0].id);
        if (e.accounts.length === 1) setAccountsId(e.accounts[0].id);
      })
      .catch((err) => {
        if (!cancelled) {
          setLoadError(err instanceof Error ? err.message : "Couldn't load approvers.");
        }
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [app.applicationId]);

  useEffect(() => {
    let cancelled = false;
    webFetchAgreementVersions(app.applicationId)
      .then((rows) => {
        if (cancelled) return;
        setVersions(rows);
        if (rows.length > 0) {
          setSelectedVersion(rows[rows.length - 1].versionNumber); // latest
        }
      })
      .catch(() => {
        /* non-fatal: no explicit version, the server takes the latest */
      });
    return () => {
      cancelled = true;
    };
  }, [app.applicationId]);

  const phase2 = (eligible?.phase ?? 1) >= 2;
  const managerBlocked = !!eligible && eligible.managers.length === 0;
  const accountsBlocked = phase2 && !!eligible && eligible.accounts.length === 0;
  const canSend =
    !loading
    && !loadError
    && !managerBlocked
    && !accountsBlocked
    && managerId != null
    && (!phase2 || accountsId != null);

  const send = () => {
    if (!canSend || busy) return;
    setSendError("");
    void onSend({
      managerUserId: managerId,
      accountsUserId: phase2 ? accountsId : undefined,
      versionNumber: selectedVersion ?? undefined,
    }).catch((e) =>
      setSendError(e instanceof Error ? e.message : "Couldn't send for approval."),
    );
  };

  return (
    <ModalShell
      title="Send for approval"
      onClose={onClose}
      closeable={!busy}
      footer={
        <>
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            className="px-3 py-2 rounded-md text-xs font-semibold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={!canSend || busy}
            onClick={send}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {busy ? <Loader2 size={12} className="animate-spin" /> : <CheckCircle2 size={12} />}
            Send for approval
          </button>
        </>
      }
    >
      <div className="space-y-4">
        {loading ? (
          <div className="py-8 flex justify-center text-gray-400">
            <Loader2 size={20} className="animate-spin" />
          </div>
        ) : loadError ? (
          <p className="text-[12px] text-red-600 inline-flex items-start gap-1">
            <AlertCircle size={12} className="mt-0.5 shrink-0" /> {loadError}
          </p>
        ) : (
          <>
            <p className="text-[12px] text-gray-500">
              Route this agreement to {phase2 ? "a Manager and an Accounts approver" : "a Manager"}.
              {phase2 ? " Both must approve." : ""}
            </p>
            {versions.length > 1 ? (
              <div>
                <label
                  htmlFor="send-approval-version"
                  className="block text-[11px] font-semibold uppercase tracking-wider text-sage-navy mb-1"
                >
                  Version to review
                </label>
                <select
                  id="send-approval-version"
                  value={selectedVersion == null ? "" : String(selectedVersion)}
                  onChange={(e) =>
                    setSelectedVersion(e.target.value === "" ? null : Number(e.target.value))
                  }
                  disabled={busy}
                  className="w-full px-3 py-2.5 text-[14px] rounded-md border border-stone-300 bg-white focus:outline-none focus:ring-2 focus:ring-sage-copper/40 disabled:bg-gray-50"
                >
                  {versions.map((v) => (
                    <option key={v.versionNumber} value={v.versionNumber}>
                      Version V{v.versionNumber}
                      {typeof v.phase === "number" ? ` · Phase ${v.phase}` : ""}
                      {v.approvedAt ? ` · ${formatUsDayCt(v.approvedAt)}` : ""}
                    </option>
                  ))}
                </select>
                <p className="text-[11px] text-gray-400 mt-1">
                  The approver(s) review this frozen version. Defaults to the
                  latest (the version you just released).
                </p>
              </div>
            ) : versions.length === 1 ? (
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-wider text-sage-navy mb-1">
                  Version to review
                </p>
                <div className="rounded-md border border-stone-200 bg-stone-50 px-3 py-2.5">
                  <p className="text-[13px] font-medium text-gray-900">
                    Version V{versions[0].versionNumber}
                  </p>
                  <p className="text-[11px] text-gray-500">
                    Approved{" "}
                    {versions[0].approvedAt ? formatUsDayCt(versions[0].approvedAt) : "—"}
                  </p>
                </div>
              </div>
            ) : null}
            <ApproverPicker
              id="send-approval-manager"
              label="Manager"
              roleWord="manager"
              options={eligible?.managers ?? []}
              value={managerId}
              onChange={setManagerId}
              disabled={busy}
            />
            {phase2 && (
              <ApproverPicker
                id="send-approval-accounts"
                label="Accounts"
                roleWord="accounts"
                options={eligible?.accounts ?? []}
                value={accountsId}
                onChange={setAccountsId}
                disabled={busy}
              />
            )}
          </>
        )}
        {sendError && (
          <p className="text-[12px] text-red-600 inline-flex items-start gap-1">
            <AlertCircle size={12} className="mt-0.5 shrink-0" /> {sendError}
          </p>
        )}
      </div>
    </ModalShell>
  );
}

/**
 * One gate's picker: the red "No … assigned" block when the team has nobody
 * in that role, a read-only card when exactly one (already picked), a
 * dropdown otherwise. roleWord is the console's ("Select a accounts…" is its
 * wording, kept as is).
 */
function ApproverPicker({
  id,
  label,
  roleWord,
  options,
  value,
  onChange,
  disabled,
}: {
  id: string;
  label: string;
  roleWord: string;
  options: WebApproverOption[];
  value: number | null;
  onChange: (v: number | null) => void;
  disabled: boolean;
}) {
  if (options.length === 0) {
    return (
      <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2.5 text-[12px] text-red-700 flex items-start gap-1.5">
        <AlertCircle size={13} className="mt-0.5 shrink-0" />
        <span>No {roleWord} assigned — ask an admin to assign one.</span>
      </div>
    );
  }
  if (options.length === 1) {
    const only = options[0];
    return (
      <div>
        <p className="text-[11px] font-semibold uppercase tracking-wider text-sage-navy mb-1">{label}</p>
        <div className="rounded-md border border-stone-200 bg-stone-50 px-3 py-2.5 min-w-0">
          <p className="text-[13px] font-medium text-gray-900 break-words">{only.name}</p>
          <p className="text-[11px] text-gray-500 break-all">{only.email}</p>
        </div>
      </div>
    );
  }
  return (
    <div>
      <label
        htmlFor={id}
        className="block text-[11px] font-semibold uppercase tracking-wider text-sage-navy mb-1"
      >
        {label}
      </label>
      <select
        id={id}
        value={value == null ? "" : String(value)}
        onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
        disabled={disabled}
        className="w-full px-3 py-2.5 text-[14px] rounded-md border border-stone-300 bg-white focus:outline-none focus:ring-2 focus:ring-sage-copper/40 disabled:bg-gray-50"
      >
        <option value="">Select a {roleWord}…</option>
        {options.map((o) => (
          <option key={o.id} value={o.id}>
            {o.name} ({o.email})
          </option>
        ))}
      </select>
    </div>
  );
}
