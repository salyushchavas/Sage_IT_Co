"use client";

import { useEffect, useId, useState, type ReactNode } from "react";
import {
  AlertCircle,
  Check,
  CheckCircle2,
  Clock,
  Copy,
  Info,
  Loader2,
  X,
  type LucideIcon,
} from "lucide-react";

import { formatUsDateTime } from "@/lib/dates";
import {
  APPROVAL_DECISION_META,
  TONE_ACCENT,
  TONE_CLASSES,
  type StatusTone,
  type WebApprovalDecision,
} from "@/lib/web-agreement-status";

/**
 * Shared chrome for the website agreement's approval screens: the approval
 * board, the approver dashboard, the System Admin's Agreements tab and the
 * user-page cards.
 *
 * The website's copy of the console's src/components/admin-console/
 * primitives.tsx, plus the board's two chips (ApprovalGateChip, FilterChip)
 * from src/components/agreement-erm/ApprovalStatusBoards.tsx, reading the
 * website's own status vocabulary (src/lib/web-agreement-status.ts). Same
 * markup and classes, so the copied screens keep the console's look. Kept
 * separate so a change here never reaches the console.
 */

// ── Layout ──────────────────────────────────────────────────────

/**
 * A titled white card. The screens' only content container — panels, tables
 * and forms all sit in one of these so spacing and borders stay uniform.
 */
export function SectionCard({
  title,
  description,
  action,
  tone,
  children,
  padded = true,
}: {
  title?: string;
  description?: ReactNode;
  /** Right-aligned control in the header (a button, a filter, a count). */
  action?: ReactNode;
  /** Left edge accent — use sparingly, e.g. red for destructive panels. */
  tone?: StatusTone;
  children: ReactNode;
  /** Off when the child is a full-bleed table. */
  padded?: boolean;
}) {
  return (
    <section className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
      {tone && <div className={`h-1 w-full ${TONE_ACCENT[tone]}`} />}
      {(title || action) && (
        <header className="flex items-start justify-between gap-3 px-5 pt-4 pb-3 border-b border-gray-100">
          <div className="min-w-0">
            {title && (
              <h2 className="text-sm font-bold text-sage-navy">{title}</h2>
            )}
            {description && (
              <p className="text-[12px] text-gray-500 mt-0.5 leading-relaxed">
                {description}
              </p>
            )}
          </div>
          {action && <div className="shrink-0">{action}</div>}
        </header>
      )}
      <div className={padded ? "px-5 py-4" : ""}>{children}</div>
    </section>
  );
}

/** Headline number. Used in the overview KPI row. */
export function StatTile({
  label,
  value,
  hint,
  tone = "neutral",
  Icon,
  onClick,
}: {
  label: string;
  value: number | string;
  hint?: string;
  tone?: StatusTone;
  Icon?: LucideIcon;
  /** Makes the tile a filter shortcut into another tab. */
  onClick?: () => void;
}) {
  const body = (
    <>
      <div className="flex items-center gap-2">
        <span className={`h-2 w-2 rounded-full ${TONE_ACCENT[tone]}`} />
        <span className="text-[11px] font-semibold uppercase tracking-wider text-gray-500">
          {label}
        </span>
        {Icon && <Icon size={13} className="ml-auto text-gray-300" />}
      </div>
      <p className="mt-2 text-2xl font-bold text-gray-900 tabular-nums leading-none">
        {value}
      </p>
      {hint && <p className="mt-1.5 text-[11px] text-gray-400">{hint}</p>}
    </>
  );

  const base =
    "text-left bg-white rounded-xl border border-gray-100 shadow-sm px-4 py-3";
  return onClick ? (
    <button
      type="button"
      onClick={onClick}
      className={`${base} w-full hover:border-sage-navy/30 hover:shadow transition cursor-pointer`}
    >
      {body}
    </button>
  ) : (
    <div className={base}>{body}</div>
  );
}

