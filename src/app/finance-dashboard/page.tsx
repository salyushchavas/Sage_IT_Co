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
  useUrlTab,
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
  loginHere,
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
// router and API layer. All amounts are US dollars (checklist 5.3).

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
const TAB_IDS = TABS.map((t) => t.id);

export default function FinanceDashboardPage() {
  const router = useRouter();
  const { user, isLoading } = useAuth();
  // The open tab is in the URL (?tab=<id>): a refresh or Back keeps it.
  const [active, setActive] = useUrlTab<TabId>(TAB_IDS, "home");
  const [checks, setChecks] = useState<FinanceCheckRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.replace(loginHere());
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
    // Checklist 2.4: check copies are Finance's only (System Admin
    // included); Operations sees the other finance tabs.
    if (role !== "FINANCE" && role !== "SYSTEM_ADMIN") {
      setLoading(false);
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

  const role = (user?.role ?? "").toUpperCase();
  const canSeeChecks = role === "FINANCE" || role === "SYSTEM_ADMIN";
  // Operations admins look; only Finance and the System Admin change money.
  const readOnly = !canSeeChecks;
  const tabs = canSeeChecks ? TABS : TABS.filter((t) => t.id !== "checks");
  // ?tab=checks for someone who can't see checks opens the overview.
  const shown: TabId = tabs.some((t) => t.id === active) ? active : "home";

  return (
    <RoleDashboardShell
      title="Finance"
      tabs={tabs}
      active={shown}
      onSelect={(id) => setActive(id as TabId)}
    >
      {error && (
        <p className="mb-4 inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      {/* null: check copies aren't this role's (no "0 pending" tiles). */}
      {shown === "home" && <FinanceOverviewTab checks={canSeeChecks ? checks : null} />}
      {readOnly && (
        <p className="mb-4 text-xs text-gray-500">View only: plans, invoices and payments are changed by Finance.</p>
      )}
      {shown === "plans" && <FinancePlansTab readOnly={readOnly} />}
      {shown === "invoices" && <FinanceInvoicesTab readOnly={readOnly} />}
      {shown === "payments" && <FinancePaymentsLedgerTab readOnly={readOnly} />}
      {shown === "checks" && canSeeChecks && (
        <FinanceChecksTab checks={checks} onRefresh={refreshChecks} />
      )}
      {shown === "tracking" && <FinanceTrackingTab readOnly={readOnly} />}
      {shown === "excepts" && <FinanceExceptionsTab />}
    </RoleDashboardShell>
  );
}
