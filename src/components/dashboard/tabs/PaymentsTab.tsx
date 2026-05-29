"use client";

import { useEffect, useState } from "react";
import { AlertCircle, CheckCircle2, Loader2, Send } from "lucide-react";

import {
  acceptPaymentPlan,
  getParticipantPaymentPlan,
  getParticipantPaymentSummary,
  listParticipantCheckTracking,
  listParticipantInvoices,
  listParticipantPaymentHistory,
  submitCheckTracking,
  type CheckTrackingDTO,
  type InvoiceDTO,
  type ParticipantPaymentPlanResponse,
  type PaymentLedgerDTO,
  type PaymentSummary,
} from "@/lib/api";

const PAYMENT_PLAN_ACK_VERSION = "PPL-v1.0";

/**
 * Participant payments surface. Read-mostly with two write paths:
 *   - Accept the payment plan (PaymentPlanSection -- one-time)
 *   - Submit physical check tracking (CheckTrackingSection)
 *
 * No Razorpay or any other payment-gateway integration in this
 * port -- Spire's PaymentsTab doesn't trigger an online payment
 * flow either. Off-platform settlement (bank transfer / check)
 * is the supported model; this surface tracks the resulting
 * invoices + ledger.
 */
export default function PaymentsTab() {
  const [planRes, setPlanRes] = useState<ParticipantPaymentPlanResponse | null>(null);
  const [invoices, setInvoices] = useState<InvoiceDTO[]>([]);
  const [summary, setSummary] = useState<PaymentSummary>({});
  const [history, setHistory] = useState<PaymentLedgerDTO[]>([]);
  const [trackings, setTrackings] = useState<CheckTrackingDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const refresh = async () => {
    const [p, inv, s, h, t] = await Promise.all([
      getParticipantPaymentPlan(),
      listParticipantInvoices(),
      getParticipantPaymentSummary(),
      listParticipantPaymentHistory(),
      listParticipantCheckTracking(),
    ]);
    setPlanRes(p);
    setInvoices(inv);
    setSummary(s);
    setHistory(h);
    setTrackings(t);
  };

  useEffect(() => {
    let cancelled = false;
    refresh()
      .catch((e) => {
        if (!cancelled)
          setError(e instanceof Error ? e.message : "Couldn't load payments");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) {
    return (
      <div className="text-center py-10">
        <Loader2 size={20} className="animate-spin text-sage-navy inline" />
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <h1 className="text-2xl font-bold text-gray-900">Payments &amp; invoices</h1>
      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}

      <PaymentPlanSection res={planRes} onAccepted={refresh} />
      {planRes?.plan?.acceptedAt && (
        <>
          <CheckTrackingSection trackings={trackings} onAdded={refresh} />
          <InvoicesSection invoices={invoices} />
          <PaymentSummarySection summary={summary} history={history} />
        </>
      )}
    </div>
  );
}

function PaymentPlanSection({
  res,
  onAccepted,
}: {
  res: ParticipantPaymentPlanResponse | null;
  onAccepted: () => Promise<void>;
}) {
  const [accepted, setAccepted] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const plan = res?.plan;

  if (!plan) {
    return (
      <div className="rounded-2xl border border-dashed border-gray-200 bg-gray-50/60 p-5">
        <p className="text-sm text-gray-700">
          No payment plan on file yet. Once finance creates your plan it will
          appear here for you to review and accept.
        </p>
      </div>
    );
  }

  const handleAccept = async () => {
    if (!accepted) return;
    setSaving(true);
    setError("");
    try {
      await acceptPaymentPlan(plan.id, PAYMENT_PLAN_ACK_VERSION);
      await onAccepted();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't accept plan");
    } finally {
      setSaving(false);
    }
  };

  const alreadyAccepted = !!plan.acceptedAt;
  const schedule = res?.schedule ?? [];

  return (
    <div
      className={
        "rounded-2xl border p-5 space-y-3 " +
        (alreadyAccepted
          ? "border-emerald-200 bg-emerald-50/30"
          : "border-gray-200 bg-white shadow-sm")
      }
    >
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div>
          <h2 className="text-xl font-bold text-gray-900">Payment plan</h2>
          <p className="text-xs text-gray-500 mt-0.5">
            <span className="font-mono">{plan.planId}</span>
            {" · "}Total {formatMoney(plan.totalAmount)}
            {" · "}
            {plan.installments ?? schedule.length} installments
          </p>
        </div>
        {alreadyAccepted && (
          <span className="inline-flex items-center gap-1.5 text-xs font-bold text-emerald-700">
            <CheckCircle2 size={12} /> Accepted
            {plan.acceptedAt &&
              " on " +
                new Date(plan.acceptedAt).toLocaleDateString("en-IN", {
                  timeZone: "Asia/Kolkata",
                  dateStyle: "medium",
                })}
          </span>
        )}
      </div>

      <div className="rounded-xl border border-gray-100 bg-white overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[10px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-3 py-2 w-12">#</th>
              <th className="text-left px-3 py-2">Due date</th>
              <th className="text-left px-3 py-2">Amount</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {schedule.length === 0 ? (
              <tr>
                <td
                  colSpan={3}
                  className="px-3 py-4 text-center text-xs text-gray-400 italic"
                >
                  Schedule pending.
                </td>
              </tr>
            ) : (
              schedule.map((s, idx) => (
                <tr key={idx}>
                  <td className="px-3 py-2 text-gray-700">{idx + 1}</td>
                  <td className="px-3 py-2 font-mono text-xs text-gray-700">
                    {s.dueDate ?? "—"}
                  </td>
                  <td className="px-3 py-2 text-gray-700">
                    {formatMoney(s.amount)}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {!alreadyAccepted && (
        <>
          <div className="rounded-xl border border-gray-200 bg-white p-4 text-sm text-gray-700 leading-relaxed">
            <p className="font-bold text-gray-900">PAYMENT PLAN ACCEPTANCE</p>
            <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
              Version: {PAYMENT_PLAN_ACK_VERSION}
            </p>
            <p className="mt-1.5">By accepting this payment plan, I confirm:</p>
            <ol className="list-decimal pl-5 space-y-1 text-[13px] mt-1">
              <li>I have reviewed the payment schedule above.</li>
              <li>I agree to the installment amounts and due dates as listed.</li>
              <li>
                I understand that late payments may incur additional follow-up
                as per my agreement.
              </li>
              <li>
                I will provide check copies and tracking information through the
                secure portal.
              </li>
            </ol>
          </div>
          <label className="flex items-start gap-2.5 text-sm text-gray-700 cursor-pointer">
            <input
              type="checkbox"
              checked={accepted}
              onChange={(e) => setAccepted(e.target.checked)}
              className="mt-0.5 w-4 h-4 rounded border-gray-300 text-sage-navy focus:ring-sage-copper"
            />
            <span>
              I accept the payment plan ({PAYMENT_PLAN_ACK_VERSION}){" "}
              <span className="text-red-500">*</span>
            </span>
          </label>
          {error && (
            <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
              <AlertCircle size={14} /> {error}
            </p>
          )}
          <div className="flex justify-end">
            <button
              type="button"
              onClick={handleAccept}
              disabled={!accepted || saving}
              className={
                "inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-bold transition " +
                (accepted && !saving
                  ? "bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
                  : "bg-gray-200 text-gray-500 cursor-not-allowed")
              }
            >
              {saving ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <CheckCircle2 size={14} />
              )}
              {saving ? "Accepting…" : "Accept Payment Plan →"}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

function CheckTrackingSection({
  trackings,
  onAdded,
}: {
  trackings: CheckTrackingDTO[];
  onAdded: () => Promise<void>;
}) {
  const [checkNumber, setCheckNumber] = useState("");
  const [carrier, setCarrier] = useState("USPS");
  const [trackingId, setTrackingId] = useState("");
  const [mailedDate, setMailedDate] = useState("");
  const [expectedDate, setExpectedDate] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const canSubmit =
    checkNumber.trim() && trackingId.trim() && mailedDate && !saving;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSaving(true);
    setError("");
    try {
      await submitCheckTracking({
        checkNumber: checkNumber.trim(),
        carrier,
        trackingId: trackingId.trim(),
        mailedDate,
        expectedReceiptDate: expectedDate || null,
      });
      await onAdded();
      setCheckNumber("");
      setTrackingId("");
      setMailedDate("");
      setExpectedDate("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't submit tracking");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="rounded-2xl border border-gray-100 bg-white shadow-sm p-5 space-y-3">
      <h2 className="text-xl font-bold text-gray-900">Physical check tracking</h2>
      <p className="text-sm text-gray-500">
        If paying by physical check, provide tracking details so finance can
        reconcile receipt.
      </p>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
        <Input label="Check number *" value={checkNumber} onChange={setCheckNumber} />
        <div>
          <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
            Carrier *
          </label>
          <select
            value={carrier}
            onChange={(e) => setCarrier(e.target.value)}
            className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200 bg-white"
          >
            {["USPS", "FedEx", "UPS", "DHL", "Other"].map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>
        <Input label="Tracking ID *" value={trackingId} onChange={setTrackingId} />
        <Input
          label="Mailed date *"
          type="date"
          value={mailedDate}
          onChange={setMailedDate}
        />
        <Input
          label="Expected receipt"
          type="date"
          value={expectedDate}
          onChange={setExpectedDate}
        />
      </div>
      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      <div className="flex justify-end">
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!canSubmit}
          className={
            "inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-bold transition " +
            (canSubmit
              ? "bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
              : "bg-gray-200 text-gray-500 cursor-not-allowed")
          }
        >
          {saving ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <Send size={14} />
          )}
          {saving ? "Submitting…" : "Submit tracking info →"}
        </button>
      </div>

      {trackings.length > 0 && (
        <div className="mt-3 rounded-xl border border-gray-100 bg-white overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-[10px] uppercase tracking-wider font-semibold text-gray-500">
              <tr>
                <th className="text-left px-3 py-2">Check #</th>
                <th className="text-left px-3 py-2">Carrier</th>
                <th className="text-left px-3 py-2">Tracking ID</th>
                <th className="text-left px-3 py-2">Mailed</th>
                <th className="text-left px-3 py-2">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {trackings.map((t) => (
                <tr key={t.id}>
                  <td className="px-3 py-2 font-mono text-xs text-gray-700">
                    {t.checkNumber ?? "—"}
                  </td>
                  <td className="px-3 py-2 text-gray-700">{t.carrier ?? "—"}</td>
                  <td className="px-3 py-2 font-mono text-[10px] text-gray-500">
                    {t.trackingId ?? "—"}
                  </td>
                  <td className="px-3 py-2 font-mono text-xs text-gray-700">
                    {t.mailedDate ?? "—"}
                  </td>
                  <td className="px-3 py-2">
                    <span
                      className={
                        "px-2 py-0.5 rounded-full text-[10px] font-bold " +
                        (t.status === "RECEIVED"
                          ? "bg-emerald-50 text-emerald-700"
                          : t.status === "EXCEPTION" || t.status === "LOST"
                            ? "bg-red-50 text-red-700"
                            : "bg-amber-50 text-amber-700")
                      }
                    >
                      {t.status}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function InvoicesSection({ invoices }: { invoices: InvoiceDTO[] }) {
  return (
    <div className="rounded-2xl border border-gray-100 bg-white shadow-sm p-5 space-y-3">
      <h2 className="text-xl font-bold text-gray-900">Invoices</h2>
      <div className="rounded-xl border border-gray-100 bg-white overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[10px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-3 py-2">Invoice #</th>
              <th className="text-left px-3 py-2">Amount</th>
              <th className="text-left px-3 py-2">Due</th>
              <th className="text-left px-3 py-2">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {invoices.length === 0 ? (
              <tr>
                <td
                  colSpan={4}
                  className="px-3 py-4 text-center text-xs text-gray-400 italic"
                >
                  No invoices yet.
                </td>
              </tr>
            ) : (
              invoices.map((i) => (
                <tr key={i.id}>
                  <td className="px-3 py-2 font-mono text-xs text-gray-700">
                    {i.invoiceNumber}
                  </td>
                  <td className="px-3 py-2 text-gray-700">
                    {formatMoney(i.amount)}
                  </td>
                  <td className="px-3 py-2 font-mono text-xs text-gray-700">
                    {i.dueDate ?? "—"}
                  </td>
                  <td className="px-3 py-2">
                    <InvoiceStatusBadge status={i.status} />
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function InvoiceStatusBadge({ status }: { status: string }) {
  const cls =
    status === "PAID"
      ? "bg-emerald-50 text-emerald-700"
      : status === "OVERDUE"
        ? "bg-red-50 text-red-700"
        : status === "PARTIAL"
          ? "bg-blue-50 text-blue-700"
          : "bg-amber-50 text-amber-700";
  return (
    <span
      className={"px-2 py-0.5 rounded-full text-[10px] font-bold " + cls}
    >
      {status}
    </span>
  );
}

function PaymentSummarySection({
  summary,
  history,
}: {
  summary: PaymentSummary;
  history: PaymentLedgerDTO[];
}) {
  return (
    <div className="rounded-2xl border border-gray-100 bg-white shadow-sm p-5 space-y-4">
      <h2 className="text-xl font-bold text-gray-900">Payment summary</h2>
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-2.5">
        <SmallStat label="Total due" value={formatMoney(summary.totalDue)} />
        <SmallStat
          label="Total paid"
          value={formatMoney(summary.totalPaid)}
          accent="emerald"
        />
        <SmallStat label="Balance" value={formatMoney(summary.balance)} />
        <SmallStat
          label="Overdue"
          value={formatMoney(summary.overdue)}
          accent="red"
        />
        <SmallStat
          label="Next due"
          value={
            summary.nextDueAmount
              ? formatMoney(summary.nextDueAmount) +
                " · " +
                (summary.nextDueDate ?? "")
              : "—"
          }
        />
      </div>

      <div>
        <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-1.5">
          Payment history
        </p>
        <div className="rounded-xl border border-gray-100 bg-white overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-[10px] uppercase tracking-wider font-semibold text-gray-500">
              <tr>
                <th className="text-left px-3 py-2">Date</th>
                <th className="text-left px-3 py-2">Amount</th>
                <th className="text-left px-3 py-2">Method</th>
                <th className="text-left px-3 py-2">Invoice</th>
                <th className="text-left px-3 py-2">Balance</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {history.length === 0 ? (
                <tr>
                  <td
                    colSpan={5}
                    className="px-3 py-4 text-center text-xs text-gray-400 italic"
                  >
                    No payments recorded yet.
                  </td>
                </tr>
              ) : (
                history.map((h) => (
                  <tr key={h.id}>
                    <td className="px-3 py-2 font-mono text-xs text-gray-700">
                      {h.receiptDate ?? "—"}
                    </td>
                    <td className="px-3 py-2 text-gray-700">
                      {formatMoney(h.amountReceived)}
                    </td>
                    <td className="px-3 py-2 text-gray-700">
                      {h.method ?? "—"}
                    </td>
                    <td className="px-3 py-2 text-xs text-gray-500">
                      #{h.invoiceId ?? "—"}
                    </td>
                    <td className="px-3 py-2 text-gray-700">
                      {formatMoney(h.balance)}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function SmallStat({
  label,
  value,
  accent,
}: {
  label: string;
  value: string;
  accent?: "emerald" | "red";
}) {
  const accentCls =
    accent === "emerald"
      ? "text-emerald-700"
      : accent === "red"
        ? "text-red-700"
        : "text-gray-900";
  return (
    <div className="rounded-xl border border-gray-100 bg-gray-50/60 p-3">
      <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">
        {label}
      </p>
      <p className={"mt-0.5 text-sm font-bold " + accentCls}>{value}</p>
    </div>
  );
}

function Input({
  label,
  value,
  onChange,
  type = "text",
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
}) {
  return (
    <div>
      <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
        {label}
      </label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
      />
    </div>
  );
}

/**
 * Format a numeric amount as INR currency. Sage's pricing model is
 * Indian Rupees throughout (services + courses + invoices), so we
 * fix the currency at INR rather than locale-detect -- Spire's
 * USD default was a Spire-specific deployment choice that wouldn't
 * match Sage's catalog rates.
 */
function formatMoney(v: string | number | null | undefined): string {
  if (v == null || v === "") return "—";
  const n = typeof v === "number" ? v : Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString("en-IN", { style: "currency", currency: "INR" });
}
