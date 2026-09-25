"use client";

import { Fragment, useEffect, useState } from "react";
import { AlertCircle, Loader2 } from "lucide-react";

import {
  createFinancePlan,
  generateInvoice,
  getFinancePlans,
  getPlanCandidates,
  previewFinancePlan,
  updateFinancePlan,
  type FinancePlanRow,
  type PaymentScheduleItem,
  type PlanCandidate,
} from "@/lib/api";
import { businessToday, formatDateMedium, formatDay } from "@/lib/datetime";
import { Field, Pill, Spinner, moneyFmt } from "./FinanceParts";

/**
 * Checklist 5.1: Finance picks the participant by name, and the server
 * builds the schedule — equal monthly instalments in whole cents that add
 * up exactly to the total, due on the same day each month. A plan can be
 * changed until its first invoice; if the participant had accepted it,
 * they're asked to accept the changed plan.
 */
/** readOnly: Operations admins look; only Finance and the System Admin change money. */
export function FinancePlansTab({ readOnly = false }: { readOnly?: boolean }) {
  const [rows, setRows] = useState<FinancePlanRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [dialog, setDialog] = useState<{ plan?: FinancePlanRow } | null>(null);
  const [openId, setOpenId] = useState<number | null>(null);
  const [error, setError] = useState("");

  const refresh = async () => setRows(await getFinancePlans());

  const [loadError, setLoadError] = useState("");

  useEffect(() => {
    let cancelled = false;
    refresh()
      .catch((e) => {
        if (!cancelled) setLoadError(e instanceof Error ? e.message : "Couldn't load the plans");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const invoice = async (id: number) => {
    setError("");
    try {
      await generateInvoice(id);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't issue the invoice");
    }
  };

  if (loading) return <Spinner />;
  // A failed load must not look like "No payment plans yet".
  if (loadError) return <p className="text-sm text-red-700">Couldn&apos;t load the plans: {loadError}</p>;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <h1 className="text-2xl font-bold text-gray-900">Payment plans</h1>
        {!readOnly && (
          <button
            onClick={() => setDialog({})}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
          >
            + Create plan
          </button>
        )}
      </div>
      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}

      <div className="rounded-2xl border border-gray-100 bg-white overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Plan #</th>
              <th className="text-left px-4 py-2">Participant</th>
              <th className="text-left px-4 py-2">Total</th>
              <th className="text-left px-4 py-2">Installments</th>
              <th className="text-left px-4 py-2">Status</th>
              <th className="text-left px-4 py-2">Accepted</th>
              <th className="text-right px-4 py-2">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {rows.length === 0 ? (
              <tr>
                <td
                  colSpan={7}
                  className="px-4 py-6 text-center text-sm text-gray-400 italic"
                >
                  No payment plans yet.
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <Fragment key={r.id}>
                  <tr>
                    <td className="px-4 py-2 font-mono text-xs text-gray-700">
                      {r.planNumber}
                    </td>
                    <td className="px-4 py-2">
                      <div className="font-medium text-gray-900">
                        {r.participantName ?? "--"}
                      </div>
                      <div className="font-mono text-[10px] text-gray-400">
                        {r.participantId ?? "--"}
                      </div>
                    </td>
                    <td className="px-4 py-2 text-gray-700">
                      {moneyFmt(r.totalAmount)}
                    </td>
                    <td className="px-4 py-2 text-gray-700">
                      {r.installments ?? "--"}
                      <button
                        onClick={() => setOpenId(openId === r.id ? null : r.id)}
                        className="ml-2 text-[10px] font-semibold text-sage-navy hover:text-sage-navy-deep cursor-pointer"
                      >
                        {openId === r.id ? "Hide" : "Schedule"}
                      </button>
                    </td>
                    <td className="px-4 py-2">
                      <Pill>{r.status === "PENDING" ? "WAITING FOR PARTICIPANT" : r.status}</Pill>
                    </td>
                    <td className="px-4 py-2 text-xs text-gray-500">
                      {r.acceptedAt ? formatDateMedium(r.acceptedAt) : "--"}
                    </td>
                    <td className="px-4 py-2 text-right">
                      <div className="inline-flex gap-1.5">
                        {!readOnly && r.invoiceCount === 0 && (r.status === "PENDING" || r.status === "ACTIVE") && (
                          <button
                            onClick={() => setDialog({ plan: r })}
                            className="px-2 py-1 rounded-md text-[10px] font-bold bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy cursor-pointer"
                          >
                            Edit
                          </button>
                        )}
                        {!readOnly && r.status === "ACTIVE" && r.invoiceCount < (r.installments ?? 0) && (
                          <button
                            onClick={() => invoice(r.id)}
                            className="px-2 py-1 rounded-md text-[10px] font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
                          >
                            + Invoice
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                  {openId === r.id && (
                    <tr>
                      <td colSpan={7} className="px-4 pb-3">
                        <ScheduleTable schedule={r.schedule} />
                        <p className="mt-1 text-[11px] text-gray-500">
                          {r.invoiceCount} of {r.installments ?? r.schedule.length} invoiced.
                        </p>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))
            )}
          </tbody>
        </table>
      </div>

      {dialog && (
        <PlanDialog
          plan={dialog.plan}
          onClose={() => setDialog(null)}
          onSaved={refresh}
        />
      )}
    </div>
  );
}

function ScheduleTable({ schedule }: { schedule: PaymentScheduleItem[] }) {
  return (
    <div className="rounded-xl border border-gray-100 bg-white overflow-x-auto">
      <table className="w-full text-xs">
        <thead className="bg-gray-50 text-[10px] uppercase tracking-wider font-semibold text-gray-500">
          <tr>
            <th className="text-left px-3 py-1.5 w-10">#</th>
            <th className="text-left px-3 py-1.5">Due date</th>
            <th className="text-right px-3 py-1.5">Amount</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {schedule.map((s, i) => (
            <tr key={i}>
              <td className="px-3 py-1.5 text-gray-500">{i + 1}</td>
              <td className="px-3 py-1.5 text-gray-700">{formatDay(s.dueDate)}</td>
              <td className="px-3 py-1.5 text-right text-gray-700">{moneyFmt(s.amount)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Create a plan, or change one that has no invoices yet. */
function PlanDialog({
  plan,
  onClose,
  onSaved,
}: {
  plan?: FinancePlanRow;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const editing = !!plan;
  const [candidates, setCandidates] = useState<PlanCandidate[]>([]);
  const [participantId, setParticipantId] = useState("");
  const [total, setTotal] = useState(plan?.totalAmount != null ? String(plan.totalAmount) : "");
  const [installments, setInstallments] = useState(String(plan?.installments ?? 3));
  const [firstDue, setFirstDue] = useState(plan?.schedule?.[0]?.dueDate ?? "");
  const [preview, setPreview] = useState<PaymentScheduleItem[]>([]);
  const [previewError, setPreviewError] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    // A failed load says so (it used to read "Nobody is ready for a plan").
    if (!editing) getPlanCandidates().then(setCandidates).catch((e) =>
      setError(e instanceof Error ? `Couldn't load who is ready for a plan: ${e.message}` : "Couldn't load who is ready for a plan"));
  }, [editing]);

  // The server's schedule, shown as Finance types.
  useEffect(() => {
    const totalNum = Number(total);
    const n = Number(installments);
    if (!total || !firstDue || !n || Number.isNaN(totalNum)) {
      setPreview([]);
      setPreviewError("");
      return;
    }
    const timer = setTimeout(() => {
      previewFinancePlan({ totalAmount: totalNum, installments: n, firstDueDate: firstDue })
        .then((s) => {
          setPreview(s);
          setPreviewError("");
        })
        .catch((e) => {
          setPreview([]);
          setPreviewError(e instanceof Error ? e.message : "Check the amounts");
        });
    }, 300);
    return () => clearTimeout(timer);
  }, [total, installments, firstDue]);

  const handleSubmit = async () => {
    setSaving(true);
    setError("");
    try {
      const terms = { totalAmount: Number(total), installments: Number(installments), firstDueDate: firstDue };
      if (plan) await updateFinancePlan(plan.id, terms);
      else await createFinancePlan({ ...terms, participantId: Number(participantId) });
      await onSaved();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't save the plan");
    } finally {
      setSaving(false);
    }
  };

  const ready = (editing || participantId) && total && firstDue && preview.length > 0 && !saving;
  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-black/40 p-4"
      onClick={onClose}
    >
      <div
        className="bg-white rounded-2xl shadow-xl max-w-md w-full p-5 max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-lg font-bold text-gray-900">
          {editing ? `Change plan ${plan?.planNumber}` : "Create payment plan"}
        </h2>
        <p className="text-xs text-gray-500 mt-1">
          {editing && plan?.acceptedAt
            ? "The participant already accepted this plan. They'll be emailed to accept the changed plan, and no invoices go out until they do."
            : "Equal monthly installments, due on the same day each month. The participant is emailed to review and accept it."}
        </p>
        <div className="mt-3 space-y-2">
          {editing ? (
            <p className="text-sm text-gray-800">
              {plan?.participantName}{" "}
              <span className="font-mono text-[10px] text-gray-400">{plan?.participantId}</span>
            </p>
          ) : (
            <div>
              <label className="block text-[11px] font-medium text-gray-600 mb-0.5">Participant</label>
              <select
                value={participantId}
                onChange={(e) => setParticipantId(e.target.value)}
                className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200 bg-white"
              >
                <option value="">-- Pick a participant --</option>
                {candidates.map((c) => (
                  <option key={c.userId} value={c.userId}>
                    {c.fullName} · {c.participantId}
                    {c.employer ? ` · ${c.employer}` : ""}
                  </option>
                ))}
              </select>
              {candidates.length === 0 && (
                <p className="mt-0.5 text-[10px] text-gray-400">
                  Nobody is ready for a plan: participants appear here once they&apos;ve completed Phase 1 and don&apos;t have a plan.
                </p>
              )}
            </div>
          )}
          <Field label="Total amount (USD)" type="number" value={total} onChange={setTotal} />
          <Field label="Installments" type="number" value={installments} onChange={setInstallments} />
          <div>
            <label className="block text-[11px] font-medium text-gray-600 mb-0.5">First installment due date</label>
            <input
              type="date"
              value={firstDue}
              min={businessToday()}
              onChange={(e) => setFirstDue(e.target.value)}
              className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </div>
          {previewError && (
            <p className="inline-flex items-center gap-1.5 text-xs text-red-700">
              <AlertCircle size={12} /> {previewError}
            </p>
          )}
          {preview.length > 0 && <ScheduleTable schedule={preview} />}
        </div>
        {error && (
          <p className="mt-2 inline-flex items-center gap-1.5 text-sm text-red-700">
            <AlertCircle size={14} /> {error}
          </p>
        )}
        <div className="mt-4 flex justify-end gap-2">
          <button
            onClick={onClose}
            className="px-3 py-1.5 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={!ready}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {saving ? (
              <Loader2 size={12} className="animate-spin" />
            ) : editing ? (
              "Save changes"
            ) : (
              "Create plan"
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
