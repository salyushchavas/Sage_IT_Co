"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  ChevronDown,
  ChevronRight,
  FileText,
  Loader2,
  RefreshCw,
  Search,
  Trash2,
  Users,
  X,
} from "lucide-react";

import {
  adminDeleteApplication,
  listAllConsultantApplications,
  type ConsultantApplication,
} from "@/lib/api";
import {
  describeStatus,
  STAGE_META,
  TONE_ACCENT,
  type AgreementStage,
  type AgreementStatusMeta,
  type StatusContext,
  type StatusTone,
} from "@/lib/agreement-status";
import { formatUsDate, formatUsDateTime } from "@/lib/dates";
import AgreementStatusPill from "@/components/agreement-erm/AgreementStatusPill";
import {
  BTN_DANGER,
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
} from "./primitives";

/**
 * Super-admin agreements tab — every agreement in the system, grouped under
 * its owning ERM.
 *
 * Ported from AgreementsByErmView, which listed name / status / created date
 * and nothing else. That was a directory, not a triage surface: it could tell
 * you a row was "Verified" but not that it had been sitting there for three
 * weeks because nobody released the consultant copy. This keeps that view's
 * spine (collapsible per-ERM cards, the delete flow) and adds the three things
 * an operator actually scans for — whose desk the row is on, which approval
 * gates are outstanding, and how long since anything happened.
 *
 * Data comes from listAllConsultantApplications rather than adminListAgreements:
 * the super-admin gets every row from both, but only the former carries
 * phase / linkExpired / consultantCopyReleased / manager+accounts gate state,
 * and those are exactly what the status context and the approvals column need.
 * That endpoint pages at 100 rows, so the helper walks every page — a single
 * request would have quietly hidden older agreements from the grouping, the
 * stage counts and the delete action this tab exclusively owns.
 */

const ALL = "all";
const UNASSIGNED = "__none__";

/** Triage order, not lifecycle order — what is on fire goes first. */
const STAGE_ORDER: readonly AgreementStage[] = [
  "stuck",
  "with_consultant",
  "with_erm",
  "with_approvers",
  "executed",
  "closed",
];

function isStage(value: string): value is AgreementStage {
  return (STAGE_ORDER as readonly string[]).includes(value);
}

/**
 * Gate decisions as they read in the console's post-rewrite vocabulary.
 * REVISION_REQUESTED is "Declined" here for the same reason
 * APPROVAL_REVISION_REQUESTED is "Declined by approver" in agreement-status:
 * the approver refused their gate, and calling that "Revision" implied the
 * consultant had been sent something to fix, which never happens.
 */
const APPROVAL_META: Record<string, { label: string; tone: StatusTone }> = {
  APPROVED: { label: "Approved", tone: "done" },
  PENDING: { label: "Pending", tone: "waiting" },
  REVISION_REQUESTED: { label: "Declined", tone: "danger" },
};

const CONFIRM_DELETE =
  "Delete this agreement?\n\n"
  + "This removes it from all dashboards -- ERM and consultant. "
  + "The row is retained internally for audit; recovery is DB-level only.";

/**
 * A row with everything derived from it resolved once, so the filter, the
 * grouping and the cells can never disagree about what a row's stage is.
 */
interface Row {
  app: ConsultantApplication;
  meta: AgreementStatusMeta;
  context: StatusContext;
  ermKey: string;
  ermName: string;
  /** Lowercased search corpus: consultant name, email, appId, ERM name. */
  haystack: string;
  /** Epoch ms of updatedAt, 0 when unparseable — sort key. */
  updatedTs: number;
}

interface ErmGroup {
  key: string;
  name: string;
  rows: Row[];
}

interface FilterChipDef {
  key: string;
  label: string;
  count: number;
  /** Null on "All", which has no stage colour of its own. */
  tone: StatusTone | null;
}

/** "1" and "2" rather than numbers so the segmented control keys stay strings. */
type PhaseFilter = "ALL" | "1" | "2";

const PHASE_OPTIONS: ReadonlyArray<{ key: PhaseFilter; label: string }> = [
  { key: "ALL", label: "Both phases" },
  { key: "1", label: "Phase 1" },
  { key: "2", label: "Phase 2" },
];

