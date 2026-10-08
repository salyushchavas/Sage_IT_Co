"use client";

import { Suspense, useCallback } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import {
  FileText,
  LayoutDashboard,
  Route,
  Wrench,
  type LucideIcon,
} from "lucide-react";

import { Spinner } from "@/components/web-agreement/ui/primitives";
import AdminOverview from "./AdminOverview";
import AdminAgreementsByErm from "./AdminAgreementsByErm";
import AdminLifecycle from "./AdminLifecycle";
import AdminMaintenance from "./AdminMaintenance";

/**
 * /admin → Agreements (System Admin only): the website's copy of the
 * console's super-admin page (src/app/agreements/admin/page.tsx), for the
 * website agreements. The console's "People" tab is the existing Users tab
 * plus the user page's cards, so four sub-tabs remain here.
 *
 * Like the console, this is only the shell: sub-tab routing and nothing
 * else. Each sub-tab loads its own data, so one you are not looking at does
 * not fetch.
 *
 * The sub-tab and its filter live in the URL
 * (?tab=agreements&sub=…&filter=…), so "Open" → /operations → Back returns
 * to the same sub-tab and filter, and a refresh keeps them.
 */

/** Sub-tab ids. Also the `?sub=` URL values. */
export type AdminSubTabId = "overview" | "agreements" | "lifecycle" | "maintenance";

/**
 * Jump to another sub-tab, optionally pre-filtering it. The Agreements
 * sub-tab reads `filter` as a stage key or a status.
 */
export type NavigateFn = (sub: AdminSubTabId, filter?: string) => void;

const SUB_TABS: ReadonlyArray<{
  id: AdminSubTabId;
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

const SUB_TAB_IDS = new Set<string>(SUB_TABS.map((t) => t.id));

function isSubTabId(value: string | null): value is AdminSubTabId {
  return value != null && SUB_TAB_IDS.has(value);
}

export function WebAgreementsAdminPanel({
  onOpenUsers,
}: {
  /** Opens /admin's Users tab (the console's "People"). */
  onOpenUsers: () => void;
}) {
  // useSearchParams needs a Suspense boundary in the App Router.
  return (
    <Suspense
      fallback={
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm">
          <Spinner />
        </div>
      }
    >
      <WebAgreementsAdminPanelInner onOpenUsers={onOpenUsers} />
    </Suspense>
  );
}

export default WebAgreementsAdminPanel;

function WebAgreementsAdminPanelInner({ onOpenUsers }: { onOpenUsers: () => void }) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const subParam = searchParams.get("sub");
  const sub: AdminSubTabId = isSubTabId(subParam) ? subParam : "overview";
  // A stage key or a status, handed over by an Overview tile or kept from
  // the Agreements sub-tab's own chips.
  const filter = searchParams.get("filter") ?? undefined;

  const navigate = useCallback<NavigateFn>(
    (nextSub, nextFilter) => {
      const params = new URLSearchParams();
      params.set("tab", "agreements");
      params.set("sub", nextSub);
      if (nextFilter) params.set("filter", nextFilter);
      router.replace(`${pathname}?${params.toString()}`, { scroll: false });
    },
    [router, pathname],
  );

  const setAgreementsFilter = useCallback(
    (next: string | undefined) => navigate("agreements", next),
    [navigate],
  );

  const active = SUB_TABS.find((t) => t.id === sub) ?? SUB_TABS[0];

  return (
    <div className="min-w-0">
      <div className="mb-5">
        <h1 className="text-2xl font-bold text-zinc-900">Agreements</h1>
        <p className="text-sm text-zinc-500 mt-0.5">{active.hint}</p>
      </div>

      <nav className="mb-5 flex flex-wrap gap-1 rounded-xl border border-gray-200 bg-white p-1 text-xs shadow-sm">
        {SUB_TABS.map(({ id, label, Icon }) => {
          const isActive = id === sub;
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

      {sub === "overview" && (
        <AdminOverview onNavigate={navigate} onOpenUsers={onOpenUsers} />
      )}
      {sub === "agreements" && (
        <AdminAgreementsByErm filter={filter} onFilterChange={setAgreementsFilter} />
      )}
      {sub === "lifecycle" && <AdminLifecycle />}
      {sub === "maintenance" && <AdminMaintenance />}
    </div>
  );
}
