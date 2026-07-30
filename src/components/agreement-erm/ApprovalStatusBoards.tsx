"use client";

import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import Link from "next/link";
import {
  AlertCircle,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  ClipboardCheck,
  Clock,
  RefreshCw,
  Search,
  X,
} from "lucide-react";

import {
  fetchApprovalBoard,
  type AgreementApproval,
  type ApprovalBoardItem,
  type ApprovalDecision,
  type ApproverRole,
  type ConsultantApplication,
  type ConsultantApplicationStatus,
} from "@/lib/api";
import {
  APPROVAL_DECISION_META,
  describeStatus,
  TONE_ACCENT,
  type AgreementStatusMeta,
  type StatusContext,
  type StatusTone,
} from "@/lib/agreement-status";
import { formatUsDateTime } from "@/lib/dates";
import {
  BTN_ROW,
  BTN_SECONDARY,
  Chip,
  EmptyState,
  InlineAlert,
  SectionCard,
  Spinner,
  TableShell,
  TD,
  TH,
} from "@/components/admin-console/primitives";
import AgreementStatusPill from "./AgreementStatusPill";

/**
 * The approval gate on the ERM / super-admin agreements dashboard.
 *
 * This used to be two 50/50 cards, each an unbroken scroll of every agreement
 * in that phase. At 43 rows the Phase 1 card was a wall and the Phase 2 card
 * was an empty half-viewport column, and neither told the super-admin the one
 * thing they open this page for: WHOSE queue is backed up.
 *
 * The shape now is phase → owning ERM → rows:
 *
 *   PHASE 1 · MANAGER GATE ─────────────────────  3 declined · 43 in gate
 *     ▸ Aditi Rao      2 over 14d · 4 need this ERM · 11 agreements
 *     ▸ Rahul Menon                 1 need this ERM ·  8 agreements
 *   PHASE 2 · MANAGER + ACCOUNTS GATES ─────────────────  0 in gate
 *     Nothing in the Phase 2 gate right now.
 *
 * Phase is the container rather than a filter chip because the two phases have
 * genuinely different gate topology (Phase 1 = Manager, Phase 2 = Manager +
 * Accounts), which is also why neither a phase column nor an ERM column is
 * needed on the rows — the band names the phase and the card header names the
 * ERM.
 *
 * Two things this endpoint mixes that the flat list hid: the board returns
 * AWAITING_APPROVALS, APPROVAL_REVISION_REQUESTED and READY_TO_SIGN
 * (ConsultantApplicationService.approvalBoard), so rows waiting on an APPROVER
 * and rows waiting on the ERM sat interleaved. Sorting is by desk, not by date,
 * and every row keeps its own status pill.
 *
 * Wording: every status label comes from src/lib/agreement-status.ts. The only
 * copy hardcoded here is PHASE_META (gate topology, not status) and the gate
 * decision words, which name an approver's decision rather than an agreement
 * state.
 */

const ALL = "all";
const UNASSIGNED = "__none__";
/** Collapse/show-all key for a band that renders one flat table (no grouping). */
const FLAT = "__flat__";
/** Rows rendered per group before the "Show all" footer. */
const ROW_CAP = 10;
/** Amber at a week on one desk, red at a fortnight. Thresholds, not statuses. */
const STALE_DAYS = 7;
const CRITICAL_DAYS = 14;

/**
 * The one piece of hardcoded copy allowed in this file: it names which gates
 * exist in each phase, which is topology (Phase 2 adds Accounts), not a
 * lifecycle status. Everything status-shaped goes through describeStatus.
 */
const PHASE_META: Record<1 | 2, { title: string; empty: string }> = {
  1: {
    title: "PHASE 1 · MANAGER GATE",
    empty: "Nothing in the Phase 1 gate right now.",
  },
  2: {
    title: "PHASE 2 · MANAGER + ACCOUNTS GATES",
    empty: "Nothing in the Phase 2 gate right now.",
  },
};

/**
 * Icon per gate decision, so hue is never the only signal. Label and tone come
 * from the shared APPROVAL_DECISION_META — this map adds only the glyph, which
 * is specific to this board's chip.
 */
const DECISION_ICON: Record<ApprovalDecision, typeof CheckCircle2> = {
  APPROVED: CheckCircle2,
  PENDING: Clock,
  REVISION_REQUESTED: AlertCircle,
};

/** Resolved once so header chips never invent their own wording. */
const DECLINED_META = describeStatus("APPROVAL_REVISION_REQUESTED");
const READY_META = describeStatus("READY_TO_SIGN");

// ── Row model ───────────────────────────────────────────────────

interface Row {
  app: ConsultantApplication;
  context: StatusContext;
  meta: AgreementStatusMeta;
  phase: 1 | 2;
  ermKey: string;
  ermName: string;
  /** Max-round MANAGER gate; null when the agreement was never routed. */
  manager: AgreementApproval | null;
  /** Max-round ACCOUNTS gate. Always null in Phase 1 — that gate doesn't exist. */
  accounts: AgreementApproval | null;
  /** Highest approval round seen. > 1 means it has been re-sent. */
  round: number;
  decline: { role: ApproverRole; name: string | null; note: string } | null;
  /** Epoch ms the row landed on its CURRENT desk. 0 when unresolvable. */
  waitSince: number;
  /** Sentence for the age tooltip — says which clock is being read. */
  waitTitle: string;
  deskRank: 0 | 1 | 2 | 3;
  /** Lowercased search corpus: consultant name, email, appId, ERM name. */
  haystack: string;
}

