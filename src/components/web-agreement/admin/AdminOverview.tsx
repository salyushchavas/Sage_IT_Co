"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ArrowRight,
  CheckCircle2,
  Gauge,
  Hourglass,
  Inbox,
  KeyRound,
  ShieldAlert,
  Stamp,
  UserX,
  Users,
  type LucideIcon,
} from "lucide-react";

import { getUsers, type UserDTO, type WebAgreement } from "@/lib/api";
import {
  describeStatus,
  STAGE_META,
  TONE_CLASSES,
  type AgreementStage,
  type StatusTone,
} from "@/lib/web-agreement-status";
import {
  BTN_ROW,
  Chip,
  EmptyState,
  InlineAlert,
  SectionCard,
  Spinner,
  StageMeter,
  StatTile,
  TableShell,
  TD,
  TH,
} from "@/components/web-agreement/ui/primitives";
import { listAllWebAgreements } from "./AdminAgreementsByErm";
import type { NavigateFn } from "./WebAgreementsAdminPanel";

/**
 * Overview sub-tab: what is in flight, whose desk it is sitting on, and
 * what needs a human. The website's copy of the console's AdminOverviewTab
 * (src/components/admin-console/AdminOverviewTab.tsx).
 *
 * Everything is derived in the browser from two calls the System Admin can
 * already make — every website agreement (GET /api/web-agreements, walked
 * 100 at a time) and every user (GET /api/admin/users). The status→stage
 * mapping lives in one place, describeStatus(); this file never spells a
 * status label itself.
 *
 * Differences from the console: there is no access link to lapse, so the
 * "lapsed links" alert and tile hint are gone; the website has no last-login
 * column, so "never signed in" reads the website's "Waiting for first
 * sign-in" flag (mustChangePassword) on the console-equivalent accounts; and
 * the console's People tab is the Users tab here.
 */

// ── Derivation ──────────────────────────────────────────────────

/** Meter + legend order: participant → us → approvers → out the other side. */
const STAGE_ORDER: readonly AgreementStage[] = [
  "with_participant",
  "with_erm",
  "with_approvers",
  "executed",
  "closed",
  "stuck",
];

/**
 * Stages where the agreement is still open work. Stalled rows are in here on
 * purpose: nobody executed or cancelled them, so they are still a live
 * liability even though no button moves them.
 */
const OPEN_STAGES = new Set<AgreementStage>([
  "with_participant",
  "with_erm",
  "with_approvers",
  "stuck",
]);

/** Roles that can own an agreement besides ERM (an admin who created one, or a re-roled ERM). */
const ROLE_LABEL: Record<string, string> = {
  ERM: "ERM",
  MANAGER: "Manager",
  ACCOUNTS: "Accounts",
  OPERATIONS_ADMIN: "Operations admin",
  SYSTEM_ADMIN: "System admin",
};

/**
 * The website equivalents of the console's own users (ERM, Manager, Accounts
 * and the super-admin). The console's user list holds only these, so its
 * "never signed in" alert never names anyone else.
 */
const CONSOLE_EQUIVALENT_ROLES = new Set([
  "ERM",
  "MANAGER",
  "ACCOUNTS",
  "OPERATIONS_ADMIN",
  "SYSTEM_ADMIN",
]);

function roleOf(u: UserDTO): string {
  return (u.role ?? "").toUpperCase();
}

/** Active unless deactivated (null counts as active, as on the server). */
function isActive(u: UserDTO): boolean {
  return u.isActive !== false;
}

interface OwnerRow {
  /** users.id. null is the unassigned bucket, not a person. */
  id: number | null;
  name: string;
  role: string | null;
  active: boolean;
  /** Every agreement they own, at any stage. */
  total: number;
  /** Waiting on THEM — verify, route, action a decline, or countersign. */
  onDesk: number;
  withParticipant: number;
  withApprovers: number;
  /**
   * Phase 1 countersignatures. Includes every agreement now at Phase 2:
   * advancing refuses anything that is not COMPLETED, so reaching Phase 2 IS
   * proof that Phase 1 was executed, whatever the row's status is today.
   */
  executedPhase1: number;
  /** Phase 2 countersignatures — at Phase 2 AND executed right now. */
  executedPhase2: number;
  /** At Phase 2 but not yet re-executed, i.e. a second round in progress. */
  inPhase2: number;
}

