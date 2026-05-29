"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AlertCircle,
  ClipboardList,
  CreditCard,
  FileText,
  Image as ImageIcon,
  LayoutDashboard,
  Loader2,
  Package,
} from "lucide-react";

import {
  RoleDashboardShell,
  type RoleDashboardTab,
} from "@/components/dashboard/RoleDashboardShell";
import { FinanceOverviewTab } from "@/components/dashboard/finance/FinanceOverviewTab";
import { FinancePlansTab } from "@/components/dashboard/finance/FinancePlansTab";
import { FinanceInvoicesTab } from "@/components/dashboard/finance/FinanceInvoicesTab";
import { FinancePaymentsLedgerTab } from "@/components/dashboard/finance/FinancePaymentsLedgerTab";
import { FinanceChecksTab } from "@/components/dashboard/finance/FinanceChecksTab";
import { FinanceTrackingTab } from "@/components/dashboard/finance/FinanceTrackingTab";
import { FinanceExceptionsTab } from "@/components/dashboard/finance/FinanceExceptionsTab";
import { useAuth } from "@/lib/auth-context";
import {
  getFinanceChecks,
  type FinanceCheckRow,
} from "@/lib/api";

// Finance dashboard. Seven tabs:
//   home     -- summary stats
//   plans    -- payment plans + invoice generation
//   invoices -- invoices list + bulk generate / mark overdue
//   payments -- receipt ledger
//   checks   -- un-redacted check soft-copy review (finance/ops only)
//   tracking -- physical check tracking status updates
//   excepts  -- overdue invoices + tracking exceptions
//
// Locked to FINANCE / SYSTEM_ADMIN / OPERATIONS_ADMIN at both the
// router and API layer. All currency rendered in INR (en-IN).

type TabId =
  | "home"
  | "plans"
  | "invoices"
  | "payments"
  | "checks"
  | "tracking"
  | "excepts";

const TABS: ReadonlyArray<RoleDashboardTab> = [
  { id: "home", label: "Overview", Icon: LayoutDashboard },
  { id: "plans", label: "Payment Plans", Icon: CreditCard },
  { id: "invoices", label: "Invoices", Icon: FileText },
  { id: "payments", label: "Payments", Icon: ClipboardList },
  { id: "checks", label: "Check Copies", Icon: ImageIcon },
  { id: "tracking", label: "Check Tracking", Icon: Package },
  { id: "excepts", label: "Exceptions", Icon: AlertCircle },
];

export default function FinanceDashboardPage() {
  const router = useRouter();
  const { user, isLoading } = useAuth();
  const [active, setActive] = useState<TabId>("home");
  const [checks, setChecks] = useState<FinanceCheckRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.replace("/login?redirect=/finance-dashboard");
      return;
    }
    const role = (user.role ?? "").toUpperCase();
    if (
      role !== "FINANCE" &&
      role !== "SYSTEM_ADMIN" &&
      role !== "OPERATIONS_ADMIN"
    ) {
      import("@/lib/api").then(({ dashboardRouteForRole }) => {
        router.replace(dashboardRouteForRole(role));
      });
      return;
    }
    let cancelled = false;
    getFinanceChecks()
      .then((c) => {
        if (!cancelled) setChecks(c);
      })
      .catch((e) => {
        if (!cancelled)
          setError(e instanceof Error ? e.message : "Couldn't load checks");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isLoading, user, router]);

  const refreshChecks = async () => setChecks(await getFinanceChecks());

  if (isLoading || loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <RoleDashboardShell
      title="Finance"
      tabs={TABS}
      active={active}
      onSelect={(id) => setActive(id as TabId)}
    >
      {error && (
        <p className="mb-4 inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      {active === "home" && <FinanceOverviewTab checks={checks} />}
      {active === "plans" && <FinancePlansTab />}
      {active === "invoices" && <FinanceInvoicesTab />}
      {active === "payments" && <FinancePaymentsLedgerTab />}
      {active === "checks" && (
        <FinanceChecksTab checks={checks} onRefresh={refreshChecks} />
      )}
      {active === "tracking" && <FinanceTrackingTab />}
      {active === "excepts" && <FinanceExceptionsTab />}
    </RoleDashboardShell>
  );
}
