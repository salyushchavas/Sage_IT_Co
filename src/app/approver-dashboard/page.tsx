"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AlertCircle,
  CheckCircle2,
  ClipboardCheck,
  FileText,
  Inbox,
  Loader2,
} from "lucide-react";

import {
  RoleDashboardShell,
  useUrlTab,
  type RoleDashboardTab,
} from "@/components/dashboard/RoleDashboardShell";
import ApprovalCard from "@/components/web-agreement/approver/ApprovalCard";
import ApproverAllAgreements from "@/components/web-agreement/approver/ApproverAllAgreements";
import ApproverApprovedRecord from "@/components/web-agreement/approver/ApproverApprovedRecord";
import { useAuth } from "@/lib/auth-context";
import {
  loginHere,
  webApproverFetchApplications,
  webApproverFetchApproved,
  webApproverFetchQueue,
  type WebAgreement,
  type WebApprovalRole,
  type WebApproverApprovedItem,
  type WebApproverQueueItem,
} from "@/lib/api";
import { approverRoleLabel, canOpenApproverDashboard, homeForRole } from "@/lib/roles";

/**
 * Approver dashboard for the website agreement's two approval gates: the
 * website's copy of the console approvals page
 * (src/app/agreements/approvals/page.tsx) for website MANAGER and ACCOUNTS
 * users. Three tabs:
 *
 *   pending  -- agreements waiting on my gate in the current round: preview
 *               the routed version, then Approve or Request revision
 *   all      -- every agreement ever routed to me, any round and status
 *               (read-only, with a preview of the latest version)
 *   approved -- my approvals, one per agreement, with the downloads
 *
 * Only MANAGER and ACCOUNTS get in; everyone else goes to their own home
 * (an ERM to /erm-dashboard, a System Admin to /admin — the System Admin
 * reaches the approver API with ?role= but has no screen here, like the
 * console's super-admin). The ERM's countersign and the agreement's
 * management stay on the ERM screens; approvers never see them. Nothing
 * here sends an email: this queue is how an approver finds their work.
 */

type TabId = "pending" | "all" | "approved";

const TABS: ReadonlyArray<RoleDashboardTab> = [
  { id: "pending", label: "Pending approval", Icon: Inbox },
  { id: "all", label: "All agreements", Icon: FileText },
  { id: "approved", label: "Approved agreements", Icon: ClipboardCheck },
];
const TAB_IDS = TABS.map((t) => t.id);

export default function ApproverDashboardPage() {
  const router = useRouter();
  const { user, isLoading } = useAuth();
  // The open tab is in the URL (?tab=<id>): a refresh or Back keeps it.
  const [tab, setTab] = useUrlTab<TabId>(TAB_IDS, "pending");
  const [checked, setChecked] = useState(false);
  const [role, setRole] = useState<WebApprovalRole | null>(null);
  const [items, setItems] = useState<WebApproverQueueItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  // Loaded the first time each tab opens, then kept (as in the console).
  const [approved, setApproved] = useState<WebApproverApprovedItem[]>([]);
  const [approvedLoading, setApprovedLoading] = useState(false);
  const [approvedLoaded, setApprovedLoaded] = useState(false);
  const [allApps, setAllApps] = useState<WebAgreement[]>([]);
  const [allLoading, setAllLoading] = useState(false);
  const [allLoaded, setAllLoaded] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setItems(await webApproverFetchQueue());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load your queue.");
    } finally {
      setLoading(false);
    }
  }, []);

  const loadApproved = useCallback(async () => {
    setApprovedLoading(true);
    setError("");
    try {
      setApproved(await webApproverFetchApproved());
      setApprovedLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load your approved record.");
    } finally {
      setApprovedLoading(false);
    }
  }, []);

  const loadAll = useCallback(async () => {
    setAllLoading(true);
    setError("");
    try {
      setAllApps(await webApproverFetchApplications());
      setAllLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load your agreements.");
    } finally {
      setAllLoading(false);
    }
  }, []);

  // Keyed on the role, not the user object, so a profile refresh doesn't
  // reload the queue (which would reset every card's preview gate).
  const signedIn = !!user;
  const userRole = (user?.role ?? "").toUpperCase();
  useEffect(() => {
    if (isLoading) return;
    if (!signedIn) {
      router.replace(loginHere());
      return;
    }
    if (!canOpenApproverDashboard(userRole)) {
      router.replace(homeForRole(userRole));
      return;
    }
    setRole(userRole as WebApprovalRole);
    setChecked(true);
    void load();
  }, [isLoading, signedIn, userRole, router, load]);

  useEffect(() => {
    if (checked && tab === "approved" && !approvedLoaded) {
      void loadApproved();
    }
  }, [checked, tab, approvedLoaded, loadApproved]);

  useEffect(() => {
    if (checked && tab === "all" && !allLoaded) {
      void loadAll();
    }
  }, [checked, tab, allLoaded, loadAll]);

  if (!checked || !role) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <RoleDashboardShell
      title="Approvals"
      tabs={TABS}
      active={tab}
      onSelect={(id) => setTab(id as TabId)}
    >
      <p className="mb-4 text-sm text-gray-500">
        Agreements awaiting your {approverRoleLabel(role)} approval.
      </p>

      {error && (
        <div
          role="alert"
          className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 inline-flex items-start gap-2 text-sm text-red-700"
        >
          <AlertCircle size={14} className="mt-0.5 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {tab === "pending" ? (
        loading ? (
          <div className="py-16 flex items-center justify-center text-gray-400">
            <Loader2 size={22} className="animate-spin" />
          </div>
        ) : items.length === 0 ? (
          <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-10 text-center">
            <CheckCircle2 size={28} className="mx-auto text-emerald-500" />
            <p className="mt-3 text-sm font-semibold text-gray-700">
              Nothing awaiting your approval
            </p>
            <p className="mt-1 text-xs text-gray-500">
              New agreements appear here when an ERM sends them for approval.
            </p>
          </div>
        ) : (
          <div className="space-y-4 max-w-3xl">
            {items.map((item) => (
              <ApprovalCard
                key={item.application.applicationId}
                item={item}
                role={role}
                onDone={load}
              />
            ))}
          </div>
        )
      ) : tab === "all" ? (
        <ApproverAllAgreements loading={allLoading} rows={allApps} />
      ) : (
        <ApproverApprovedRecord
          loading={approvedLoading}
          items={approved}
          // Accounts approvers work across ERMs, so their record is split by
          // the agreement's ERM; a Manager sees one flat list.
          groupByErm={role === "ACCOUNTS"}
        />
      )}
    </RoleDashboardShell>
  );
}
