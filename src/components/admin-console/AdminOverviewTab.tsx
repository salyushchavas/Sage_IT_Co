"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ArrowRight,
  CheckCircle2,
  Gauge,
  Hourglass,
  Inbox,
  KeyRound,
  Link2Off,
  ShieldAlert,
  Stamp,
  UserX,
  Users,
  type LucideIcon,
} from "lucide-react";

import {
  adminListUsers,
  listAllConsultantApplications,
  type AgreementUserDto,
  type AgreementUserRole,
  type ConsultantApplication,
} from "@/lib/api";
import {
  describeStatus,
  STAGE_META,
  TONE_CLASSES,
  type AgreementStage,
  type StatusTone,
} from "@/lib/agreement-status";
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
} from "@/components/admin-console/primitives";
import type { NavigateFn } from "@/components/admin-console/types";

/**
 * Overview — the console's landing tab.
 *
 * The super-admin used to land on a flat user table, which answered a question
 * nobody was asking. This tab answers the three they actually arrive with:
 * what is in flight, whose desk is it sitting on, and what needs a human.
 *
 * Everything here is derived client-side from two calls the console can
 * already make. There is deliberately no /overview endpoint: a server-side
 * summary would need its own copy of the status→stage mapping, and that
 * mapping lives in exactly one place — describeStatus(). This file never
 * re-implements it and never spells a status label itself.
 *
 * It reads the full ConsultantApplication rows rather than the slim
 * AgreementSummaryDto because only the former carry phase, linkExpired and
 * consultantCopyReleased — the three context flags that turn a raw enum into
 * an honest answer about who is holding things up.
 */

// ── Derivation ──────────────────────────────────────────────────

/** Meter + legend order: consultant → us → approvers → out the other side. */
const STAGE_ORDER: readonly AgreementStage[] = [
  "with_consultant",
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
  "with_consultant",
  "with_erm",
  "with_approvers",
  "stuck",
]);

const ROLE_LABEL: Record<AgreementUserRole, string> = {
  SUPER_ADMIN: "Super admin",
  ERM: "ERM",
  MANAGER: "Manager",
  ACCOUNTS: "Accounts",
};

interface OwnerRow {
  /** AgreementUser id. null is the unassigned bucket, not a person. */
  id: string | null;
  name: string;
  role: AgreementUserRole | null;
  active: boolean;
  /** Every agreement they own, at any stage. */
  total: number;
  /** Waiting on THEM — route, action a decline, or countersign. */
  onDesk: number;
  withConsultant: number;
  withApprovers: number;
  executed: number;
}

interface Overview {
  stageCounts: Record<AgreementStage, number>;
  inFlight: number;
  waitingOnUs: number;
  expiredLinks: number;
  unowned: number;
  ownerRows: OwnerRow[];
  /** Deactivated accounts that are still the owner of open work. */
  disabledOwners: AgreementUserDto[];
  neverSignedIn: AgreementUserDto[];
}

function blankRow(
  id: string | null,
  name: string,
  role: AgreementUserRole | null,
  active: boolean,
): OwnerRow {
  return {
    id,
    name,
    role,
    active,
    total: 0,
    onDesk: 0,
    withConsultant: 0,
    withApprovers: 0,
    executed: 0,
  };
}