interface ErmGroup {
  /** `${phase}:${ermKey}` — collapse state is per band, not per ERM. */
  id: string;
  key: string;
  name: string;
  rows: Row[];
}

interface Band {
  phase: 1 | 2;
  rows: Row[];
  /** Empty when the board is not grouping (see canGroup). */
  groups: ErmGroup[];
}

interface DeskChipDef {
  key: string;
  label: string;
  count: number;
  /** Null on "All", which has no status colour of its own. */
  tone: StatusTone | null;
}

interface RowParts {
  name: ReactNode;
  email: ReactNode;
  round: ReactNode;
  note: ReactNode;
  status: ReactNode;
  waitingOn: ReactNode;
  gates: ReactNode;
  wait: ReactNode;
}

// ── Derivation ──────────────────────────────────────────────────

function groupKey(phase: 1 | 2, key: string): string {
  return `${phase}:${key}`;
}

function toTs(iso: string | null | undefined): number {
  if (!iso) return 0;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? 0 : t;
}

function ageDays(ts: number): number {
  if (!ts) return 0;
  return Math.floor((Date.now() - ts) / 86_400_000);
}

function roleWord(role: ApproverRole): string {
  // Role names, not statuses — the status vocabulary has no word for "which
  // human refused", so naming them locally is correct here.
  return role === "ACCOUNTS" ? "Accounts" : "Manager";
}

/**
 * Triage order, not lifecycle order: the two things the ERM can personally act
 * on come first, the ones they can only watch sink.
 *
 * The default arm is a guard. If the status list in
 * ConsultantApplicationService.approvalBoard (~line 2980) is ever widened, the
 * new rows still sort last, still get a desk label from describeStatus and
 * still render their own pill instead of vanishing or mis-sorting.
 */
function deskRank(status: ConsultantApplicationStatus): 0 | 1 | 2 | 3 {
  switch (status) {
    case "APPROVAL_REVISION_REQUESTED":
      return 0;
    case "READY_TO_SIGN":
      return 1;
    case "AWAITING_APPROVALS":
      return 2;
    default:
      return 3;
  }
}

/**
 * How long the row has been on its CURRENT desk.
 *
 * Deliberately NOT application.sentForApprovalAt: that field is @Transient and
 * only ever filled by populateApprovalSummary, which this endpoint does not
 * call — sourcing it would render "0d" on every single row. Everything here
 * comes from the approval gates, which are persisted and always serialized.
 */
function resolveWait(
  app: ConsultantApplication,
  current: AgreementApproval[],
  meta: AgreementStatusMeta,
): { waitSince: number; waitTitle: string } {
  const withApprovers =
    meta.blockedOn === "Manager" || meta.blockedOn === "Manager + Accounts";

  if (current.length > 0 && withApprovers) {
    // Oldest gate still open — the approver who has held it longest.
    const pending = current.filter((a) => a.status === "PENDING");
    const pool = pending.length > 0 ? pending : current;
    const iso = earliest(pool.map((a) => a.createdAt));
    if (iso) {
      return {
        waitSince: toTs(iso),
        waitTitle: `Routed to approvers on ${formatUsDateTime(iso)}`,
      };
    }
  }

  if (current.length > 0 && meta.blockedOn === "ERM") {
    // The last approver decision is the moment it landed back on the ERM.
    const iso = latest(current.map((a) => a.decidedAt));
    if (iso) {
      return {
        waitSince: toTs(iso),
        waitTitle: `With the ERM since ${formatUsDateTime(iso)}`,
      };
    }
  }

  const ts = toTs(app.updatedAt);
  return {
    waitSince: ts,
    waitTitle: ts ? `Last updated ${formatUsDateTime(app.updatedAt)}` : "",
  };
}

function earliest(list: Array<string | null>): string | null {
  let best: string | null = null;
  let bestTs = 0;
  for (const iso of list) {
    const ts = toTs(iso);
    if (!ts) continue;
    if (!best || ts < bestTs) {
      best = iso;
      bestTs = ts;
    }
  }
  return best;
}

function latest(list: Array<string | null>): string | null {
  let best: string | null = null;
  let bestTs = 0;
  for (const iso of list) {
    const ts = toTs(iso);
    if (!ts) continue;
    if (!best || ts > bestTs) {
      best = iso;
      bestTs = ts;
    }
  }
  return best;
}

