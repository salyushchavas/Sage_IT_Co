"use client";

import { useEffect, useState } from "react";

import {
  getFinanceDashboard,
  type FinanceCheckRow,
  type FinanceDashboardSummary,
} from "@/lib/api";
import { Stat, moneyFmt } from "./FinanceParts";

export function FinanceOverviewTab({ checks }: { checks: FinanceCheckRow[] }) {
  const [summary, setSummary] = useState<FinanceDashboardSummary | null>(null);
  useEffect(() => {
    getFinanceDashboard()
      .then(setSummary)
      .catch(() => {});
  }, []);

  const pending = checks.filter((c) => c.reviewStatus === "PENDING").length;
  const approved = checks.filter((c) => c.reviewStatus === "APPROVED").length;
  const totalAmount = checks
    .filter((c) => c.reviewStatus === "APPROVED" && c.amount != null)
    .reduce((sum, c) => sum + (Number(c.amount) || 0), 0);

  return (
    <div className="space-y-5">
      <h1 className="text-2xl font-bold text-gray-900">Finance overview</h1>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <Stat label="Active payment plans" value={summary?.activePlans ?? 0} />
        <Stat
          label="Unpaid invoices"
          value={summary?.unpaidInvoices ?? 0}
          accent="amber"
        />
        <Stat
          label="Overdue invoices"
          value={summary?.overdueInvoices ?? 0}
          accent="red"
        />
        <Stat
          label="Collected"
          value={moneyFmt(summary?.totalCollected ?? 0)}
          accent="emerald"
        />
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
        <Stat
          label="Checks pending review"
          value={pending}
          accent="amber"
        />
        <Stat label="Checks approved" value={approved} accent="emerald" />
        <Stat label="Check total" value={moneyFmt(totalAmount)} />
      </div>
    </div>
  );
}