/** Phase split for a set of rows. A null phase is a pre-column Phase 1 row. */
function splitByPhase(rows: Row[]): { p1: number; p2: number } {
  let p1 = 0;
  let p2 = 0;
  for (const r of rows) {
    if ((r.app.phase ?? 1) >= 2) p2 += 1;
    else p1 += 1;
  }
  return { p1, p2 };
}

export default function AdminAgreementsTab({
  initialFilter,
}: {
  initialFilter?: string;
}) {
  const [rows, setRows] = useState<ConsultantApplication[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  // False only if the page walk hit its ceiling — then the counts below are a
  // floor, not a total, and the banner has to say so.
  const [complete, setComplete] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");
  const [deletingId, setDeletingId] = useState<string | null>(null);

  // A stage key, a raw status, or ALL. The tab is remounted on a new filter by
  // the console shell, so seeding state from the prop is enough.
  const [filter, setFilter] = useState<string>(initialFilter ?? ALL);
  // Phase is orthogonal to stage — "Phase 2, in approval" is a real question —
  // so it gets its own control rather than more chips in the stage row.
  const [phaseFilter, setPhaseFilter] = useState<PhaseFilter>("ALL");
  const [query, setQuery] = useState("");
  const [grouped, setGrouped] = useState(true);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  const load = useCallback(async () => {
    setLoading(true);
    try {
      // Walk every page, not just the first. The server clamps a page at 100
      // rows, and this tab groups by ERM, computes stage counts and owns the
      // only delete action — a single capped page silently hid older
      // agreements from all three.
      const all = await listAllConsultantApplications();
      setRows(all.rows);
      setTotalElements(all.total);
      setComplete(all.complete);
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load agreements.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Auto-clear the success banner so it doesn't linger across actions
  // (mirrors ConsultantDetailView).
  useEffect(() => {
    if (!feedback) return;
    const t = setTimeout(() => setFeedback(""), 8_000);
    return () => clearTimeout(t);
  }, [feedback]);

  const decorated = useMemo<Row[]>(
    () =>
      rows.map((app) => {
        // Everything the status vocabulary needs to sharpen a bare enum:
        // phase opens the Accounts gate, consultantCopyReleased splits
        // VERIFIED in two, linkExpired outranks "Awaiting consultant".
        const context: StatusContext = {
          phase: app.phase,
          consultantCopyReleased: app.consultantCopyReleased,
          linkExpired: app.linkExpired,
        };
        const ermName = app.ownerName ?? "Unassigned";
        const ts = Date.parse(app.updatedAt);
        return {
          app,
          context,
          meta: describeStatus(app.status, context),
          ermKey: app.ownerErmId ?? UNASSIGNED,
          ermName,
          haystack: [
            app.consultantName,
            app.consultantEmail,
            app.applicationId,
            ermName,
          ]
            .filter(Boolean)
            .join(" ")
            .toLowerCase(),
          updatedTs: Number.isNaN(ts) ? 0 : ts,
        };
      }),
    [rows],
  );

  const searched = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return decorated;
    // Every term has to land somewhere in the row, so "priya gmail" narrows
    // twice instead of looking for that exact phrase.
    const terms = needle.split(/\s+/);
    return decorated.filter((r) => terms.every((t) => r.haystack.includes(t)));
  }, [decorated, query]);

  /** Rows matching the search AND the phase selection. Feeds everything below. */
  const scoped = useMemo(() => {
    if (phaseFilter === "ALL") return searched;
    const want = phaseFilter === "1" ? 1 : 2;
    // A null phase is a Phase 1 row that predates the column.
    return searched.filter((r) => (r.app.phase ?? 1) === want);
  }, [searched, phaseFilter]);

  /** Phase counts come from the SEARCHED set, not the phase-scoped one —
   *  otherwise picking Phase 2 would show "Phase 1 (0)" and strand the
   *  operator with no way to read what they had just left. */
  const phaseCounts = useMemo(() => {
    let p1 = 0;
    let p2 = 0;
    for (const r of searched) {
      if ((r.app.phase ?? 1) >= 2) p2 += 1;
      else p1 += 1;
    }
    return { all: searched.length, p1, p2 };
  }, [searched]);

  const chips = useMemo<FilterChipDef[]>(() => {
    const counts = new Map<AgreementStage, number>();
    for (const r of scoped) {
      counts.set(r.meta.stage, (counts.get(r.meta.stage) ?? 0) + 1);
    }

    const out: FilterChipDef[] = [
      { key: ALL, label: "All", count: scoped.length, tone: null },
    ];
    for (const stage of STAGE_ORDER) {
      const count = counts.get(stage) ?? 0;
      // Only stages that exist get a chip — but never drop the active one, or
      // the filter would vanish from the UI the moment a search emptied it and
      // the operator would be staring at a blank list with no way back.
      if (count === 0 && filter !== stage) continue;
      out.push({
        key: stage,
        label: STAGE_META[stage].label,
        count,
        tone: STAGE_META[stage].tone,
      });
    }

    // An Overview tile can hand us a raw status instead of a stage (e.g. the
    // "Ready to countersign" tile). Give it its own chip so what is applied is
    // visible and clearable.
    if (filter !== ALL && !isStage(filter)) {
      const meta = describeStatus(filter);
      out.push({
        key: filter,
        label: meta.label,
        count: scoped.filter((r) => r.app.status === filter).length,
        tone: meta.tone,
      });
    }
    return out;
  }, [scoped, filter]);

  const visible = useMemo(() => {
    if (filter === ALL) return scoped;
    if (isStage(filter)) return scoped.filter((r) => r.meta.stage === filter);
    return scoped.filter((r) => r.app.status === filter);
  }, [scoped, filter]);

  const groups = useMemo<ErmGroup[]>(() => {
    const map = new Map<string, ErmGroup>();
    for (const r of visible) {
      const existing = map.get(r.ermKey);
      if (existing) existing.rows.push(r);
      else map.set(r.ermKey, { key: r.ermKey, name: r.ermName, rows: [r] });
    }
    const list = Array.from(map.values());
    // Most recently touched first inside every group — the top of each card is
    // where the day's activity is.
    for (const g of list) g.rows.sort((a, b) => b.updatedTs - a.updatedTs);
    // Alphabetical by ERM name; "Unassigned" last.
    return list.sort((a, b) => {
      if (a.key === UNASSIGNED) return 1;
      if (b.key === UNASSIGNED) return -1;
      return a.name.localeCompare(b.name);
    });
  }, [visible]);

  const flat = useMemo(
    () => [...visible].sort((a, b) => b.updatedTs - a.updatedTs),
    [visible],
  );

  // Drop collapse state for ERMs that no longer exist (e.g. their last row was
  // deleted) so a future same-key group isn't unexpectedly rendered collapsed.
  // Pruned against every LOADED row rather than the filtered ones — otherwise
  // filtering a group out of view would silently forget it was collapsed.
  const liveErmKeys = useMemo(
    () => new Set(decorated.map((r) => r.ermKey)),
    [decorated],
  );
  useEffect(() => {
    setCollapsed((prev) => {
      if (prev.size === 0) return prev;
      let changed = false;
      const next = new Set<string>();
      prev.forEach((k) => {
        if (liveErmKeys.has(k)) next.add(k);
        else changed = true;
      });
      return changed ? next : prev;
    });
  }, [liveErmKeys]);

  const toggle = (key: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const handleDelete = async (appId: string) => {
    if (!confirm(CONFIRM_DELETE)) return;
    setDeletingId(appId);
    setError("");
    try {
      await adminDeleteApplication(appId);
      // Drop it locally instead of refetching — a reload would also reset the
      // operator's filter, search and collapse state mid-triage.
      setRows((prev) => prev.filter((r) => r.applicationId !== appId));
      setTotalElements((prev) => Math.max(0, prev - 1));
      setFeedback("Agreement deleted.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't delete agreement.");
    } finally {
      setDeletingId(null);
    }
  };

  const truncated = !complete && totalElements > rows.length;
  const allCollapsed =
    groups.length > 0 && groups.every((g) => collapsed.has(g.key));
  const filtering =
    filter !== ALL || query.trim() !== "" || phaseFilter !== "ALL";

  return (
    <div className="space-y-4">
      <SectionCard
        title="Agreements"
        description={
          <span className="inline-flex items-center gap-1.5">
            <Users size={12} className="text-sage-navy" />
            {loading
              ? "Loading…"
              : `${visible.length} of ${rows.length} loaded · ${groups.length} ERM${groups.length === 1 ? "" : "s"} in view`}
          </span>
        }
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
                aria-label="Search agreements"
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

            {/* Phase sits beside the view toggle rather than in the stage chip
                row below: the two are independent, and mixing them would imply
                an agreement is EITHER "Phase 2" OR "In approval". */}
            <div className="inline-flex rounded-lg border border-gray-200 bg-white p-0.5">
              {PHASE_OPTIONS.map((opt) => {
                const count =
                  opt.key === "ALL"
                    ? phaseCounts.all
                    : opt.key === "1"
                      ? phaseCounts.p1
                      : phaseCounts.p2;
                return (
                  <button
                    key={opt.key}
                    type="button"
                    onClick={() => setPhaseFilter(opt.key)}
                    aria-pressed={phaseFilter === opt.key}
                    className={
                      "inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold cursor-pointer transition " +
                      (phaseFilter === opt.key
                        ? "bg-sage-navy text-white"
                        : "text-gray-600 hover:text-sage-navy")
                    }
                  >
                    {opt.label}
                    <span
                      className={
                        "tabular-nums text-[10px] " +
                        (phaseFilter === opt.key ? "text-white/70" : "text-gray-400")
                      }
                    >
                      {count}
                    </span>
                  </button>
                );
              })}
            </div>

            {/* Grouping is a view preference, not a filter — both modes show
                exactly the same rows. */}
            <div className="inline-flex rounded-lg border border-gray-200 bg-white p-0.5">
              {[
                { key: true, label: "Group by ERM" },
                { key: false, label: "Flat list" },
              ].map((opt) => (
                <button
                  key={String(opt.key)}
                  type="button"
                  onClick={() => setGrouped(opt.key)}
                  aria-pressed={grouped === opt.key}
                  className={
                    "px-2.5 py-1.5 rounded-md text-[11px] font-semibold cursor-pointer transition " +
                    (grouped === opt.key
                      ? "bg-sage-navy text-white"
                      : "text-gray-600 hover:text-sage-navy")
                  }
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-1.5">
            {chips.map((c) => (
              <FilterChip
                key={c.key}
                def={c}
                active={filter === c.key}
                onSelect={() => setFilter(c.key)}
              />
            ))}
            {grouped && groups.length > 1 && (
              <div className="ml-auto flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => setCollapsed(new Set())}
                  disabled={collapsed.size === 0}
                  className={BTN_ROW}
                >
                  Expand all
                </button>
                <button
                  type="button"
                  onClick={() => setCollapsed(new Set(groups.map((g) => g.key)))}
                  disabled={allCollapsed}
                  className={BTN_ROW}
                >
                  Collapse all
                </button>
              </div>
            )}
          </div>
        </div>
      </SectionCard>

      {truncated && (
        <InlineAlert tone="info">
          Showing {rows.length} of <strong>{totalElements}</strong> agreements —
          the console stopped paging at its safety ceiling. Counts and filters
          below describe the loaded rows only.
        </InlineAlert>
      )}
      {error && (
        <InlineAlert tone="error" onDismiss={() => setError("")}>
          {error}
        </InlineAlert>
      )}
      {feedback && (
        <InlineAlert tone="success" onDismiss={() => setFeedback("")}>
          {feedback}
        </InlineAlert>
      )}

      {loading ? (
        <SectionCard>
          <Spinner label="Loading agreements…" />
        </SectionCard>
      ) : visible.length === 0 ? (
        <SectionCard>
          <EmptyState
            Icon={FileText}
            title={filtering ? "Nothing matches those filters." : "No agreements yet."}
            hint={
              filtering
                ? "Widen the search, or pick another stage or phase."
                : "Agreements appear here as soon as an ERM creates one."
            }
          />
          {filtering && (
            <div className="flex justify-center">
              <button
                type="button"
                onClick={() => {
                  setFilter(ALL);
                  setQuery("");
                  setPhaseFilter("ALL");
                }}
                className={BTN_SECONDARY}
              >
                Clear filters
              </button>
            </div>
          )}
        </SectionCard>
      ) : grouped ? (
        groups.map((g) => {
          const isCollapsed = collapsed.has(g.key);
          const stuckCount = g.rows.filter((r) => r.meta.stage === "stuck").length;
          // Only worth showing when the group actually spans both phases —
          // with a phase filter applied it would just restate the filter.
          const phases = splitByPhase(g.rows);
          const showPhaseSplit =
            phaseFilter === "ALL" && phases.p1 > 0 && phases.p2 > 0;
          return (
            <SectionCard key={g.key} padded={false}>
              <button
                type="button"
                onClick={() => toggle(g.key)}
                aria-expanded={!isCollapsed}
                className="w-full flex items-center justify-between gap-3 px-4 py-3 bg-sage-navy/5 hover:bg-sage-navy/10 cursor-pointer"
              >
                <span className="inline-flex items-center gap-2 font-bold text-sage-navy text-sm">
                  {isCollapsed ? (
                    <ChevronRight size={15} />
                  ) : (
                    <ChevronDown size={15} />
                  )}
                  {g.name}
                </span>
                <span className="inline-flex items-center gap-2">
                  {/* Surfaced on the header so a collapsed card can't hide a
                      stalled agreement. */}
                  {stuckCount > 0 && (
                    <Chip tone="danger">{stuckCount} needs attention</Chip>
                  )}
                  {showPhaseSplit && (
                    <span className="hidden sm:inline-flex items-center gap-1.5">
                      <Chip>P1 {phases.p1}</Chip>
                      <Chip tone="review">P2 {phases.p2}</Chip>
                    </span>
                  )}
                  <span className="text-[11px] font-semibold text-gray-500">
                    {g.rows.length} agreement{g.rows.length === 1 ? "" : "s"}
                  </span>
                </span>
              </button>

              {!isCollapsed && (
                <TableShell head={<HeadRow showErm={false} />}>
                  {g.rows.map((r) => (
                    <AgreementRow
                      key={r.app.applicationId}
                      row={r}
                      showErm={false}
                      deleting={deletingId === r.app.applicationId}
                      onDelete={handleDelete}
                    />
                  ))}
                </TableShell>
              )}
            </SectionCard>
          );
        })
      ) : (
        <SectionCard padded={false}>
          <TableShell head={<HeadRow showErm />}>
            {flat.map((r) => (
              <AgreementRow
                key={r.app.applicationId}
                row={r}
                showErm
                deleting={deletingId === r.app.applicationId}
                onDelete={handleDelete}
              />
            ))}
          </TableShell>
        </SectionCard>
      )}
    </div>
  );
}

/**
 * Stage filter. Not the Chip primitive — that one is a static badge; this is a
 * control, so it carries the tone as a dot and reserves the fill for "active".
 */
function FilterChip({
  def,
  active,
  onSelect,
}: {
  def: FilterChipDef;
  active: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={active}
      className={
        "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11px] font-semibold cursor-pointer transition " +
        (active
          ? "border-sage-navy bg-sage-navy text-white"
          : "border-gray-200 bg-white text-gray-600 hover:border-sage-navy/40 hover:text-sage-navy")
      }
    >
      {def.tone && (
        <span className={`h-1.5 w-1.5 rounded-full ${TONE_ACCENT[def.tone]}`} />
      )}
      {def.label}
      <span className="tabular-nums opacity-60">{def.count}</span>
    </button>
  );
}

/**
 * The ERM column only appears in flat mode — grouped mode already answers
 * "whose is this?" in the card header.
 */
function HeadRow({ showErm }: { showErm: boolean }) {
  return (
    <tr>
      <th className={TH}>Consultant</th>
      {showErm && <th className={TH}>ERM</th>}
      <th className={TH}>Status</th>
      <th className={TH}>Waiting on</th>
      <th className={TH}>Phase</th>
      <th className={TH}>Approvals</th>
      <th className={TH}>Updated</th>
      <th className={TH}>
        <div className="text-right">Actions</div>
      </th>
    </tr>
  );
}

function AgreementRow({
  row,
  showErm,
  deleting,
  onDelete,
}: {
  row: Row;
  showErm: boolean;
  deleting: boolean;
  onDelete: (appId: string) => void;
}) {
  const { app, meta, context } = row;
  const phase = app.phase ?? 1;
  const href = `/agreements/${app.applicationId}`;

  return (
    <tr
      className={
        // A stalled row is one no console button can move; tinting the whole
        // row is the only thing that survives a long scroll.
        meta.stage === "stuck"
          ? "bg-red-50/70 hover:bg-red-50"
          : "hover:bg-gray-50"
      }
    >
      <td className={TD}>
        <Link href={href} className="group block">
          <div className="font-medium text-gray-900 group-hover:text-sage-navy">
            {app.consultantName || "—"}
          </div>
          <div className="text-[11px] text-gray-500">{app.consultantEmail}</div>
        </Link>
      </td>

      {showErm && (
        <td className={TD}>
          <span className="text-[12px] text-gray-600">{row.ermName}</span>
        </td>
      )}

      <td className={TD}>
        <AgreementStatusPill status={app.status} context={context} />
      </td>

      <td className={TD}>
        {meta.blockedOn ? (
          <span
            className="text-[11px] font-semibold text-gray-700"
            title={meta.nextAction ?? undefined}
          >
            {meta.blockedOn}
          </span>
        ) : (
          <span className="text-[11px] text-gray-400">—</span>
        )}
      </td>

      <td className={TD}>
        <Chip tone={phase >= 2 ? "review" : "neutral"}>Phase {phase}</Chip>
      </td>

      <td className={TD}>
        <div className="flex flex-col gap-1">
          <ApprovalGate label="Mgr" status={app.managerStatus} />
          {/* Phase 1 has no Accounts gate at all. A "—" here would read as
              "not decided yet" when in fact nothing is owed. */}
          <ApprovalGate
            label="Acct"
            status={app.accountsStatus}
            absent={phase < 2}
          />
        </div>
      </td>

      <td className={TD}>
        <div
          className="whitespace-nowrap text-[12px] text-gray-600"
          title={formatUsDateTime(app.updatedAt)}
        >
          {formatUsDate(app.updatedAt) || "—"}
        </div>
        <div className="text-[10px] text-gray-400">{ageLabel(row.updatedTs)}</div>
      </td>

      <td className={TD}>
        <div className="flex items-center justify-end gap-2">
          <Link href={href} className={BTN_SECONDARY}>
            Open
          </Link>
          <button
            type="button"
            onClick={() => onDelete(app.applicationId)}
            disabled={deleting}
            className={BTN_DANGER}
          >
            {deleting ? (
              <Loader2 size={12} className="animate-spin" />
            ) : (
              <Trash2 size={12} />
            )}
            Delete
          </button>
        </div>
      </td>
    </tr>
  );
}

function ApprovalGate({
  label,
  status,
  absent,
}: {
  label: string;
  status: string | null | undefined;
  /** The gate does not exist for this agreement — renders "N/A", not "—". */
  absent?: boolean;
}) {
  const meta = status ? APPROVAL_META[status] : undefined;
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className="w-7 shrink-0 text-[10px] uppercase tracking-wider text-gray-400">
        {label}
      </span>
      {absent ? (
        <span className="text-[11px] text-gray-400">N/A</span>
      ) : meta ? (
        <Chip tone={meta.tone}>{meta.label}</Chip>
      ) : status ? (
        <Chip>{status}</Chip>
      ) : (
        // Gate exists but was never opened — the ERM has not sent it yet.
        <span className="text-[11px] text-gray-400">—</span>
      )}
    </span>
  );
}

/**
 * "4d ago". Recency is what an operator scans a triage list for; the exact
 * stamp stays on the date above it and in the title.
 */
function ageLabel(ts: number): string {
  if (!ts) return "";
  const mins = Math.round((Date.now() - ts) / 60_000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days}d ago`;
  return `${Math.round(days / 30)}mo ago`;
}
