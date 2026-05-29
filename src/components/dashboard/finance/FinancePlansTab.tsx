"use client";

import { useEffect, useState } from "react";
import { AlertCircle, Loader2 } from "lucide-react";

import {
  createFinancePlan,
  generateInvoice,
  getFinancePlans,
  type FinancePlanRow,
} from "@/lib/api";
import { Field, Pill, Spinner, moneyFmt } from "./FinanceParts";

export function FinancePlansTab() {
  const [rows, setRows] = useState<FinancePlanRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);

  const refresh = async () => setRows(await getFinancePlans());

  useEffect(() => {
    let cancelled = false;
    refresh().finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) return <Spinner />;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <h1 className="text-2xl font-bold text-gray-900">Payment plans</h1>
        <button
          onClick={() => setShowCreate(true)}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
        >
          + Create plan
        </button>
      </div>

      <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden">
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
                <tr key={r.id}>
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
                  </td>
                  <td className="px-4 py-2">
                    <Pill>{r.status}</Pill>
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-gray-500">
                    {r.acceptedAt
                      ? new Date(r.acceptedAt).toLocaleDateString("en-IN")
                      : "--"}
                  </td>
                  <td className="px-4 py-2 text-right">
                    {r.status === "ACTIVE" && (
                      <button
                        onClick={async () => {
                          await generateInvoice(r.id);
                          await refresh();
                        }}
                        className="px-2 py-1 rounded-md text-[10px] font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
                      >
                        + Invoice
                      </button>
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {showCreate && (
        <CreatePlanModal
          onClose={() => setShowCreate(false)}
          onCreated={refresh}
        />
      )}
    </div>
  );
}

function CreatePlanModal({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: () => Promise<void>;
}) {
  const [participantId, setParticipantId] = useState("");
  const [total, setTotal] = useState("");
  const [installments, setInstallments] = useState("3");
  const [firstDue, setFirstDue] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async () => {
    setSaving(true);
    setError("");
    try {
      const totalNum = Number(total);
      const n = Math.max(1, Math.min(60, Number(installments) || 1));
      const installmentAmt = Number((totalNum / n).toFixed(2));
      const base = firstDue ? new Date(firstDue + "T00:00:00") : new Date();
      const schedule = Array.from({ length: n }, (_, i) => {
        const d = new Date(base);
        d.setMonth(d.getMonth() + i);
        return {
          dueDate: d.toISOString().slice(0, 10),
          amount: installmentAmt,
          label: `Installment ${i + 1}`,
        };
      });
      await createFinancePlan({
        participantId: Number(participantId),
        totalAmount: totalNum,
        installments: n,
        schedule,
      });
      await onCreated();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't create plan");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-black/40 p-4"
      onClick={onClose}
    >
      <div
        className="bg-white rounded-2xl shadow-xl max-w-md w-full p-5"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-lg font-bold text-gray-900">Create payment plan</h2>
        <p className="text-xs text-gray-500 mt-1">
          Auto-generates a monthly schedule of equal installments starting on
          the first due date.
        </p>
        <div className="mt-3 space-y-2">
          <Field
            label="Participant user ID"
            value={participantId}
            onChange={setParticipantId}
          />
          <Field
            label="Total amount (INR)"
            type="number"
            value={total}
            onChange={setTotal}
          />
          <Field
            label="Installments"
            type="number"
            value={installments}
            onChange={setInstallments}
          />
          <Field
            label="First installment due date"
            type="date"
            value={firstDue}
            onChange={setFirstDue}
          />
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
            disabled={saving || !participantId || !total || !firstDue}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {saving ? (
              <Loader2 size={12} className="animate-spin" />
            ) : (
              "Create plan"
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