function decorate(item: ApprovalBoardItem): Row {
  const app = item.application;
  const allApprovals = item.approvals ?? [];
  const phase: 1 | 2 = (app.phase ?? 1) >= 2 ? 2 : 1;

  // Rounds are numbered per AGREEMENT, not per phase: sendForApproval bumps
  // max(round) across every row, and Phase 2 is only reachable after Phase 1 was
  // countersigned — so a Phase 2 agreement's FIRST routing already lands on
  // round 2. Counting globally would stamp "Round 2 · re-sent" on every Phase 2
  // agreement on the board, which is the exact signal an ERM triages on.
  // Scope to this phase's own gates and rank the round within them, so "Round 2"
  // means what it says: this phase was sent back and re-sent.
  const approvals = allApprovals.filter((a) => (a.phase ?? 1) === phase);
  const maxRound = approvals.reduce((m, a) => Math.max(m, a.round), 0);
  // Only the current round matters — earlier rounds are history, and the row
  // would otherwise show a stale "Approved" next to a live "Pending".
  const current = approvals.filter((a) => a.round === maxRound);
  // Rank among this phase's distinct rounds: 1 for the first routing of the
  // phase, 2 for the first re-send, regardless of the raw stored numbers.
  const round = maxRound === 0
    ? 0
    : Array.from(new Set(approvals.map((a) => a.round)))
        .sort((a, b) => a - b)
        .indexOf(maxRound) + 1;

  // phase is the only context this endpoint can honestly supply: linkExpired is
  // transient and absent here, and consultantCopyReleased only refines
  // VERIFIED, which the approval board never returns. phase is the one that
  // matters — it flips AWAITING_APPROVALS' blockedOn to "Manager + Accounts".
  const context: StatusContext = { phase: app.phase };
  const meta = describeStatus(app.status, context);

  const declined = current
    .filter((a) => a.status === "REVISION_REQUESTED")
    .sort((a, b) => toTs(b.decidedAt) - toTs(a.decidedAt))[0];

  const ermName = app.ownerName ?? "Unassigned ERM";
  const { waitSince, waitTitle } = resolveWait(app, current, meta);

  return {
    app,
    context,
    meta,
    phase,
    // Keyed by id, never by name: two ERMs can share a display name and
    // grouping by name would silently merge their queues.
    ermKey: app.ownerErmId ?? UNASSIGNED,
    ermName,
    manager: current.find((a) => a.role === "MANAGER") ?? null,
    accounts: current.find((a) => a.role === "ACCOUNTS") ?? null,
    round,
    decline: declined
      ? {
          role: declined.role,
          name: declined.approverName ?? declined.decidedByName,
          note: (declined.note ?? "").trim(),
        }
      : null,
    waitSince,
    waitTitle,
    deskRank: deskRank(app.status),
    haystack: [app.consultantName, app.consultantEmail, app.applicationId, ermName]
      .filter(Boolean)
      .join(" ")
      .toLowerCase(),
  };
}

/** Desk first, then longest-waiting first. Deliberately not chronological. */
function compareRows(a: Row, b: Row): number {
  if (a.deskRank !== b.deskRank) return a.deskRank - b.deskRank;
  // An unresolvable age sorts last rather than masquerading as the oldest row.
  const aw = a.waitSince || Number.MAX_SAFE_INTEGER;
  const bw = b.waitSince || Number.MAX_SAFE_INTEGER;
  return aw - bw;
}

function buildBand(phase: 1 | 2, rows: Row[], grouped: boolean): Band {
  const mine = rows.filter((r) => r.phase === phase).sort(compareRows);
  if (!grouped) return { phase, rows: mine, groups: [] };

  const map = new Map<string, ErmGroup>();
  for (const r of mine) {
    const existing = map.get(r.ermKey);
    if (existing) existing.rows.push(r);
    else {
      map.set(r.ermKey, {
        id: groupKey(phase, r.ermKey),
        key: r.ermKey,
        name: r.ermName,
        rows: [r],
      });
    }
  }
  // Alphabetical, unassigned last. NOT staleness-ordered: a stable roster is
  // scannable by muscle memory, and the header chips carry the urgency signal
  // without reordering the list under the operator's cursor.
  const groups = Array.from(map.values()).sort((a, b) => {
    if (a.key === UNASSIGNED) return 1;
    if (b.key === UNASSIGNED) return -1;
    return a.name.localeCompare(b.name);
  });
  return { phase, rows: mine, groups };
}

// ── Board ───────────────────────────────────────────────────────

