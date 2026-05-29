"use client";

import { useEffect, useState } from "react";

import {
  bulkGenerateInvoices,
  getFinanceInvoices,
  markOverdueInvoices,
  type InvoiceDTO,
} from "@/lib/api";
import { Pill, Spinner, moneyFmt } from "./FinanceParts";

export function FinanceInvoicesTab() {
  const [rows, setRows] = useState<InvoiceDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [filter, setFilter] = useState("ALL");

  useEffect(() => {
    let cancelled = false;
    getFinanceInvoices(filter === "ALL" ? undefined : filter)
      .then((r) => {
        if (!cancelled) setRows(r);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [filter]);

  const refresh = async () => {
    setRows(await getFinanceInvoices(filter === "ALL" ? undefined : filter));
  };

  const runBulkGenerate = async () => {
    setBusy(true);
    try {
      await bulkGenerateInvoices();
      await refresh();
    } finally {
      setBusy(false);
    }
  };
  const runMarkOverdue = async () => {
    setBusy(true);
    try {
      await markOverdueInvoices();
      await refresh();
    } finally {
      setBusy(false);
    }
  };

  if (loading) return <Spinner />;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <h1 className="text-2xl font-bold text-gray-900">Invoices</h1>
        <div className="flex gap-1.5">
          <button
            onClick={runBulkGenerate}
            disabled={busy}
            className="px-2.5 py-1 rounded-md text-[10px] font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            Generate due
          </button>
          <button
            onClick={runMarkOverdue}
            disabled={busy}
            className="px-2.5 py-1 rounded-md text-[10px] font-bold bg-red-700 text-white hover:bg-red-800 disabled:opacity-60 cursor-pointer"
          >
            Mark overdue
          </button>
        </div>
      </div>
      <div className="inline-flex rounded-lg border border-gray-200 bg-gray-50 p-1 text-xs">
        {["ALL", "UNPAID", "PARTIAL", "PAID", "OVERDUE"].map((s) => (
          <button
            key={s}
            onClick={() => setFilter(s)}
            className={
              "px-2.5 py-1 rounded-md font-semibold cursor-pointer " +
              (filter === s
                ? "bg-sage-navy text-white"
                : "text-gray-600 hover:text-sage-navy")
            }
          >
            {s}
          </button>
        ))}
      </div>

      <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Invoice</th>
              <th className="text-left px-4 py-2">User</th>
              <th className="text-left px-4 py-2">Amount</th>
              <th className="text-left px-4 py-2">Balance</th>
              <th className="text-left px-4 py-2">Due</th>
              <th className="text-left px-4 py-2">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {rows.length === 0 ? (
              <tr>
                <td
                  colSpan={6}
                  className="px-4 py-6 text-center text-sm text-gray-400 italic"
                >
                  No invoices match this filter.
                </td>
              </tr>
            ) : (
              rows.map((i) => (
                <tr key={i.id}>
                  <td className="px-4 py-2 font-mono text-xs text-gray-700">
                    {i.invoiceNumber}
                  </td>
                  <td className="px-4 py-2 text-gray-700">#{i.userId}</td>
                  <td className="px-4 py-2 text-gray-700">
                    {moneyFmt(i.amount)}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {moneyFmt(i.balance)}
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-gray-700">
                    {i.dueDate ?? "--"}
                  </td>
                  <td className="px-4 py-2">
                    <Pill>{i.status}</Pill>
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