function buildOverview(
  apps: ConsultantApplication[],
  users: AgreementUserDto[],
): Overview {
  const stageCounts: Record<AgreementStage, number> = {
    with_consultant: 0,
    with_erm: 0,
    with_approvers: 0,
    executed: 0,
    closed: 0,
    stuck: 0,
  };

  const byId = new Map(users.map((u) => [u.id, u]));
  const rows = new Map<string | null, OwnerRow>();

  // Seed every active ERM, including those holding nothing. An empty desk is
  // half the answer to "who takes the next agreement", and an ERM with zero
  // rows would otherwise be missing from a table titled "workload".
  for (const u of users) {
    if (u.role === "ERM" && u.active) {
      rows.set(u.id, blankRow(u.id, u.fullName, u.role, u.active));
    }
  }

  const liveOwners = new Set<string>();
  let expiredLinks = 0;
  let unowned = 0;

  for (const app of apps) {
    // The status alone is not enough to place a row: consultantCopyReleased
    // splits VERIFIED into two different situations and linkExpired reframes
    // SUBMITTED entirely. Hand the flags over and let describeStatus decide.
    const meta = describeStatus(app.status, {
      consultantCopyReleased: app.consultantCopyReleased,
      phase: app.phase,
      linkExpired: app.linkExpired,
    });
    stageCounts[meta.stage] += 1;

    // Derived server-side and only ever populated while the agreement is
    // awaiting the consultant, so it needs no status guard of its own.
    if (app.linkExpired) expiredLinks += 1;

    const ownerId = app.ownerErmId ?? null;
    if (ownerId === null) unowned += 1;
    else if (OPEN_STAGES.has(meta.stage)) liveOwners.add(ownerId);

    let row = rows.get(ownerId);
    if (!row) {
      const owner = ownerId === null ? undefined : byId.get(ownerId);
      row = blankRow(
        ownerId,
        // ownerName rides along on list rows, so a stamped-but-deleted owner
        // still renders a name instead of a bare id.
        owner?.fullName ??
          app.ownerName ??
          (ownerId === null ? "Unassigned" : "Unknown owner"),
        owner?.role ?? null,
        // Unknown / unassigned owners are not "disabled" — do not flag them.
        owner?.active ?? true,
      );
      rows.set(ownerId, row);
    }

    row.total += 1;
    if (meta.stage === "with_erm") row.onDesk += 1;
    else if (meta.stage === "with_consultant") row.withConsultant += 1;
    else if (meta.stage === "with_approvers") row.withApprovers += 1;
    else if (meta.stage === "executed") row.executed += 1;
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
      stageCounts.with_consultant +
      stageCounts.with_erm +
      stageCounts.with_approvers +
      stageCounts.stuck,
    waitingOnUs: stageCounts.with_erm + stageCounts.with_approvers,
    expiredLinks,
    unowned,
    // Unassigned is pinned last: it is a defect, not a colleague, and sorting
    // it into the middle of the ranking would read as if it were an ERM.
    ownerRows: unassignedRow ? [...assigned, unassignedRow] : assigned,
    disabledOwners: users.filter((u) => !u.active && liveOwners.has(u.id)),
    // Active accounts only — a disabled user who never signed in is closed
    // business, not a stalled onboarding.
    neverSignedIn: users.filter((u) => u.active && u.lastLoginAt == null),
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
function nameList(people: AgreementUserDto[], max = 3): string {
  const names = people.map((u) => u.fullName);
  if (names.length <= max) return names.join(", ");
  return `${names.slice(0, max).join(", ")} and ${names.length - max} more`;
}

// ── Tab ─────────────────────────────────────────────────────────

interface Loaded {
  users: AgreementUserDto[];
  apps: ConsultantApplication[];
  /** Server-side total. Equals apps.length unless the page walk was cut short. */
  total: number;
  /** False when the walk hit its ceiling — every count below is then a floor. */
  complete: boolean;
}

export default function AdminOverviewTab({ onNavigate }: { onNavigate: NavigateFn }) {
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
    Promise.all([adminListUsers(), listAllConsultantApplications()])
      .then(([userList, all]) => {
        if (cancelled) return;
        setData({
          users: userList,
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
    const { disabledOwners, stageCounts, expiredLinks, unowned, neverSignedIn } =
      overview;
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
        actionLabel: "People",
        onAction: () => onNavigate("people"),
      });
    }

    if (stageCounts.stuck > 0) {
      const n = stageCounts.stuck;
      items.push({
        key: "stuck",
        Icon: ShieldAlert,
        tone: STAGE_META.stuck.tone,
        title: `${n} stalled ${plural(n, "agreement", "agreements")}`,
        detail: `${STAGE_META.stuck.blurb} Neither the consultant nor the ERM has a button that moves these.`,
        actionLabel: "Agreements",
        onAction: () => onNavigate("agreements", "stuck"),
      });
    }

    if (expiredLinks > 0) {
      const n = expiredLinks;
      items.push({
        key: "expired-links",
        Icon: Link2Off,
        tone: "attention",
        title: `${n} consultant ${plural(n, "link has", "links have")} lapsed`,
        detail:
          "The 7-day access link expired before the consultant signed. They cannot open the agreement until the owning ERM resends the invitation.",
        actionLabel: "Agreements",
        // A lapsed link only happens on an awaiting agreement, so the status
        // filter lands exactly on the affected set.
        onAction: () => onNavigate("agreements", "SUBMITTED"),
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
        actionLabel: "People",
        onAction: () => onNavigate("people"),
      });
    }

    return items;
  }, [overview, onNavigate]);

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
          console stopped paging at its safety ceiling. Every number on this tab
          counts the loaded rows only, so treat them as a floor.
        </InlineAlert>
      )}

      {/* KPI row. Every tile is a count of agreements and every click filters
          the agreements tab, so the row reads as one consistent unit. */}
      <div className="grid grid-cols-2 lg:grid-cols-5 gap-3">
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
          label={STAGE_META.with_consultant.label}
          value={stageCounts.with_consultant}
          // A lapsed link still counts as "with consultant" by stage, but the
          // consultant cannot act — say so here rather than let the tile lie.
          hint={
            overview.expiredLinks > 0
              ? `${overview.expiredLinks} ${plural(overview.expiredLinks, "link", "links")} lapsed`
              : undefined
          }
          tone={STAGE_META.with_consultant.tone}
          Icon={Hourglass}
          onClick={() => onNavigate("agreements", "with_consultant")}
        />
        <StatTile
          label="Waiting on us"
          value={overview.waitingOnUs}
          hint={`${stageCounts.with_erm} ERM · ${stageCounts.with_approvers} approvers`}
          tone={STAGE_META.with_erm.tone}
          Icon={Inbox}
          // Spans two stages, so it opens the ERM slice — the half a
          // super-admin can actually chase. The hint names the split and the
          // meter below links to the approver half directly.
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
        description="Problems the console can prove from the data it already has."
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
            hint="No lapsed links, no stalled agreements, no unowned work, no dormant accounts."
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
        description="Busiest desk first. Total is every agreement they own; the columns beside it break out the live stages, so cancelled and stalled rows sit in the total without a column of their own."
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
              <tr>
                <th className={TH}>ERM</th>
                <th className={TH}>Total</th>
                <th className={TH}>Their desk</th>
                {/* Stage columns take their names from STAGE_META so the
                    table and the meter above never drift apart. */}
                <th className={TH}>{STAGE_META.with_consultant.label}</th>
                <th className={TH}>{STAGE_META.with_approvers.label}</th>
                <th className={TH}>{STAGE_META.executed.label}</th>
              </tr>
            }
          >
            {ownerRows.map((row) => (
              <tr
                key={row.id ?? "unassigned"}
                className={row.id === null ? "bg-amber-50/40" : undefined}
              >
                <td className={TD}>
                  <div className="flex items-center gap-1.5">
                    <span className="truncate font-semibold text-gray-900">
                      {row.name}
                    </span>
                    {row.id === null && <Chip tone="attention">No owner</Chip>}
                    {!row.active && <Chip tone="danger">Disabled</Chip>}
                    {/* Ownership is not restricted to the ERM role — name the
                        exception rather than let it read as an ERM. */}
                    {row.role !== null && row.role !== "ERM" && (
                      <Chip>{ROLE_LABEL[row.role]}</Chip>
                    )}
                  </div>
                </td>
                <td className={TD}>
                  <Num value={row.total} />
                </td>
                <td className={TD}>
                  <Num value={row.onDesk} />
                </td>
                <td className={TD}>
                  <Num value={row.withConsultant} />
                </td>
                <td className={TD}>
                  <Num value={row.withApprovers} />
                </td>
                <td className={TD}>
                  <Num value={row.executed} />
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