export default function ApprovalStatusBoards() {
  const [items, setItems] = useState<ApprovalBoardItem[]>([]);
  const [loading, setLoading] = useState(true);
  /** The fetch failed. Separate from `error` — a thrown value can be blank. */
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [desk, setDesk] = useState<string>(ALL);
  /**
   * Explicit collapse overrides only (true = collapsed), keyed
   * `${phase}:${ermKey}`. The DEFAULT is derived per band — collapsed when the
   * band holds more than one group — so a plain Set of collapsed keys could
   * not express "the operator closed the only group in this band". In memory
   * only: no localStorage, no URL state.
   */
  const [collapse, setCollapse] = useState<Map<string, boolean>>(new Map());
  /** Groups the operator lifted past the ROW_CAP, same key scheme. */
  const [showAll, setShowAll] = useState<Set<string>>(new Set());

  const uid = useId();

  /**
   * Generation counter so only the newest in-flight request may write state.
   * Two Retry/Refresh clicks can leave two requests racing: without this, a
   * slow FAILING request that resolves after a fast successful one would wipe a
   * board that had already rendered. Also stops any write after unmount, which
   * the auth redirect in /agreements can trigger mid-fetch.
   */
  const reqRef = useRef(0);

  useEffect(() => {
    // Invalidate whatever is in flight when this component goes away.
    return () => {
      reqRef.current += 1;
    };
  }, []);

  const load = useCallback(async () => {
    const gen = ++reqRef.current;
    setLoading(true);
    try {
      const data = await fetchApprovalBoard();
      if (gen !== reqRef.current) return;
      setItems(data);
      setFailed(false);
      setError("");
    } catch (e) {
      if (gen !== reqRef.current) return;
      // The old component swallowed this and returned null, so a failed board
      // vanished and the operator never learned 43 agreements were waiting.
      setFailed(true);
      setError(e instanceof Error ? e.message : "");
      setItems([]);
    } finally {
      if (gen === reqRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const rows = useMemo(() => items.map(decorate), [items]);

  const searched = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return rows;
    // Every term has to land somewhere in the row, so "priya gmail" narrows
    // twice instead of looking for that exact phrase.
    const terms = needle.split(/\s+/);
    return rows.filter((r) => terms.every((t) => r.haystack.includes(t)));
  }, [rows, query]);

  const visible = useMemo(
    () => (desk === ALL ? searched : searched.filter((r) => r.app.status === desk)),
    [searched, desk],
  );

  const deskChips = useMemo<DeskChipDef[]>(() => {
    const counts = new Map<string, number>();
    for (const r of searched) {
      counts.set(r.app.status, (counts.get(r.app.status) ?? 0) + 1);
    }
    const statuses = new Set<string>(rows.map((r) => r.app.status));
    // Keep the active desk even if a reload emptied it, or the filter would
    // disappear and the operator would be stranded on a blank board.
    if (desk !== ALL) statuses.add(desk);

    const out: DeskChipDef[] = [
      { key: ALL, label: "All", count: searched.length, tone: null },
    ];
    for (const s of Array.from(statuses).sort(
      (a, b) =>
        deskRank(a as ConsultantApplicationStatus)
        - deskRank(b as ConsultantApplicationStatus),
    )) {
      const count = counts.get(s) ?? 0;
      if (count === 0 && desk !== s) continue;
      const meta = describeStatus(s);
      out.push({ key: s, label: meta.label, count, tone: meta.tone });
    }
    return out;
  }, [rows, searched, desk]);

  /**
   * Grouping is decided over ALL loaded rows — not the filtered set and not
   * per band — so the page's shape does not flicker as the operator types and
   * both bands stay structurally identical.
   */
  const ownerKeys = useMemo(() => new Set(rows.map((r) => r.ermKey)), [rows]);
  const anyOwnerName = useMemo(
    () => rows.some((r) => r.app.ownerName != null),
    [rows],
  );
  /**
   * Both clauses are load-bearing:
   *
   *  - ownerKeys.size > 1 is the degeneracy guard. The service scopes an ERM to
   *    their own rows, so an ERM always has exactly one key and must never see
   *    an accordion wrapping their own name between them and their queue.
   *  - anyOwnerName is the deploy-lag guard. Frontend and backend ship to
   *    different hosts; until approvalBoard() calls populateOwnerNames every
   *    ownerName is null while ownerErmId is populated, and grouping would
   *    produce a wall of cards all titled "Unassigned ERM" — which reads as
   *    data corruption. This degrades to a clean flat table instead. Silently,
   *    by design: a warning about a missing internal field is noise to an
   *    operator who cannot fix it.
   */
  const canGroup = anyOwnerName && ownerKeys.size > 1;

  /** Flat mode still answers "whose is this?" — in the band header suffix. */
  const soleOwnerName = useMemo(() => {
    if (canGroup || ownerKeys.size !== 1) return null;
    return rows.find((r) => r.app.ownerName)?.app.ownerName ?? null;
  }, [rows, canGroup, ownerKeys]);

  const bands = useMemo<Record<1 | 2, Band>>(
    () => ({
      1: buildBand(1, visible, canGroup),
      2: buildBand(2, visible, canGroup),
    }),
    [visible, canGroup],
  );

  const groupIds = useMemo(
    () => [...bands[1].groups, ...bands[2].groups].map((g) => g.id),
    [bands],
  );

  const collapseStates = useMemo(() => {
    const out: boolean[] = [];
    for (const band of [bands[1], bands[2]]) {
      const fallback = band.groups.length > 1;
      for (const g of band.groups) out.push(collapse.get(g.id) ?? fallback);
    }
    return out;
  }, [bands, collapse]);

  // Drop collapse / show-all state for groups that no longer exist, so a future
  // same-key group isn't rendered in a state nobody chose. Pruned against every
  // LOADED row rather than the filtered ones — otherwise filtering a group out
  // of view would silently forget it was collapsed.
  const liveKeys = useMemo(() => {
    const set = new Set<string>([groupKey(1, FLAT), groupKey(2, FLAT)]);
    for (const r of rows) set.add(groupKey(r.phase, r.ermKey));
    return set;
  }, [rows]);

  useEffect(() => {
    setCollapse((prev) => {
      if (prev.size === 0) return prev;
      const next = new Map<string, boolean>();
      prev.forEach((v, k) => {
        if (liveKeys.has(k)) next.set(k, v);
      });
      return next.size === prev.size ? prev : next;
    });
    setShowAll((prev) => {
      if (prev.size === 0) return prev;
      const next = new Set<string>();
      prev.forEach((k) => {
        if (liveKeys.has(k)) next.add(k);
      });
      return next.size === prev.size ? prev : next;
    });
  }, [liveKeys]);

  const toggleCollapse = useCallback((id: string, collapsed: boolean) => {
    setCollapse((prev) => {
      const next = new Map(prev);
      next.set(id, !collapsed);
      return next;
    });
  }, []);

  const toggleShowAll = useCallback((id: string) => {
    setShowAll((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const filtering = query.trim() !== "" || desk !== ALL;
  const clearFilters = () => {
    setQuery("");
    setDesk(ALL);
  };

  // First load only. A Refresh keeps the board mounted — spinning the whole
  // surface would throw away the operator's search and collapse state on
  // screen and jump the page under them; the button's own spinner is enough.
  if (loading && items.length === 0) {
    // Inside a card so the page doesn't jump when the board resolves.
    return (
      <div className="mb-8 space-y-4">
        <SectionCard>
          <Spinner label="Loading approvals…" />
        </SectionCard>
      </div>
    );
  }

  // Rendered instead of the bands, and never suppressed by the empty-board
  // rule below: "nothing is waiting" and "we could not find out" are opposite
  // facts and must not look the same.
  if (failed) {
    return (
      <div className="mb-8 space-y-4">
        <InlineAlert tone="error">
          <p className="font-semibold">Couldn&apos;t load the approval gate.</p>
          {error && <p className="mt-0.5 text-[12px] opacity-80">{error}</p>}
          <button
            type="button"
            onClick={() => void load()}
            className={`${BTN_ROW} mt-2`}
          >
            <RefreshCw size={11} /> Retry
          </button>
        </InlineAlert>
      </div>
    );
  }

  // A dashboard with nothing to say shouldn't add a card above the list below.
  if (items.length === 0) return null;

  const visibleOwnerKeys = new Set(visible.map((r) => r.ermKey));
  const description = canGroup
    ? `${rows.length} in the approval gate · ${visibleOwnerKeys.size} ERM${
        visibleOwnerKeys.size === 1 ? "" : "s"
      } in view`
    : ownerKeys.size <= 1 && !anyOwnerName
      ? "Your agreements in the approval gate."
      : `${rows.length} in the approval gate`;

  const anyCollapsed = collapseStates.some((c) => c);
  const anyExpanded = collapseStates.some((c) => !c);

  return (
    <div className="mb-8 space-y-4">
      <SectionCard
        title="Approval gate"
        description={description}
        action={
          <button
            type="button"
            onClick={() => void load()}
            disabled={loading}
            className={BTN_SECONDARY}
          >
            <RefreshCw size={12} className={loading ? "animate-spin" : ""} />
            Refresh
          </button>
        }
      >
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative flex-1 min-w-[220px]">
              <Search
                size={13}
                className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400"
              />
              <input
                // Deliberately not type="search" — WebKit paints its own clear
                // button, which would sit next to the one below it.
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search consultant, email, agreement ID or ERM…"
                aria-label="Search the approval gate"
                className="w-full rounded-md border border-gray-200 py-2 pl-8 pr-8 text-sm focus:border-sage-navy focus:outline-none focus:ring-1 focus:ring-sage-navy"
              />
              {query && (
                <button
                  type="button"
                  onClick={() => setQuery("")}
                  aria-label="Clear search"
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-700 cursor-pointer"
                >
                  <X size={13} />
                </button>
              )}
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-1.5">
            {deskChips.map((c) => (
              <FilterChip
                key={c.key}
                def={c}
                active={desk === c.key}
                onSelect={() => setDesk(c.key)}
              />
            ))}
            {canGroup && groupIds.length > 1 && (
              <div className="w-full sm:w-auto sm:ml-auto flex items-center gap-1.5">
                <button
                  type="button"
                  // Merge, never replace: groupIds only holds the groups the
                  // current search leaves visible, so replacing the map would
                  // silently discard overrides for filtered-out groups and undo
                  // them the moment the search is cleared.
                  onClick={() =>
                    setCollapse((prev) => {
                      const next = new Map(prev);
                      for (const id of groupIds) next.set(id, false);
                      return next;
                    })
                  }
                  disabled={!anyCollapsed}
                  className={BTN_ROW}
                >
                  Expand all
                </button>
                <button
                  type="button"
                  onClick={() =>
                    setCollapse((prev) => {
                      const next = new Map(prev);
                      for (const id of groupIds) next.set(id, true);
                      return next;
                    })
                  }
                  disabled={!anyExpanded}
                  className={BTN_ROW}
                >
                  Collapse all
                </button>
              </div>
            )}
          </div>

          {/* Both bands filtered to nothing: the control card and both band
              headers stay mounted, so the way out is always on screen. */}
          {filtering && visible.length === 0 && (
            <div>
              <EmptyState
                Icon={ClipboardCheck}
                title="Nothing matches those filters."
                hint="Widen the search, or pick another desk."
              />
              <div className="flex justify-center">
                <button
                  type="button"
                  onClick={clearFilters}
                  className={BTN_SECONDARY}
                >
                  Clear filters
                </button>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* Phase 1 first, Phase 2 second, both always rendered — the operator has
          to be able to see the board covers both phases and that an empty one
          is genuinely empty rather than missing. */}
      {([1, 2] as const).map((phase) => (
        <PhaseBand
          key={phase}
          band={bands[phase]}
          titleId={`${uid}-phase-${phase}`}
          canGroup={canGroup}
          soleOwnerName={soleOwnerName}
          filtering={filtering}
          collapse={collapse}
          onToggleCollapse={toggleCollapse}
          showAll={showAll}
          onToggleShowAll={toggleShowAll}
        />
      ))}

      {filtering && (
        <p className="px-1 text-[11px] text-gray-400">
          Showing {visible.length} of {rows.length}
        </p>
      )}
    </div>
  );
}

// ── Bands ───────────────────────────────────────────────────────

function PhaseBand({
  band,
  titleId,
  canGroup,
  soleOwnerName,
  filtering,
  collapse,
  onToggleCollapse,
  showAll,
  onToggleShowAll,
}: {
  band: Band;
  titleId: string;
  canGroup: boolean;
  soleOwnerName: string | null;
  filtering: boolean;
  collapse: Map<string, boolean>;
  onToggleCollapse: (id: string, collapsed: boolean) => void;
  showAll: Set<string>;
  onToggleShowAll: (id: string) => void;
}) {
  const { phase } = band;
  const flatId = groupKey(phase, FLAT);
  // One rule, no data-dependent conditionals: collapsed when the band holds
  // more than one group, expanded when it holds exactly one.
  const fallbackCollapsed = band.groups.length > 1;

  return (
    <section aria-labelledby={titleId}>
      <BandHeader
        phase={phase}
        titleId={titleId}
        rows={band.rows}
        suffix={canGroup ? null : soleOwnerName}
      />

      {band.rows.length === 0 ? (
        // A one-line strip rather than a card: it kills the half-viewport dead
        // column without hiding the fact that the phase exists. The two copies
        // differ so a filter artefact is never read as an empty gate.
        <p className="px-1 py-2 text-[12px] text-gray-400 italic">
          {filtering
            ? `No Phase ${phase} agreements match those filters.`
            : PHASE_META[phase].empty}
        </p>
      ) : canGroup ? (
        <div className="space-y-3">
          {band.groups.map((g) => (
            <ErmGroupCard
              key={g.id}
              group={g}
              collapsed={collapse.get(g.id) ?? fallbackCollapsed}
              onToggle={onToggleCollapse}
              expanded={showAll.has(g.id)}
              onToggleExpanded={() => onToggleShowAll(g.id)}
            />
          ))}
        </div>
      ) : (
        <SectionCard padded={false}>
          <RowList
            rows={band.rows}
            expanded={showAll.has(flatId)}
            onToggleExpanded={() => onToggleShowAll(flatId)}
          />
        </SectionCard>
      )}
    </section>
  );
}

/** A bare divider row, not a card — the bands are structure, not content. */
function BandHeader({
  phase,
  titleId,
  rows,
  suffix,
}: {
  phase: 1 | 2;
  titleId: string;
  rows: Row[];
  suffix: string | null;
}) {
  const declined = rows.filter(
    (r) => r.app.status === "APPROVAL_REVISION_REQUESTED",
  ).length;
  const ready = rows.filter((r) => r.app.status === "READY_TO_SIGN").length;

  return (
    <div className="flex items-center gap-2 px-1 mt-1 mb-2">
      <ClipboardCheck size={14} className="text-sage-navy shrink-0" />
      {/* truncate, not nowrap: the title was unshrinkable, so on a 320px
          screen it pushed the trailing "N in gate" count out of a row the page
          cannot scroll sideways (body has overflow-x hidden) and it was simply
          clipped away. The count matters more than the full band title. */}
      <span
        id={titleId}
        className="text-[10px] uppercase tracking-wider font-bold text-gray-500 truncate"
      >
        {PHASE_META[phase].title}
      </span>
      {suffix && (
        <span className="text-[11px] text-gray-400 normal-case truncate">
          · {suffix}
        </span>
      )}
      <span className="flex-1 h-px bg-gray-200" />
      {declined > 0 && (
        <span className="hidden md:inline-flex">
          <Chip tone={DECLINED_META.tone}>
            {declined} {DECLINED_META.label}
          </Chip>
        </span>
      )}
      {ready > 0 && (
        <span className="hidden md:inline-flex">
          <Chip tone={READY_META.tone}>
            {ready} {READY_META.label}
          </Chip>
        </span>
      )}
      <span className="text-[11px] font-semibold text-gray-500 whitespace-nowrap">
        {rows.length} in gate
      </span>
    </div>
  );
}

/**
 * One ERM's queue inside a band. A collapsed card must never hide something
 * actionable — that is what the header chips are for.
 */
function ErmGroupCard({
  group,
  collapsed,
  onToggle,
  expanded,
  onToggleExpanded,
}: {
  group: ErmGroup;
  collapsed: boolean;
  onToggle: (id: string, collapsed: boolean) => void;
  expanded: boolean;
  onToggleExpanded: () => void;
}) {
  const declined = group.rows.filter(
    (r) => r.app.status === "APPROVAL_REVISION_REQUESTED",
  ).length;
  const ready = group.rows.filter((r) => r.app.status === "READY_TO_SIGN").length;
  // The super-admin's actual question: who is sitting on work.
  const needErm = group.rows.filter((r) => r.meta.blockedOn === "ERM").length;
  const overdue = group.rows.filter(
    (r) => ageDays(r.waitSince) >= CRITICAL_DAYS,
  ).length;
  const oldest = group.rows.reduce(
    (m, r) => Math.max(m, ageDays(r.waitSince)),
    0,
  );

  return (
    <SectionCard padded={false}>
      <button
        type="button"
        onClick={() => onToggle(group.id, collapsed)}
        aria-expanded={!collapsed}
        className="w-full block px-4 py-3 bg-sage-navy/5 hover:bg-sage-navy/10 cursor-pointer text-left"
      >
        {/* Name owns its own line so the ERM — the thing this board is
            organized by — is never squeezed to a few characters by the
            metadata beside it. */}
        <span className="flex items-center gap-2 min-w-0">
          {collapsed ? (
            <ChevronRight size={15} className="shrink-0 text-sage-navy" />
          ) : (
            <ChevronDown size={15} className="shrink-0 text-sage-navy" />
          )}
          <span className="truncate font-bold text-sage-navy text-sm">
            {group.name}
          </span>
          <span className="ml-auto shrink-0 text-[11px] font-semibold text-gray-500 whitespace-nowrap">
            {group.rows.length} agreement{group.rows.length === 1 ? "" : "s"}
          </span>
        </span>

        {/* Metadata wraps onto its own line rather than hiding at small
            widths. A collapsed card must never conceal something actionable —
            that is the whole point of these chips, so "Declined" and "Ready"
            are the LAST things to go, not the first. */}
        {(overdue > 0 ||
          declined > 0 ||
          ready > 0 ||
          needErm > 0 ||
          oldest >= STALE_DAYS) && (
          <span className="mt-1.5 flex flex-wrap items-center gap-1.5 pl-[23px]">
            {declined > 0 && (
              <Chip tone={DECLINED_META.tone}>
                {declined} {DECLINED_META.label}
              </Chip>
            )}
            {ready > 0 && (
              <Chip tone={READY_META.tone}>
                {ready} {READY_META.label}
              </Chip>
            )}
            {overdue > 0 && (
              <Chip tone="danger">
                {overdue} over {CRITICAL_DAYS}d
              </Chip>
            )}
            {needErm > 0 && (
              <span className="text-[11px] text-gray-500">
                {needErm} need this ERM
              </span>
            )}
            {oldest >= STALE_DAYS && (
              <span className="text-[11px] text-gray-400 tabular-nums">
                oldest {oldest}d
              </span>
            )}
          </span>
        )}
      </button>

      {!collapsed && (
        <RowList
          rows={group.rows}
          expanded={expanded}
          onToggleExpanded={onToggleExpanded}
        />
      )}
    </SectionCard>
  );
}

// ── Rows ────────────────────────────────────────────────────────

/**
 * One row model, two arrangements. Both branches consume the same Row through
 * useRowParts — they differ only in layout, so a change to what a row SAYS
 * cannot land in one and miss the other.
 */
function RowList({
  rows,
  expanded,
  onToggleExpanded,
}: {
  rows: Row[];
  expanded: boolean;
  onToggleExpanded: () => void;
}) {
  const shown = expanded ? rows : rows.slice(0, ROW_CAP);

  return (
    <>
      <div className="hidden sm:block">
        <TableShell head={<HeadRow />}>
          {shown.map((r) => (
            <GateRow key={r.app.applicationId} row={r} />
          ))}
        </TableShell>
      </div>

      <div className="sm:hidden divide-y divide-gray-100">
        {shown.map((r) => (
          <GateCard key={r.app.applicationId} row={r} />
        ))}
      </div>

      {rows.length > ROW_CAP && (
        <div className="flex justify-center border-t border-gray-100 px-4 py-2">
          <button type="button" onClick={onToggleExpanded} className={BTN_ROW}>
            {expanded ? "Show fewer" : `Show all ${rows.length}`}
          </button>
        </div>
      )}
    </>
  );
}

function HeadRow() {
  return (
    <tr>
      <th className={TH}>Consultant</th>
      <th className={TH}>Status</th>
      <th className={`${TH} hidden lg:table-cell`}>Waiting on</th>
      <th className={TH}>Gates</th>
      <th className={TH}>
        <div className="text-right">In gate</div>
      </th>
      <th className={TH}>
        <span className="sr-only">Actions</span>
      </th>
    </tr>
  );
}

function GateRow({ row }: { row: Row }) {
  const parts = useRowParts(row);
  const href = `/agreements/${row.app.applicationId}`;

  return (
    <tr className="hover:bg-gray-50">
      <td className={TD}>
        {/* A <tr> cannot wrap a <Link> — invalid HTML that React will fight on
            hydration. Only the identity cell links; the row keeps an explicit
            Open button on the right. */}
        <Link href={href} className="group block">
          <span className="flex items-center gap-1.5">
            {parts.name}
            {parts.round}
          </span>
          <span className="block">{parts.email}</span>
        </Link>
        {parts.note}
      </td>
      <td className={TD}>{parts.status}</td>
      <td className={`${TD} hidden lg:table-cell`}>{parts.waitingOn}</td>
      <td className={TD}>
        <div className="flex flex-col gap-1">{parts.gates}</div>
      </td>
      <td className={TD}>
        <div className="text-right">{parts.wait}</div>
      </td>
      <td className={TD}>
        <div className="flex justify-end">
          <Link href={href} className={BTN_SECONDARY}>
            Open
          </Link>
        </div>
      </td>
    </tr>
  );
}

/** Below sm the table becomes stacked blocks; the whole block is the link. */
function GateCard({ row }: { row: Row }) {
  const parts = useRowParts(row);

  return (
    <Link
      href={`/agreements/${row.app.applicationId}`}
      className="group block px-4 py-3 hover:bg-gray-50"
    >
      <div className="flex items-start justify-between gap-3">
        <span className="min-w-0 truncate">{parts.name}</span>
        {parts.wait}
      </div>
      <div className="truncate">{parts.email}</div>
      <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
        {parts.status}
        {parts.round}
      </div>
      <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
        {parts.gates}
      </div>
      {parts.note}
    </Link>
  );
}

function useRowParts(row: Row): RowParts {
  const { app, meta } = row;
  const declineLine = row.decline ? declineText(row.decline) : "";

  return {
    name: (
      <span className="font-medium text-gray-900 group-hover:text-sage-navy">
        {app.consultantName || "—"}
      </span>
    ),
    email: <span className="text-[11px] text-gray-500">{app.consultantEmail}</span>,
    // Invisible on the board until now, and materially important: a round > 1
    // means this agreement has already been round-tripped through approval.
    round:
      row.round > 1 ? (
        <span title="This agreement has been re-sent for approval.">
          <Chip>Round {row.round}</Chip>
        </span>
      ) : null,
    // The whole reason the row is stuck. The board never showed it before, so
    // the ERM had to open the agreement to find out what the approver wanted.
    note: row.decline ? (
      <p
        className="mt-0.5 text-[11px] italic text-gray-500 line-clamp-1"
        title={declineLine}
      >
        {declineLine}
      </p>
    ) : null,
    status: (
      <AgreementStatusPill status={app.status} size="xs" context={row.context} />
    ),
    waitingOn: meta.blockedOn ? (
      <span
        className="text-[11px] font-semibold text-gray-700"
        title={meta.nextAction ?? undefined}
      >
        {meta.blockedOn}
      </span>
    ) : (
      <span className="text-[11px] text-gray-400">—</span>
    ),
    gates: (
      <>
        <ApprovalGateChip
          label="Mgr"
          status={row.manager?.status}
          approverName={row.manager?.approverName ?? row.manager?.decidedByName}
          decidedAt={row.manager?.decidedAt}
        />
        <ApprovalGateChip
          label="Acct"
          status={row.accounts?.status}
          // Phase 1 has no Accounts gate at all — a "—" would read as
          // "not decided yet" when in fact nothing is owed.
          absent={row.phase < 2}
          approverName={row.accounts?.approverName ?? row.accounts?.decidedByName}
          decidedAt={row.accounts?.decidedAt}
        />
      </>
    ),
    wait: <WaitBadge row={row} />,
  };
}

function declineText(decline: NonNullable<Row["decline"]>): string {
  const who = `${roleWord(decline.role)}${decline.name ? ` (${decline.name})` : ""}`;
  return decline.note ? `${who}: ${decline.note}` : `${who} declined without a note.`;
}

/**
 * How long this row has been on its current desk. The NUMBER is the signal;
 * colour is only emphasis, so nobody has to distinguish a hue to read it.
 */
function WaitBadge({ row }: { row: Row }) {
  if (!row.waitSince) {
    return <span className="text-[11px] text-gray-400">—</span>;
  }
  const mins = Math.max(0, Math.round((Date.now() - row.waitSince) / 60_000));
  const hours = Math.floor(mins / 60);
  const days = Math.floor(hours / 24);
  const label =
    mins < 60 ? "just now" : hours < 24 ? `${hours}h` : `${days}d`;
  const cls =
    days >= CRITICAL_DAYS
      ? "text-red-700 font-semibold"
      : days >= STALE_DAYS
        ? "text-amber-700 font-semibold"
        : "text-gray-500";

  return (
    <span
      title={row.waitTitle || undefined}
      className={`text-[11px] tabular-nums whitespace-nowrap ${cls}`}
    >
      {label}
    </span>
  );
}

/**
 * One approval gate's decision. Carries an icon as well as a hue so the three
 * outcomes are never told apart by colour alone.
 */
function ApprovalGateChip({
  label,
  status,
  absent,
  approverName,
  decidedAt,
}: {
  /** "Mgr" / "Acct". Omit to render the chip on its own. */
  label?: string;
  status?: ApprovalDecision | null;
  /** The gate does not exist for this agreement — renders "N/A", not "—". */
  absent?: boolean;
  approverName?: string | null;
  decidedAt?: string | null;
}) {
  const meta = status ? APPROVAL_DECISION_META[status] : undefined;
  const Icon = status ? DECISION_ICON[status] : undefined;
  // Chip has no title prop, so the tooltip lives on a wrapping span.
  const tip =
    approverName && decidedAt
      ? `${approverName} · ${formatUsDateTime(decidedAt)}`
      : undefined;

  return (
    <span className="inline-flex items-center gap-1.5">
      {label && (
        <span className="w-7 shrink-0 text-[10px] uppercase tracking-wider text-gray-400">
          {label}
        </span>
      )}
      {absent ? (
        <span className="text-[11px] text-gray-400">N/A</span>
      ) : meta ? (
        <span title={tip}>
          <Chip tone={meta.tone}>
            {Icon && <Icon size={10} />}
            {meta.label}
          </Chip>
        </span>
      ) : (
        // The gate exists but was never opened — not routed yet.
        <span className="text-[11px] text-gray-400">—</span>
      )}
    </span>
  );
}

/**
 * Desk filter. Not the Chip primitive — that one is a static badge; this is a
 * control, so it carries the tone as a dot and reserves the fill for "active".
 * The count sits inside the button text so it is announced with the label.
 */
function FilterChip({
  def,
  active,
  onSelect,
}: {
  def: DeskChipDef;
  active: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={active}
      className={
        "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11px] font-semibold cursor-pointer transition "
        + (active
          ? "border-sage-navy bg-sage-navy text-white"
          : "border-gray-200 bg-white text-gray-600 hover:border-sage-navy/40 hover:text-sage-navy")
      }
    >
      {def.tone && (
        <span className={`h-1.5 w-1.5 rounded-full ${TONE_ACCENT[def.tone]}`} />
      )}
      {def.label}
      {/* Explicit colours, not opacity: opacity-60 over text-gray-600 composited
          to ~2.9:1 on white, below AA for 11px text — and the count is the
          number the operator actually reads to pick a desk. */}
      <span
        className={
          "tabular-nums " + (active ? "text-white/80" : "text-gray-500")
        }
      >
        {def.count}
      </span>
    </button>
  );
}
