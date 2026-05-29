"use client";

import { useEffect, useState } from "react";

import {
  getFinanceInvoices,
  getFinanceTrackings,
  type FinanceTrackingRow,
  type InvoiceDTO,
} from "@/lib/api";
import { Spinner, moneyFmt } from "./FinanceParts";

export function FinanceExceptionsTab() {
  const [overdue, setOverdue] = useState<InvoiceDTO[]>([]);
  const [exceptions, setExceptions] = useState<FinanceTrackingRow[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      getFinanceInvoices("OVERDUE"),
      getFinanceTrackings("EXCEPTION"),
    ])
      .then(([o, e]) => {
        if (cancelled) return;
        setOverdue(o);
        setExceptions(e);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) return <Spinner />;

  return (
    <div className="space-y-5">
      <h1 className="text-2xl font-bold text-gray-900">Finance exceptions</h1>

      <div className="space-y-2">
        <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
          Overdue invoices ({overdue.length})
        </p>
        <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-[10px] uppercase tracking-wider font-semibold text-gray-500">
              <tr>
                <th className="text-left px-3 py-1.5">Invoice</th>
                <th className="text-left px-3 py-1.5">User</th>
                <th className="text-left px-3 py-1.5">Amount</th>
                <th className="text-left px-3 py-1.5">Due</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {overdue.length === 0 ? (
                <tr>
                  <td
                    colSpan={4}
                    className="px-3 py-4 text-center text-xs text-gray-400 italic"
                  >
                    None.
                  </td>
                </tr>
              ) : (
                overdue.map((i) => (
                  <tr key={i.id}>
                    <td className="px-3 py-1.5 font-mono text-xs text-gray-700">
                      {i.invoiceNumber}
                    </td>
                    <td className="px-3 py-1.5 text-gray-700">#{i.userId}</td>
                    <td className="px-3 py-1.5 text-gray-700">
                      {moneyFmt(i.amount)}
                    </td>
                    <td className="px-3 py-1.5 font-mono text-xs text-gray-700">
                      {i.dueDate ?? "--"}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      <div className="space-y-2">
        <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
          Check tracking exceptions ({exceptions.length})
        </p>
        <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-[10px] uppercase tracking-wider font-semibold text-gray-500">
              <tr>
                <th className="text-left px-3 py-1.5">Participant</th>
                <th className="text-left px-3 py-1.5">Check #</th>
                <th className="text-left px-3 py-1.5">Carrier</th>
                <th className="text-left px-3 py-1.5">Tracking ID</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {exceptions.length === 0 ? (
                <tr>
                  <td
                    colSpan={4}
                    className="px-3 py-4 text-center text-xs text-gray-400 italic"
                  >
                    None.
                  </td>
                </tr>
              ) : (
                exceptions.map((e) => (
                  <tr key={e.id}>
                    <td className="px-3 py-1.5 text-gray-700">
                      {e.participantName ?? "--"}
                    </td>
                    <td className="px-3 py-1.5 font-mono text-xs text-gray-700">
                      {e.checkNumber ?? "--"}
                    </td>
                    <td className="px-3 py-1.5 text-gray-700">
                      {e.carrier ?? "--"}
                    </td>
                    <td className="px-3 py-1.5 font-mono text-[10px] text-gray-500">
                      {e.trackingId ?? "--"}
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
