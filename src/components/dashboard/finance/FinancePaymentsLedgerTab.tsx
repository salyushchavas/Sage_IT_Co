"use client";

import { useEffect, useState } from "react";
import { AlertCircle, Loader2 } from "lucide-react";

import {
  getFinanceInvoices,
  getFinanceLedger,
  recordPaymentReceipt,
  type FinanceInvoiceRow,
  type FinanceLedgerRow,
  type LedgerEntryType,
} from "@/lib/api";
import { businessToday, formatDay } from "@/lib/datetime";
import { Field, Pill, Spinner, moneyFmt } from "./FinanceParts";

const ENTRY_LABEL: Record<LedgerEntryType, string> = {
  PAYMENT: "Payment",
  FAILED: "Failed payment",
  WAIVER: "Waiver",
  REVERSAL: "Reversal",
};

const METHOD_LABEL: Record<string, string> = {
  CHEQUE: "Check",
  BANK_TRANSFER: "Bank transfer",
  CARD: "Card",
  CASH: "Cash",
  ONLINE: "Online",
  WAIVER: "Waiver",
  ADJUSTMENT: "Adjustment",
};

/**
 * Checklist 5.2: the ledger takes payments, failed payments (the balance
 * doesn't change), waivers and reversals of an earlier payment (e.g. a
 * bounced check). A payment can't be more than the invoice's balance.
 */