/**
 * Proportional stacked bar over the pipeline stages, with a labelled legend
 * underneath. Segments carry a 2px surface gap so adjacent fills stay
 * distinguishable, and every segment is named in the legend — hue is never
 * the only thing telling two stages apart.
 */
export function StageMeter({
  segments,
}: {
  segments: ReadonlyArray<{
    key: string;
    label: string;
    count: number;
    tone: StatusTone;
    onClick?: () => void;
  }>;
}) {
  const total = segments.reduce((sum, s) => sum + s.count, 0);
  const shown = segments.filter((s) => s.count > 0);

  if (total === 0) {
    return (
      <p className="text-[12px] text-gray-400 italic">Nothing in flight.</p>
    );
  }

  return (
    <div>
      <div className="flex gap-[2px] h-2.5 rounded-full overflow-hidden bg-gray-100">
        {shown.map((s) => (
          <div
            key={s.key}
            className={TONE_ACCENT[s.tone]}
            style={{ width: `${(s.count / total) * 100}%` }}
            title={`${s.label}: ${s.count}`}
          />
        ))}
      </div>
      <ul className="mt-3 grid grid-cols-2 sm:grid-cols-3 gap-x-4 gap-y-1.5">
        {segments.map((s) => {
          const row = (
            <>
              <span className={`h-2 w-2 rounded-full shrink-0 ${TONE_ACCENT[s.tone]}`} />
              <span className="text-[12px] text-gray-600 truncate">{s.label}</span>
              <span className="ml-auto text-[12px] font-bold text-gray-900 tabular-nums">
                {s.count}
              </span>
            </>
          );
          return (
            <li key={s.key}>
              {s.onClick ? (
                <button
                  type="button"
                  onClick={s.onClick}
                  className="w-full flex items-center gap-2 rounded px-1 -mx-1 py-0.5 hover:bg-gray-50 cursor-pointer"
                >
                  {row}
                </button>
              ) : (
                <div className="flex items-center gap-2 px-1 -mx-1 py-0.5">{row}</div>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export function EmptyState({
  Icon,
  title,
  hint,
}: {
  Icon: LucideIcon;
  title: string;
  hint?: string;
}) {
  return (
    <div className="py-10 text-center">
      <Icon size={22} className="mx-auto text-gray-300" />
      <p className="mt-2 text-sm font-semibold text-gray-500">{title}</p>
      {hint && <p className="mt-0.5 text-[12px] text-gray-400">{hint}</p>}
    </div>
  );
}

export function Spinner({ label }: { label?: string }) {
  return (
    <div className="py-10 flex flex-col items-center gap-2 text-gray-400">
      <Loader2 size={20} className="animate-spin text-sage-navy" />
      {label && <span className="text-[12px]">{label}</span>}
    </div>
  );
}

export function InlineAlert({
  tone,
  children,
  onDismiss,
}: {
  tone: "error" | "success" | "info";
  children: ReactNode;
  onDismiss?: () => void;
}) {
  const map = {
    error: { cls: "border-red-200 bg-red-50 text-red-700", Icon: AlertCircle },
    success: {
      cls: "border-emerald-200 bg-emerald-50 text-emerald-700",
      Icon: CheckCircle2,
    },
    info: { cls: "border-sky-200 bg-sky-50 text-sky-800", Icon: Info },
  }[tone];
  return (
    <div
      // Errors and confirmations are injected after an action the user
      // already triggered, often far from where they are looking. Without a
      // live region a failed delete or save is announced to nobody.
      role={tone === "error" ? "alert" : "status"}
      aria-live={tone === "error" ? "assertive" : "polite"}
      className={`flex items-start gap-2 rounded-xl border px-3 py-2 text-[13px] ${map.cls}`}
    >
      <map.Icon size={14} className="mt-0.5 shrink-0" />
      <div className="flex-1 min-w-0">{children}</div>
      {onDismiss && (
        <button
          type="button"
          onClick={onDismiss}
          className="shrink-0 opacity-60 hover:opacity-100 cursor-pointer"
          aria-label="Dismiss"
        >
          <X size={13} />
        </button>
      )}
    </div>
  );
}

/** Small neutral badge. For roles, phases, counts — not lifecycle status. */
export function Chip({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: StatusTone;
}) {
  return (
    <span
      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold whitespace-nowrap ${TONE_CLASSES[tone]}`}
    >
      {children}
    </span>
  );
}

// ── Chips for the approval gate ─────────────────────────────────

/**
 * Icon per gate decision, so hue is never the only signal. Label and tone come
 * from the shared APPROVAL_DECISION_META — this map adds only the glyph.
 */
const DECISION_ICON: Record<WebApprovalDecision, LucideIcon> = {
  APPROVED: CheckCircle2,
  PENDING: Clock,
  REVISION_REQUESTED: AlertCircle,
};

/**
 * One approval gate's decision. Carries an icon as well as a hue so the three
 * outcomes are never told apart by colour alone.
 */
export function ApprovalGateChip({
  label,
  status,
  absent,
  approverName,
  decidedAt,
}: {
  /** "Mgr" / "Acct". Omit to render the chip on its own. */
  label?: string;
  /** A gate decision; anything else shows as a plain chip with the raw value. */
  status?: WebApprovalDecision | string | null;
  /** The gate does not exist for this agreement — renders "N/A", not "—". */
  absent?: boolean;
  approverName?: string | null;
  decidedAt?: string | null;
}) {
  const meta = status
    ? APPROVAL_DECISION_META[status as WebApprovalDecision]
    : undefined;
  const Icon = meta ? DECISION_ICON[status as WebApprovalDecision] : undefined;
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
      ) : status ? (
        <Chip>{status}</Chip>
      ) : (
        // The gate exists but was never opened — not routed yet.
        <span className="text-[11px] text-gray-400">—</span>
      )}
    </span>
  );
}

/** One filter chip's caption, count and colour. */
export interface FilterChipDef {
  key: string;
  label: string;
  count: number;
  /** Null on "All", which has no colour of its own. */
  tone: StatusTone | null;
}

/**
 * Desk / stage filter. Not the Chip primitive — that one is a static badge;
 * this is a control, so it carries the tone as a dot and reserves the fill
 * for "active". The count sits inside the button text so it is announced with
 * the label.
 */
export function FilterChip({
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
          number the reader actually uses to pick a filter. */}
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

// ── Table ───────────────────────────────────────────────────────

export const TH =
  "text-left px-4 py-2 text-[11px] uppercase tracking-wider font-semibold text-gray-500";
export const TD = "px-4 py-2.5 align-middle";

/**
 * Phones and tablets: the action column stays in view while the table
 * scrolls sideways inside its card (the ERM dashboard's classes).
 */
export const STICKY_ACTION_TH =
  "sticky right-0 bg-gray-50 shadow-[-6px_0_6px_-6px_rgba(0,0,0,0.15)] xl:shadow-none";
export const STICKY_ACTION_TD =
  "sticky right-0 bg-white shadow-[-6px_0_6px_-6px_rgba(0,0,0,0.15)] xl:shadow-none";

export function TableShell({
  head,
  children,
}: {
  head: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="bg-gray-50">{head}</thead>
        <tbody className="divide-y divide-gray-100">{children}</tbody>
      </table>
    </div>
  );
}

// ── Buttons ─────────────────────────────────────────────────────

export const BTN_PRIMARY =
  "inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold " +
  "bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer " +
  "disabled:opacity-60 disabled:cursor-not-allowed";

export const BTN_SECONDARY =
  "inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold " +
  "border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer " +
  "disabled:opacity-50 disabled:cursor-not-allowed";

export const BTN_DANGER =
  "inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold " +
  "border border-red-200 bg-white hover:bg-red-50 text-red-700 cursor-pointer " +
  "disabled:opacity-50 disabled:cursor-not-allowed";

/** Compact variant for in-row actions. */
export const BTN_ROW =
  "inline-flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-semibold " +
  "border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer " +
  "disabled:opacity-50";

// ── Modal ───────────────────────────────────────────────────────
// The compact dialog of the admin screens (Maintenance's typed confirm).
// The ERM detail's modals use ModalShell.tsx instead.

export const modalInput =
  "w-full px-3 py-2 text-sm rounded-md border border-gray-200 " +
  "focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy " +
  "disabled:bg-gray-50 disabled:text-gray-500";

export function Modal({
  title,
  subtitle,
  onClose,
  disabled,
  width = "md",
  children,
}: {
  title: string;
  subtitle?: string;
  onClose: () => void;
  disabled?: boolean;
  width?: "md" | "lg";
  children: ReactNode;
}) {
  const titleId = useId();

  // Escape closes, matching every other dismissible surface. Suppressed
  // while `disabled` (a submit is in flight) so nobody walks away from a
  // half-finished action and assumes it did not happen.
  useEffect(() => {
    if (disabled) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [disabled, onClose]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={`w-full ${width === "lg" ? "max-w-lg" : "max-w-md"} bg-white rounded-2xl border border-gray-100 shadow-xl max-h-[90vh] overflow-y-auto`}
      >
        <div className="flex items-start justify-between gap-3 px-5 py-3 border-b border-gray-100 sticky top-0 bg-white">
          <div className="min-w-0">
            <h2 id={titleId} className="text-sm font-bold text-sage-navy truncate">
              {title}
            </h2>
            {subtitle && (
              <p className="text-[11px] text-gray-500 mt-0.5 truncate">{subtitle}</p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={disabled}
            className="shrink-0 text-gray-400 hover:text-gray-700 cursor-pointer disabled:opacity-50"
            aria-label="Close"
          >
            <X size={16} />
          </button>
        </div>
        <div className="px-5 py-4">{children}</div>
      </div>
    </div>
  );
}

export function ModalActions({
  onClose,
  onSubmit,
  submitting,
  submitLabel,
  canSubmit,
  danger,
}: {
  onClose: () => void;
  onSubmit: () => void;
  submitting: boolean;
  submitLabel: string;
  canSubmit: boolean;
  danger?: boolean;
}) {
  return (
    <div className="flex items-center justify-end gap-2 pt-4 mt-1 border-t border-gray-100">
      <button
        type="button"
        onClick={onClose}
        disabled={submitting}
        className="px-3 py-2 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer disabled:opacity-50"
      >
        Cancel
      </button>
      <button
        type="button"
        onClick={onSubmit}
        disabled={!canSubmit}
        className={
          danger
            ? "inline-flex items-center justify-center gap-1.5 px-4 py-2 rounded-md text-xs font-bold bg-red-600 text-white hover:bg-red-700 cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
            : BTN_PRIMARY + " px-4"
        }
      >
        {submitting && <Loader2 size={12} className="animate-spin" />}
        {submitLabel}
      </button>
    </div>
  );
}

export function ModalField({
  label,
  required,
  hint,
  children,
}: {
  label: string;
  required?: boolean;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div>
      <label className="block text-[11px] font-semibold text-gray-600 mb-0.5">
        {label}
        {required && <span className="text-red-500"> *</span>}
      </label>
      {children}
      {hint && <p className="mt-1 text-[10px] text-gray-400">{hint}</p>}
    </div>
  );
}

// ── Copy-to-clipboard ───────────────────────────────────────────

export function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable */
    }
  };
  return (
    <button
      type="button"
      onClick={copy}
      className="shrink-0 inline-flex items-center gap-1 px-1.5 py-1 rounded-md text-[11px] font-semibold text-sage-navy hover:bg-sage-navy/10 cursor-pointer"
    >
      {copied ? <Check size={12} /> : <Copy size={12} />}
      {copied ? "Copied" : "Copy"}
    </button>
  );
}

export function CopyField({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white px-3 py-2">
      <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-400">
        {label}
      </p>
      <div className="flex items-center justify-between gap-2">
        <span className={"text-sm text-gray-900 truncate " + (mono ? "font-mono" : "")}>
          {value}
        </span>
        <CopyButton text={value} />
      </div>
    </div>
  );
}
