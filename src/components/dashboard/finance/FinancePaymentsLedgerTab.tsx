"use client";

import { useEffect, useState } from "react";
import { AlertCircle, Loader2 } from "lucide-react";

import {
  getFinanceInvoices,
  getFinanceLedger,
  recordPaymentReceipt,
  type InvoiceDTO,
  type PaymentLedgerDTO,
} from "@/lib/api";
import { Field, Spinner, moneyFmt } from "./FinanceParts";

export function FinancePaymentsLedgerTab() {
  const [rows, setRows] = useState<PaymentLedgerDTO[]>([]);
  const [invoices, setInvoices] = useState<InvoiceDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [showRecord, setShowRecord] = useState(false);

  const refresh = async () => {
    const [l, inv] = await Promise.all([
      getFinanceLedger(),
      getFinanceInvoices(),
    ]);
    setRows(l);
    setInvoices(inv);
  };

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
        <h1 className="text-2xl font-bold text-gray-900">Payments ledger</h1>
        <button
          onClick={() => setShowRecord(true)}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
        >
          + Record payment
        </button>
      </div>
      <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Date</th>
              <th className="text-left px-4 py-2">User</th>
              <th className="text-left px-4 py-2">Invoice</th>
              <th className="text-left px-4 py-2">Amount</th>
              <th className="text-left px-4 py-2">Method</th>
              <th className="text-left px-4 py-2">Balance</th>
              <th className="text-left px-4 py-2">Notes</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {rows.length === 0 ? (
              <tr>
                <td
                  colSpan={7}
                  className="px-4 py-6 text-center text-sm text-gray-400 italic"
                >
                  No payments recorded yet.
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr key={r.id}>
                  <td className="px-4 py-2 font-mono text-xs text-gray-700">
                    {r.receiptDate ?? "--"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">#{r.userId}</td>
                  <td className="px-4 py-2 text-xs text-gray-500">
                    #{r.invoiceId ?? "--"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {moneyFmt(r.amountReceived)}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {r.method ?? "--"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {moneyFmt(r.balance)}
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-500 truncate max-w-[200px]">
                    {r.notes ?? ""}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {showRecord && (
        <RecordPaymentModal
          invoices={invoices}
          onClose={() => setShowRecord(false)}
          onSaved={refresh}
        />
      )}
    </div>
  );
}

function RecordPaymentModal({
  invoices,
  onClose,
  onSaved,
}: {
  invoices: InvoiceDTO[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const unpaid = invoices.filter(
    (i) =>
      i.status === "UNPAID" ||
      i.status === "PARTIAL" ||
      i.status === "OVERDUE",
  );
  const [invoiceId, setInvoiceId] = useState<string>("");
  const [amount, setAmount] = useState("");
  const [date, setDate] = useState("");
  const [method, setMethod] = useState("CHEQUE");
  const [notes, setNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async () => {
    setSaving(true);
    setError("");
    try {
      await recordPaymentReceipt({
        invoiceId: Number(invoiceId),
        amountReceived: Number(amount),
        receiptDate: date || undefined,
        method,
        notes,
      });
      await onSaved();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't record payment");
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
        <h2 className="text-lg font-bold text-gray-900">Record payment</h2>
        <div className="mt-3 space-y-2">
          <div>
            <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
              Invoice
            </label>
            <select
              value={invoiceId}
              onChange={(e) => setInvoiceId(e.target.value)}
              className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200"
            >
              <option value="">-- Pick invoice --</option>
              {unpaid.map((i) => (
                <option key={i.id} value={i.id}>
                  {i.invoiceNumber} · user #{i.userId} ·{" "}
                  {moneyFmt(i.balance ?? i.amount)} balance
                </option>
              ))}
            </select>
          </div>
          <Field
            label="Amount received"
            type="number"
            value={amount}
            onChange={setAmount}
          />
          <Field
            label="Receipt date"
            type="date"
            value={date}
            onChange={setDate}
          />
          <div>
            <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
              Method
            </label>
            <select
              value={method}
              onChange={(e) => setMethod(e.target.value)}
              className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200"
            >
              {["CHEQUE", "BANK_TRANSFER", "CARD", "CASH", "ADJUSTMENT"].map(
                (m) => (
                  <option key={m} value={m}>
                    {m}
                  </option>
                ),
              )}
            </select>
          </div>
          <Field
            label="Notes (optional)"
            value={notes}
            onChange={setNotes}
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
            disabled={saving || !invoiceId || !amount}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {saving ? <Loader2 size={12} className="animate-spin" /> : "Record"}
          </button>
        </div>
      </div>
    </div>
  );
}