/** readOnly: Operations admins look; only Finance and the System Admin change money. */
export function FinancePaymentsLedgerTab({ readOnly = false }: { readOnly?: boolean }) {
  const [rows, setRows] = useState<FinanceLedgerRow[]>([]);
  const [invoices, setInvoices] = useState<FinanceInvoiceRow[]>([]);
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
        {!readOnly && (
          <button
            onClick={() => setShowRecord(true)}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
          >
            + Record entry
          </button>
        )}
      </div>

      <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Date</th>
              <th className="text-left px-4 py-2">Participant</th>
              <th className="text-left px-4 py-2">Invoice</th>
              <th className="text-left px-4 py-2">Type</th>
              <th className="text-left px-4 py-2">Amount</th>
              <th className="text-left px-4 py-2">Method</th>
              <th className="text-left px-4 py-2">Balance after</th>
              <th className="text-left px-4 py-2">Notes</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {rows.length === 0 ? (
              <tr>
                <td
                  colSpan={8}
                  className="px-4 py-6 text-center text-sm text-gray-400 italic"
                >
                  No payments recorded yet.
                </td>
              </tr>
            ) : (
              rows.map((r) => {
                const type = (r.entryType ?? "PAYMENT") as LedgerEntryType;
                return (
                  <tr key={r.id}>
                    <td className="px-4 py-2 text-xs text-gray-700">
                      {r.receiptDate ? formatDay(r.receiptDate) : "--"}
                    </td>
                    <td className="px-4 py-2">
                      <div className="font-medium text-gray-900">{r.participantName ?? `#${r.userId}`}</div>
                      <div className="font-mono text-[10px] text-gray-400">{r.participantId ?? ""}</div>
                    </td>
                    <td className="px-4 py-2 font-mono text-xs text-gray-500">
                      {r.invoiceNumber ?? (r.invoiceId ? `#${r.invoiceId}` : "--")}
                    </td>
                    <td className="px-4 py-2">
                      <Pill>{ENTRY_LABEL[type] ?? type}</Pill>
                      {r.reversed && (
                        <div className="mt-0.5 text-[10px] text-red-700">reversed</div>
                      )}
                    </td>
                    <td
                      className={
                        "px-4 py-2 " +
                        (type === "FAILED" ? "text-gray-400 line-through" : "text-gray-700")
                      }
                    >
                      {type === "REVERSAL" ? "−" : ""}
                      {moneyFmt(r.amountReceived)}
                    </td>
                    <td className="px-4 py-2 text-gray-700">
                      {r.method ? METHOD_LABEL[r.method] ?? r.method : "--"}
                    </td>
                    <td className="px-4 py-2 text-gray-700">
                      {moneyFmt(r.balance)}
                    </td>
                    <td className="px-4 py-2 text-xs text-gray-500 truncate max-w-[200px]">
                      {r.notes ?? ""}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {showRecord && (
        <RecordEntryModal
          invoices={invoices}
          ledger={rows}
          onClose={() => setShowRecord(false)}
          onSaved={refresh}
        />
      )}
    </div>
  );
}

function RecordEntryModal({
  invoices,
  ledger,
  onClose,
  onSaved,
}: {
  invoices: FinanceInvoiceRow[];
  ledger: FinanceLedgerRow[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [type, setType] = useState<LedgerEntryType>("PAYMENT");
  const [invoiceId, setInvoiceId] = useState<string>("");
  const [amount, setAmount] = useState("");
  const [date, setDate] = useState(businessToday());
  const [method, setMethod] = useState("CHEQUE");
  const [notes, setNotes] = useState("");
  const [reversesId, setReversesId] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  // Payments and waivers go on invoices with a balance; a reversal undoes
  // a payment on any invoice; a failed payment can be on any open invoice.
  const choices = invoices.filter((i) =>
    type === "REVERSAL"
      ? ledger.some((l) => l.invoiceId === i.id && (l.entryType ?? "PAYMENT") === "PAYMENT" && !l.reversed)
      : i.status === "UNPAID" || i.status === "PARTIAL" || i.status === "OVERDUE",
  );
  const invoice = invoices.find((i) => String(i.id) === invoiceId);
  const payments = ledger.filter(
    (l) => String(l.invoiceId) === invoiceId && (l.entryType ?? "PAYMENT") === "PAYMENT" && !l.reversed,
  );
  const needsReason = type !== "PAYMENT";

  const handleSubmit = async () => {
    setSaving(true);
    setError("");
    try {
      await recordPaymentReceipt({
        invoiceId: Number(invoiceId),
        entryType: type,
        amountReceived: type === "REVERSAL" ? undefined : Number(amount),
        receiptDate: date || undefined,
        method: type === "WAIVER" || type === "REVERSAL" ? undefined : method,
        notes,
        reversesLedgerId: type === "REVERSAL" ? Number(reversesId) : undefined,
      });
      await onSaved();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't record it");
    } finally {
      setSaving(false);
    }
  };

  const ready =
    !!invoiceId &&
    (type === "REVERSAL" ? !!reversesId : !!amount) &&
    (!needsReason || notes.trim().length >= 3) &&
    !saving;

  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-black/40 p-4"
      onClick={onClose}
    >
      <div
        className="bg-white rounded-2xl shadow-xl max-w-md w-full p-5"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-lg font-bold text-gray-900">Record a ledger entry</h2>
        <div className="mt-3 inline-flex rounded-lg border border-gray-200 bg-gray-50 p-1 text-xs">
          {(Object.keys(ENTRY_LABEL) as LedgerEntryType[]).map((t) => (
            <button
              key={t}
              onClick={() => {
                setType(t);
                setInvoiceId("");
                setReversesId("");
              }}
              className={
                "px-2.5 py-1 rounded-md font-semibold cursor-pointer " +
                (type === t ? "bg-sage-navy text-white" : "text-gray-600 hover:text-sage-navy")
              }
            >
              {ENTRY_LABEL[t]}
            </button>
          ))}
        </div>
        <p className="mt-1.5 text-[11px] text-gray-500">
          {type === "PAYMENT" && "Money received. It can't be more than the invoice's balance."}
          {type === "FAILED" && "An attempt that didn't go through (declined card, check that never cleared). The balance doesn't change; the participant is emailed."}
          {type === "WAIVER" && "Part or all of the balance written off. Give the reason."}
          {type === "REVERSAL" && "Undo a recorded payment (e.g. a bounced check). Its amount goes back on the balance; the participant is emailed."}
        </p>
        <div className="mt-3 space-y-2">
          <div>
            <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
              Invoice
            </label>
            <select
              value={invoiceId}
              onChange={(e) => {
                setInvoiceId(e.target.value);
                setReversesId("");
              }}
              className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200"
            >
              <option value="">-- Pick invoice --</option>
              {choices.map((i) => (
                <option key={i.id} value={i.id}>
                  {i.invoiceNumber} · {i.participantName ?? `user #${i.userId}`} ·{" "}
                  {moneyFmt(i.balance ?? i.amount)} balance
                </option>
              ))}
            </select>
          </div>
          {type === "REVERSAL" ? (
            <div>
              <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
                Payment to reverse
              </label>
              <select
                value={reversesId}
                onChange={(e) => setReversesId(e.target.value)}
                className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200"
              >
                <option value="">-- Pick the payment --</option>
                {payments.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.receiptDate ? formatDay(l.receiptDate) : "--"} · {moneyFmt(l.amountReceived)} ·{" "}
                    {l.method ? METHOD_LABEL[l.method] ?? l.method : ""}
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <Field
              label={
                type === "WAIVER"
                  ? "Amount to waive"
                  : type === "FAILED"
                    ? "Amount attempted"
                    : `Amount received${invoice ? ` (balance ${moneyFmt(invoice.balance ?? invoice.amount)})` : ""}`
              }
              type="number"
              value={amount}
              onChange={setAmount}
            />
          )}
          <div>
            <label className="block text-[11px] font-medium text-gray-600 mb-0.5">Date</label>
            <input
              type="date"
              value={date}
              max={businessToday()}
              onChange={(e) => setDate(e.target.value)}
              className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </div>
          {(type === "PAYMENT" || type === "FAILED") && (
            <div>
              <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
                Method
              </label>
              <select
                value={method}
                onChange={(e) => setMethod(e.target.value)}
                className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200"
              >
                {["CHEQUE", "BANK_TRANSFER", "CARD", "CASH", "ONLINE"].map((m) => (
                  <option key={m} value={m}>
                    {METHOD_LABEL[m]}
                  </option>
                ))}
              </select>
            </div>
          )}
          <Field
            label={needsReason ? "Reason (required)" : "Notes (optional)"}
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
            disabled={!ready}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {saving ? <Loader2 size={12} className="animate-spin" /> : "Record"}
          </button>
        </div>
      </div>
    </div>
  );
}