interface Overview {
  stageCounts: Record<AgreementStage, number>;
  inFlight: number;
  waitingOnUs: number;
  unowned: number;
  ownerRows: OwnerRow[];
  /** Deactivated accounts that are still the owner of open work. */
  disabledOwners: UserDTO[];
  neverSignedIn: UserDTO[];
}

function blankRow(
  id: number | null,
  name: string,
  role: string | null,
  active: boolean,
): OwnerRow {
  return {
    id,
    name,
    role,
    active,
    total: 0,
    onDesk: 0,
    withParticipant: 0,
    withApprovers: 0,
    executedPhase1: 0,
    executedPhase2: 0,
    inPhase2: 0,
  };
}

function buildOverview(apps: WebAgreement[], users: UserDTO[]): Overview {
  const stageCounts: Record<AgreementStage, number> = {
    with_participant: 0,
    with_erm: 0,
    with_approvers: 0,
    executed: 0,
    closed: 0,
    stuck: 0,
  };

  const byId = new Map(users.map((u) => [u.id, u]));
  const rows = new Map<number | null, OwnerRow>();

  // Seed every active ERM, including those holding nothing. An empty desk is
  // half the answer to "who takes the next agreement", and an ERM with zero
  // rows would otherwise be missing from a table titled "workload".
  for (const u of users) {
    if (roleOf(u) === "ERM" && isActive(u)) {
      rows.set(u.id, blankRow(u.id, u.fullName, roleOf(u), true));
    }
  }

  const liveOwners = new Set<number>();
  let unowned = 0;

  for (const app of apps) {
    // consultantCopyReleased splits VERIFIED into two different situations
    // (signed by the participant vs verified by the ERM). Hand the flags over
    // and let describeStatus decide.
    const meta = describeStatus(app.status, {
      consultantCopyReleased: app.consultantCopyReleased,
      phase: app.phase,
    });
    stageCounts[meta.stage] += 1;

    const ownerId = app.ownerUserId ?? null;
    if (ownerId === null) unowned += 1;
    else if (OPEN_STAGES.has(meta.stage)) liveOwners.add(ownerId);

    let row = rows.get(ownerId);
    if (!row) {
      const owner = ownerId === null ? undefined : byId.get(ownerId);
      row = blankRow(
        ownerId,
        // ownerName rides along on list rows, so an owner missing from the
        // user list still renders a name instead of a bare id.
        owner?.fullName ??
          app.ownerName ??
          (ownerId === null ? "Unassigned" : "Unknown owner"),
        owner ? roleOf(owner) : null,
        // Unknown / unassigned owners are not "disabled" — do not flag them.
        owner ? isActive(owner) : true,
      );
      rows.set(ownerId, row);
    }

    row.total += 1;
    if (meta.stage === "with_erm") row.onDesk += 1;
    else if (meta.stage === "with_participant") row.withParticipant += 1;
    else if (meta.stage === "with_approvers") row.withApprovers += 1;

    // Phase accounting. An agreement carries ONE phase number that moves 1 -> 2,
    // so a naive "executed, split by current phase" would move a finished Phase 1
    // out of the Phase 1 column the moment it was reopened — making an ERM's
    // Phase 1 record shrink as they do more work. Count the milestone, not the
    // current position.
    const atPhase2 = (app.phase ?? 1) >= 2;
    const executedNow = meta.stage === "executed";
    if (atPhase2) {
      row.executedPhase1 += 1; // reaching Phase 2 required a Phase 1 countersign
      if (executedNow) row.executedPhase2 += 1;
      else row.inPhase2 += 1;
    } else if (executedNow) {
      row.executedPhase1 += 1;
    }
  }

  // Array.from, not a spread: the project compiles without downlevelIteration,
  // so spreading a Map iterator does not type-check.
  const assigned = Array.from(rows.values()).filter((r) => r.id !== null);
  assigned.sort(
    (a, b) => b.onDesk - a.onDesk || b.total - a.total || a.name.localeCompare(b.name),
  );
  const unassignedRow = rows.get(null);

  return {
    stageCounts,
    inFlight:
      stageCounts.with_participant +
      stageCounts.with_erm +
      stageCounts.with_approvers +
      stageCounts.stuck,
    waitingOnUs: stageCounts.with_erm + stageCounts.with_approvers,
    unowned,
    // Unassigned is pinned last: it is a defect, not a colleague, and sorting
    // it into the middle of the ranking would read as if it were an ERM.
    ownerRows: unassignedRow ? [...assigned, unassignedRow] : assigned,
    disabledOwners: users.filter((u) => !isActive(u) && liveOwners.has(u.id)),
    // Active accounts only — a disabled user who never signed in is closed
    // business, not a stalled onboarding. "Never signed in" is the website's
    // "Waiting for first sign-in": still on the temporary password.
    neverSignedIn: users.filter(
      (u) =>
        CONSOLE_EQUIVALENT_ROLES.has(roleOf(u)) &&
        isActive(u) &&
        u.mustChangePassword === true,
    ),
  };
}

