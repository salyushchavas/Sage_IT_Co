"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import {
  ArrowLeft,
  FileText,
  LayoutDashboard,
  Loader2,
  Route,
  ShieldCheck,
  Users,
  Wrench,
  type LucideIcon,
} from "lucide-react";

import AgreementErmShell from "@/components/agreement-erm/AgreementErmShell";
import AdminOverviewTab from "@/components/admin-console/AdminOverviewTab";
import AdminAgreementsTab from "@/components/admin-console/AdminAgreementsTab";
import AdminPeopleTab from "@/components/admin-console/AdminPeopleTab";
import AdminLifecycleTab from "@/components/admin-console/AdminLifecycleTab";
import AdminMaintenanceTab from "@/components/admin-console/AdminMaintenanceTab";
import type { AdminTabId, NavigateFn } from "@/components/admin-console/types";
import { fetchMe, getAgreementErmToken } from "@/lib/api";

/**
 * Super-admin console.
 *
 * Previously a single 1511-line file that put user management, an
 * agreements-by-ERM list and two destructive backfills behind three tabs, with
 * every modal and primitive declared inline. Everything the operator needed to
 * know arrived as one flat table.
 *
 * This file is now only the shell: auth gate, tab routing, and nothing else.
 * Each tab owns its own data loading in src/components/admin-console/, so a
 * tab you are not looking at does not fetch.
 *
 * Tab state lives in the URL (`?tab=`), so a tab can be linked, bookmarked and
 * survive a refresh — it always used to reopen on Users.
 */

const TABS: ReadonlyArray<{
  id: AdminTabId;
  label: string;
  Icon: LucideIcon;
  hint: string;
}> = [
  {
    id: "overview",
    label: "Overview",
    Icon: LayoutDashboard,
    hint: "What is in flight and who is holding it up",
  },
  {
    id: "agreements",
    label: "Agreements",
    Icon: FileText,
    hint: "Every agreement, grouped by owning ERM",
  },
  {
    id: "people",
    label: "People",
    Icon: Users,
    hint: "Console users, roles and team assignments",
  },
  {
    id: "lifecycle",
    label: "Lifecycle",
    Icon: Route,
    hint: "What each status means and who acts next",
  },
  {
    id: "maintenance",
    label: "Maintenance",
    Icon: Wrench,
    hint: "Destructive operator backfills",
  },
];

const TAB_IDS = new Set<string>(TABS.map((t) => t.id));

function isTabId(value: string | null): value is AdminTabId {
  return value != null && TAB_IDS.has(value);
}

export default function AgreementsAdminPage() {
  // useSearchParams needs a Suspense boundary in the App Router, otherwise the
  // whole route opts out of static rendering at build time.
  return (
    <Suspense fallback={<FullPageSpinner />}>
      <AdminConsole />
    </Suspense>
  );
}

function AdminConsole() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [authChecked, setAuthChecked] = useState(false);

  const tabParam = searchParams.get("tab");
  const tab: AdminTabId = isTabId(tabParam) ? tabParam : "overview";
  // Consumed by whichever tab was navigated to from an Overview tile — a stage
  // key or a status. Cleared as soon as that tab has read it.
  const filter = searchParams.get("filter") ?? undefined;

  // Auth + role gate. The server already enforces 403 on every admin endpoint;
  // this is the UX layer that keeps a non-admin from seeing the chrome at all.
  useEffect(() => {
    if (!getAgreementErmToken()) {
      router.replace("/agreements/login");
      return;
    }
    let cancelled = false;
    fetchMe()
      .then((identity) => {
        if (cancelled) return;
        if (identity.role !== "SUPER_ADMIN") {
          router.replace("/agreements");
          return;
        }
        setAuthChecked(true);
      })
      .catch(() => {
        if (!cancelled) router.replace("/agreements/login");
      });
    return () => {
      cancelled = true;
    };
  }, [router]);

  const navigate = useCallback<NavigateFn>(
    (nextTab, nextFilter) => {
      const params = new URLSearchParams();
      params.set("tab", nextTab);
      if (nextFilter) params.set("filter", nextFilter);
      router.replace(`/agreements/admin?${params.toString()}`, { scroll: false });
    },
    [router],
  );

  if (!authChecked) return <FullPageSpinner />;

  const active = TABS.find((t) => t.id === tab) ?? TABS[0];

  return (
    <AgreementErmShell
      title="Admin console"
      subtitle={active.hint}
      Icon={ShieldCheck}
      toolbar={
        <Link
          href="/agreements"
          className="inline-flex items-center gap-1 text-xs font-semibold text-gray-500 hover:text-sage-navy"
        >
          <ArrowLeft size={12} /> Back to list
        </Link>
      }
    >
      <nav className="mb-5 flex flex-wrap gap-1 rounded-xl border border-gray-200 bg-white p-1 text-xs shadow-sm">
        {TABS.map(({ id, label, Icon }) => {
          const isActive = id === tab;
          return (
            <button
              key={id}
              type="button"
              onClick={() => navigate(id)}
              aria-current={isActive ? "page" : undefined}
              className={
                "inline-flex items-center gap-1.5 px-3 py-2 rounded-lg font-semibold cursor-pointer transition " +
                (isActive
                  ? "bg-sage-navy text-white shadow-sm"
                  : "text-gray-600 hover:bg-gray-50 hover:text-sage-navy")
              }
            >
              <Icon size={13} /> {label}
            </button>
          );
        })}
      </nav>

      {/* Keyed on the filter so a tile click from Overview remounts the target
          tab with its new starting filter rather than silently ignoring it. */}
      {tab === "overview" && <AdminOverviewTab onNavigate={navigate} />}
      {tab === "agreements" && (
        <AdminAgreementsTab key={filter ?? "all"} initialFilter={filter} />
      )}
      {tab === "people" && <AdminPeopleTab key={filter ?? "all"} initialRole={filter} />}
      {tab === "lifecycle" && <AdminLifecycleTab />}
      {tab === "maintenance" && <AdminMaintenanceTab />}
    </AgreementErmShell>
  );
}

function FullPageSpinner() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <Loader2 size={28} className="animate-spin text-sage-navy" />
    </div>
  );
}