// ── Needs attention ─────────────────────────────────────────────

interface AttentionItem {
  key: string;
  Icon: LucideIcon;
  tone: StatusTone;
  title: string;
  detail: string;
  actionLabel: string;
  onAction: () => void;
}

function plural(n: number, one: string, many: string): string {
  return n === 1 ? one : many;
}

/** First few names, then a count — long lists must not blow out the row. */
function nameList(people: UserDTO[], max = 3): string {
  const names = people.map((u) => u.fullName);
  if (names.length <= max) return names.join(", ");
  return `${names.slice(0, max).join(", ")} and ${names.length - max} more`;
}

// ── Tab ─────────────────────────────────────────────────────────

interface Loaded {
  users: UserDTO[];
  apps: WebAgreement[];
  /** Server-side total. Equals apps.length unless the page walk was cut short. */
  total: number;
  /** False when the walk hit its ceiling — every count below is then a floor. */
  complete: boolean;
}

export default function AdminOverview({
  onNavigate,
  onOpenUsers,
}: {
  onNavigate: NavigateFn;
  /** /admin's Users tab — the console's People. */
  onOpenUsers: () => void;
}) {
  const [data, setData] = useState<Loaded | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    // In parallel: the cross-reference in "Needs attention" needs both lists
    // together anyway, so there is nothing to gain by sequencing them.
    // Walk every page: a single request is capped at 100 rows server-side, and
    // every number on this tab is a count — a capped page understates them all.
    Promise.all([getUsers("all"), listAllWebAgreements()])
      .then(([userList, all]) => {
        if (cancelled) return;
        setData({
          users: (userList ?? []) as UserDTO[],
          apps: all.rows,
          total: all.total,
          complete: all.complete,
        });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(
          err instanceof Error ? err.message : "Could not load the overview.",
        );
      });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  const apps = data?.apps;
  const users = data?.users;
  const overview = useMemo(
    () => buildOverview(apps ?? [], users ?? []),
    [apps, users],
  );

  const attention = useMemo<AttentionItem[]>(() => {
    const { disabledOwners, stageCounts, unowned, neverSignedIn } = overview;
    const items: AttentionItem[] = [];

    // Ordered worst first. A disabled owner outranks everything else: the work
    // exists, it is assigned, and the assignee physically cannot open it.
    if (disabledOwners.length > 0) {
      const n = disabledOwners.length;
      items.push({
        key: "disabled-owners",
        Icon: ShieldAlert,
        tone: "danger",
        title: `${n} disabled ${plural(n, "account", "accounts")} still ${plural(n, "owns", "own")} open agreements`,
        detail: `${nameList(disabledOwners)} cannot sign in, so nothing they own can move. Reassign the work or re-enable the account.`,
        actionLabel: "Users",
        onAction: onOpenUsers,
      });
    }

    if (stageCounts.stuck > 0) {
      const n = stageCounts.stuck;
      items.push({
        key: "stuck",
        Icon: ShieldAlert,
        tone: STAGE_META.stuck.tone,
        title: `${n} stalled ${plural(n, "agreement", "agreements")}`,
        detail: `${STAGE_META.stuck.blurb} Neither the participant nor the ERM has a button that moves these.`,
        actionLabel: "Agreements",
        onAction: () => onNavigate("agreements", "stuck"),
      });
    }

    if (unowned > 0) {
      const n = unowned;
      items.push({
        key: "unowned",
        Icon: UserX,
        tone: "attention",
        title: `${n} ${plural(n, "agreement has", "agreements have")} no owner`,
        detail:
          "No ERM is accountable for these, so they appear on nobody's dashboard and nobody is chasing them.",
        actionLabel: "Agreements",
        // No filter: the agreements tab groups by owning ERM, so the
        // unassigned group is where these surface.
        onAction: () => onNavigate("agreements"),
      });
    }

    if (neverSignedIn.length > 0) {
      const n = neverSignedIn.length;
      items.push({
        key: "never-signed-in",
        Icon: KeyRound,
        tone: "waiting",
        title: `${n} ${plural(n, "user has", "users have")} never signed in`,
        detail: `${nameList(neverSignedIn)} — their credentials may never have reached them.`,
        actionLabel: "Users",
        onAction: onOpenUsers,
      });
    }

    return items;
  }, [overview, onNavigate, onOpenUsers]);

  if (error) {
    return (
      <InlineAlert tone="error">
        <div className="flex flex-wrap items-center gap-2">
          <span className="flex-1 min-w-0">{error}</span>
          <button type="button" onClick={retry} className={BTN_ROW}>
            Try again
          </button>
        </div>
      </InlineAlert>
    );
  }

  if (!data) {
    return (
      <SectionCard>
        <Spinner label="Reading agreements and users…" />
      </SectionCard>
    );
  }

  const { stageCounts, ownerRows } = overview;
  const loaded = data.apps.length;
  const truncated = !data.complete && loaded < data.total;

  const segments = STAGE_ORDER.filter((stage) => stageCounts[stage] > 0).map(
    (stage) => ({
      key: stage,
      label: STAGE_META[stage].label,
      count: stageCounts[stage],
      tone: STAGE_META[stage].tone,
      onClick: () => onNavigate("agreements", stage),
    }),
  );

  return (
    <div className="space-y-5">
      {truncated && (
        <InlineAlert tone="info">
          Showing <strong>{loaded}</strong> of {data.total} agreements — the
          page stopped paging at its safety ceiling. Every number on this tab
          counts the loaded rows only, so treat them as a floor.
        </InlineAlert>
      )}

      {/* KPI row. Every tile is a count of agreements and every click filters
          the agreements tab, so the row reads as one consistent unit. Two per
          row until there is room for all five beside the admin sidebar. */}
      <div className="grid grid-cols-2 xl:grid-cols-5 gap-3">
        <StatTile
          label="In flight"
          value={overview.inFlight}
          hint={`of ${loaded} ${plural(loaded, "agreement", "agreements")}`}
          // Gray on purpose: this is the denominator, not a desk. The four
          // tiles beside it carry the stage hues.
          tone="neutral"
          Icon={Gauge}
          onClick={() => onNavigate("agreements")}
        />
        <StatTile
          label={STAGE_META.with_participant.label}
          value={stageCounts.with_participant}
          tone={STAGE_META.with_participant.tone}
          Icon={Hourglass}
          onClick={() => onNavigate("agreements", "with_participant")}
        />
        <StatTile
          label="Waiting on us"
          value={overview.waitingOnUs}
          hint={`${stageCounts.with_erm} ERM · ${stageCounts.with_approvers} approvers`}
          tone={STAGE_META.with_erm.tone}
          Icon={Inbox}
          // Spans two stages, so it opens the ERM slice — the half a System
          // Admin can actually chase. The hint names the split and the meter
          // below links to the approver half directly.
          onClick={() => onNavigate("agreements", "with_erm")}
        />
        <StatTile
          label={STAGE_META.executed.label}
          value={stageCounts.executed}
          tone={STAGE_META.executed.tone}
          Icon={Stamp}
          onClick={() => onNavigate("agreements", "executed")}
        />
        <StatTile
          label={STAGE_META.stuck.label}
          value={stageCounts.stuck}
          hint="no path forward"
          tone={STAGE_META.stuck.tone}
          Icon={ShieldAlert}
          onClick={() => onNavigate("agreements", "stuck")}
        />
      </div>

      <SectionCard
        title="Where agreements are sitting"
        description="Grouped by the desk that owes the next move. Pick a stage to open it in Agreements."
      >
        <StageMeter segments={segments} />
      </SectionCard>

      <SectionCard
        title="Needs attention"
        description="Problems this page can prove from the data it already has."
        tone={attention.length > 0 ? "danger" : undefined}
        action={
          attention.length > 0 ? (
            <Chip tone="danger">
              {attention.length} {plural(attention.length, "issue", "issues")}
            </Chip>
          ) : undefined
        }
      >
        {attention.length === 0 ? (
          <EmptyState
            Icon={CheckCircle2}
            title="Nothing needs a human"
            hint="No stalled agreements, no unowned work, no dormant accounts."
          />
        ) : (
          <ul className="-my-2 divide-y divide-gray-100">
            {attention.map((item) => (
              <li key={item.key} className="flex items-start gap-3 py-3">
                <span
                  className={`mt-0.5 grid h-6 w-6 shrink-0 place-items-center rounded-full ${TONE_CLASSES[item.tone]}`}
                >
                  <item.Icon size={12} />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-[13px] font-semibold text-gray-900">
                    {item.title}
                  </p>
                  <p className="mt-0.5 text-[12px] leading-relaxed text-gray-500">
                    {item.detail}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={item.onAction}
                  className={`${BTN_ROW} mt-0.5 shrink-0`}
                >
                  {item.actionLabel}
                  <ArrowRight size={11} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      <SectionCard
        title="Workload by ERM"
        description={
          <>
            Busiest desk first. <strong>Total</strong> is every agreement they
            own, so cancelled and stalled rows sit in it without a column of
            their own. <strong>Phase 1</strong> counts countersignatures, not
            current position — an agreement reopened for Phase 2 stays counted,
            because it could only be reopened after Phase 1 was executed.{" "}
            <strong>P2 open</strong> is a second round still in progress.
          </>
        }
        padded={ownerRows.length === 0}
      >
        {ownerRows.length === 0 ? (
          <EmptyState
            Icon={Users}
            title="No agreements to distribute"
            hint="Nothing is owned by anyone yet."
          />
        ) : (
          <TableShell
            head={
              <>
                {/* Two-row header: open work and executed work are different
                    questions ("who is busy" vs "who has delivered"), so they
                    are banded rather than left as six flat columns. */}
                <tr>
                  <th className={TH} rowSpan={2}>
                    ERM
                  </th>
                  <th className={TH} rowSpan={2}>
                    Total
                  </th>
                  <th
                    className={`${TH} border-l border-gray-200 text-center`}
                    colSpan={3}
                  >
                    Open
                  </th>
                  <th
                    className={`${TH} border-l border-gray-200 text-center`}
                    colSpan={3}
                  >
                    Executed
                  </th>
                </tr>
                <tr>
                  {/* Stage columns take their names from STAGE_META so the
                      table and the meter above never drift apart. */}
                  <th className={`${TH} border-l border-gray-200 whitespace-nowrap`}>
                    {STAGE_META.with_participant.label}
                  </th>
                  <th className={`${TH} whitespace-nowrap`}>Their desk</th>
                  <th className={`${TH} whitespace-nowrap`}>{STAGE_META.with_approvers.label}</th>
                  <th className={`${TH} border-l border-gray-200 whitespace-nowrap`}>Phase 1</th>
                  <th className={`${TH} whitespace-nowrap`}>Phase 2</th>
                  <th className={`${TH} whitespace-nowrap`}>P2 open</th>
                </tr>
              </>
            }
          >
            {ownerRows.map((row) => (
              <tr
                key={row.id ?? "unassigned"}
                className={row.id === null ? "bg-amber-50/40" : undefined}
              >
                <td className={TD}>
                  <div className="flex items-center gap-1.5">
                    <span className="whitespace-nowrap font-semibold text-gray-900">
                      {row.name}
                    </span>
                    {row.id === null && <Chip tone="attention">No owner</Chip>}
                    {!row.active && <Chip tone="danger">Disabled</Chip>}
                    {/* Ownership is not restricted to the ERM role — name the
                        exception rather than let it read as an ERM. */}
                    {row.role !== null && row.role !== "ERM" && (
                      <Chip>{ROLE_LABEL[row.role] ?? row.role}</Chip>
                    )}
                  </div>
                </td>
                <td className={TD}>
                  <Num value={row.total} />
                </td>
                <td className={`${TD} border-l border-gray-100`}>
                  <Num value={row.withParticipant} />
                </td>
                <td className={TD}>
                  <Num value={row.onDesk} />
                </td>
                <td className={TD}>
                  <Num value={row.withApprovers} />
                </td>
                <td className={`${TD} border-l border-gray-100`}>
                  <Num value={row.executedPhase1} />
                </td>
                <td className={TD}>
                  <Num value={row.executedPhase2} />
                </td>
                <td className={TD}>
                  <Num value={row.inPhase2} />
                </td>
              </tr>
            ))}
          </TableShell>
        )}
      </SectionCard>
    </div>
  );
}

/** Table number. Zeros recede so a busy column is visible at a glance. */
function Num({ value }: { value: number }) {
  return (
    <span
      className={
        "tabular-nums " +
        (value > 0 ? "font-semibold text-gray-900" : "text-gray-300")
      }
    >
      {value}
    </span>
  );
}
