"use client";

import { ArrowRight, Link2Off, PenLine, Route } from "lucide-react";

import AgreementStatusPill from "@/components/agreement-erm/AgreementStatusPill";
import { Chip, SectionCard, TableShell, TD, TH } from "@/components/admin-console/primitives";
import {
  AGREEMENT_PIPELINE,
  AGREEMENT_STATUS_META,
  LIVE_STATUSES,
  describeStatus,
  statusLabel,
} from "@/lib/agreement-status";
import type { ConsultantApplicationStatus } from "@/lib/api";

/**
 * The lifecycle reference tab — in-product documentation for the agreement
 * process.
 *
 * Every word on this page is read out of src/lib/agreement-status.ts at render
 * time rather than retyped here. That is the whole point: if the wording on a
 * pill ever changes, this page changes with it, so the documentation cannot
 * drift away from the console the way the four competing vocabularies did
 * before that module existed. The only strings this file owns are the OLD
 * labels in "What changed" (historical, deliberately not in the module) and the
 * connective prose.
 *
 * Pure reference — no props, no fetches, no state.
 */
export default function AdminLifecycleTab() {
  // The Phase 2 reading of the approval gate. Derived rather than written out
  // so the footnote stays true if the gate's owner ever changes.
  const phase2Approval = describeStatus("AWAITING_APPROVALS", { phase: 2 });

  return (
    <div className="space-y-5">
      {/* ── Intro ──────────────────────────────────────────────── */}
      <header className="max-w-3xl">
        <div className="flex items-center gap-2">
          <Route size={16} className="text-sage-copper" />
          <h1 className="text-base font-bold text-sage-navy">
            How an agreement moves
          </h1>
        </div>
        <p className="mt-2 text-sm text-gray-600 leading-relaxed">
          This is the lifecycle every consultant agreement follows, from the
          invitation an ERM sends to the countersignature that executes it. The
          labels below are not a paraphrase — they are rendered by the same code
          the rest of the console uses, so a status named here is worded exactly
          the way you will meet it on a row, a pill or a filter chip.
        </p>
      </header>

      {/* ── 2. The happy path ──────────────────────────────────── */}
      <SectionCard
        title="The happy path"
        description="Five steps. Everything else is a loop back to one of these or a terminal off-ramp."
      >
        <ol className="grid gap-6 sm:grid-cols-5 sm:gap-0">
          {AGREEMENT_PIPELINE.map((stage, i) => {
            const isLast = i === AGREEMENT_PIPELINE.length - 1;
            return (
              <li key={stage.step} className="relative flex gap-3 sm:block">
                {/*
                  Connectors are rules, not images: horizontal between the
                  circles on sm+, vertical down the gutter on mobile. Both are
                  positioned off the 28px circle (centre 14px), and the mobile
                  one runs -1.5rem past the item to close the gap-6 stack.
                */}
                {!isLast && (
                  <>
                    <span
                      aria-hidden
                      className="hidden sm:block absolute left-9 right-0 top-[14px] h-px bg-gray-200"
                    />
                    <span
                      aria-hidden
                      className="sm:hidden absolute left-[13px] top-8 bottom-[-1.5rem] w-px bg-gray-200"
                    />
                  </>
                )}

                <span className="relative z-10 shrink-0 flex items-center justify-center h-7 w-7 rounded-full bg-sage-navy text-white text-[12px] font-bold tabular-nums">
                  {stage.step}
                </span>

                <div className="min-w-0 sm:mt-3 sm:pr-5">
                  <h3 className="text-sm font-bold text-sage-navy">
                    {stage.title}
                  </h3>
                  <p className="mt-1 text-[11px] font-semibold text-gray-500">
                    Owner: <span className="text-gray-700">{stage.owner}</span>
                  </p>
                  <p className="mt-1.5 text-[12px] text-gray-500 leading-relaxed">
                    {stage.summary}
                  </p>
                  {/*
                    The pills are the tie-back: this is what the step looks like
                    in a table, so the reader can match diagram to queue.
                  */}
                  <div className="mt-2.5 flex flex-wrap gap-1.5">
                    {stage.statuses.map((s) => (
                      <AgreementStatusPill key={s} status={s} size="xs" />
                    ))}
                  </div>
                </div>
              </li>
            );
          })}
        </ol>
      </SectionCard>

      {/* ── 3. Every status, defined ───────────────────────────── */}
      <SectionCard
        title="Every status, defined"
        description="One row per value the system can store. Live states first."
        padded={false}
      >
        <TableShell
          head={
            <tr>
              <th className={TH}>Status</th>
              <th className={TH}>What it means</th>
              <th className={TH}>Waiting on</th>
              <th className={TH}>Next action</th>
            </tr>
          }
        >
          {LIVE_STATUSES.map((s) => (
            <StatusRow key={s} status={s} />
          ))}

          {/* Visual break rather than a second table, so the columns stay aligned. */}
          <tr className="bg-gray-50">
            <td colSpan={4} className="px-4 py-2.5">
              <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-400">
                Retired — no live code path produces these
              </p>
              <p className="mt-0.5 text-[11px] text-gray-400 leading-relaxed">
                Kept so historical rows still render something honest. They are
                hidden from filters, and the note replaces the next action
                because there is no ordinary way forward from them.
              </p>
            </td>
          </tr>

          {RETIRED_STATUSES.map((s) => (
            <StatusRow key={s} status={s} />
          ))}
        </TableShell>

        <p className="px-4 py-3 border-t border-gray-100 text-[11px] text-gray-400 leading-relaxed">
          “Waiting on” is the Phase 1 reading. In Phase 2 the Accounts gate opens
          as well, so “{statusLabel("AWAITING_APPROVALS")}” waits on{" "}
          {phase2Approval.blockedOn}.
        </p>
      </SectionCard>

      {/* ── 4. Look like a status, aren't ──────────────────────── */}
      <SectionCard
        title="Two things that look like a status but are not"
        description="Both change the pill you see without changing what is stored on the agreement."
      >
        <div className="space-y-5">
          {/* Link expiry */}
          <div className="max-w-3xl">
            <div className="flex items-center gap-2">
              <Link2Off size={13} className="text-gray-400" />
              <h3 className="text-sm font-bold text-sage-navy">Link expired</h3>
            </div>
            <p className="mt-2 text-[13px] text-gray-600 leading-relaxed">
              The consultant’s access link lapses 7 days after it is sent. The
              agreement itself is untouched and keeps its status — what lapses is
              only the consultant’s ability to open it. Resending the invitation
              fixes it; nothing else needs unwinding.
            </p>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <AgreementStatusPill status="SUBMITTED" size="xs" />
              <ArrowRight size={12} className="text-gray-300" />
              <AgreementStatusPill
                status="SUBMITTED"
                size="xs"
                context={{ linkExpired: true }}
              />
              <span className="text-[11px] text-gray-400">
                same row, same stored status — the console only swaps the pill
                once it knows the link has lapsed
              </span>
            </div>
          </div>

          {/* The hidden sub-state inside VERIFIED */}
          <div className="max-w-3xl border-t border-gray-100 pt-5">
            <div className="flex items-center gap-2">
              <PenLine size={13} className="text-gray-400" />
              <h3 className="text-sm font-bold text-sage-navy">
                “{statusLabel("VERIFIED")}” hides two situations
              </h3>
            </div>
            <p className="mt-2 text-[13px] text-gray-600 leading-relaxed">
              One stored value covers both sides of a decision the ERM still has
              to make, and the only thing separating them is whether the
              consultant version has been released.
            </p>
            <div className="mt-3 grid gap-3 sm:grid-cols-2">
              <ReleaseCase released={false} />
              <ReleaseCase released />
            </div>
            <p className="mt-3 text-[12px] text-gray-500 leading-relaxed">
              Detail pages show the sharper label because they load the release
              flag. List rows show the plain “{statusLabel("VERIFIED")}” instead
              — the underlying list data does not carry the flag, so refining the
              label there would be a guess.
            </p>
          </div>
        </div>
      </SectionCard>

      {/* ── 5. What changed ────────────────────────────────────── */}
      <SectionCard
        tone="waiting"
        title="What changed"
        description="If you learned the old vocabulary, this is the mapping. The stored values did not change — only what the console calls them."
      >
        <ul className="divide-y divide-gray-100 -my-1">
          {RENAMES.map((rename) => {
            const meta = describeStatus(rename.status);
            // Retired states carry their own explanation in the module; only
            // the live renames need a reason written here.
            const why = rename.why ?? meta.retiredNote;
            return (
              <li
                key={rename.status}
                className="grid gap-1.5 py-3 sm:grid-cols-[16rem_1fr] sm:gap-4"
              >
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="text-[12px] text-gray-400 line-through">
                    {rename.from}
                  </span>
                  <ArrowRight size={12} className="text-gray-300 shrink-0" />
                  <AgreementStatusPill
                    status={rename.status}
                    size="xs"
                    showTooltip={false}
                  />
                  {meta.retired && <Chip>Retired</Chip>}
                </div>
                <p className="text-[12px] text-gray-500 leading-relaxed">
                  {why}
                </p>
              </li>
            );
          })}
        </ul>
      </SectionCard>
    </div>
  );
}

/* ── Pieces ───────────────────────────────────────────────────── */

const ALL_STATUSES = Object.keys(
  AGREEMENT_STATUS_META,
) as ConsultantApplicationStatus[];

/** Declaration order in the module already puts these last, in a sane order. */
const RETIRED_STATUSES = ALL_STATUSES.filter(
  (s) => AGREEMENT_STATUS_META[s].retired,
);

function StatusRow({ status }: { status: ConsultantApplicationStatus }) {
  const meta = describeStatus(status);
  const retired = meta.retired === true;

  return (
    <tr className={retired ? "opacity-60" : undefined}>
      <td className={`${TD} align-top whitespace-nowrap`}>
        {/*
          Tooltips are off here: the tooltip text is the meaning, and the
          meaning already has its own column two cells to the right.
        */}
        <AgreementStatusPill status={status} showTooltip={false} />
      </td>
      <td
        className={`${TD} align-top text-[12px] text-gray-600 leading-relaxed min-w-[18rem]`}
      >
        {meta.meaning}
      </td>
      <td className={`${TD} align-top whitespace-nowrap text-[12px]`}>
        {meta.blockedOn ? (
          <span className="font-semibold text-gray-700">{meta.blockedOn}</span>
        ) : (
          <span className="text-gray-300">—</span>
        )}
      </td>
      <td
        className={`${TD} align-top text-[12px] leading-relaxed min-w-[14rem]`}
      >
        {retired ? (
          <span className="italic text-gray-400">{meta.retiredNote ?? "—"}</span>
        ) : meta.nextAction ? (
          <span className="text-gray-600">{meta.nextAction}</span>
        ) : (
          <span className="text-gray-300">—</span>
        )}
      </td>
    </tr>
  );
}

/**
 * One side of the release decision. Both the label and the copy come out of
 * describeStatus with the flag set, which is exactly what a detail page does.
 */
function ReleaseCase({ released }: { released: boolean }) {
  const meta = describeStatus("VERIFIED", { consultantCopyReleased: released });

  return (
    <div className="rounded-xl border border-gray-100 bg-gray-50/60 px-3.5 py-3">
      <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-400">
        {released ? "Consultant version released" : "Not released yet"}
      </p>
      <div className="mt-1.5">
        <AgreementStatusPill
          status="VERIFIED"
          size="xs"
          context={{ consultantCopyReleased: released }}
          showTooltip={false}
        />
      </div>
      <p className="mt-2 text-[12px] text-gray-600 leading-relaxed">
        {meta.meaning}
      </p>
      <p className="mt-1.5 text-[12px] text-gray-500 leading-relaxed">
        <span className="font-semibold text-gray-600">Next:</span>{" "}
        {meta.nextAction}
        {!released && " Until then, “Send for approval” is refused."}
      </p>
    </div>
  );
}

/**
 * Old label → current status. The left-hand strings are the only wording in
 * this file that is not read from the status module, because they describe a
 * vocabulary the code no longer has. `why` is omitted where the module's own
 * retiredNote already answers it.
 */
const RENAMES: ReadonlyArray<{
  from: string;
  status: ConsultantApplicationStatus;
  why?: string;
}> = [
  {
    from: "Submitted",
    status: "SUBMITTED",
    why: "“Submitted” described the ERM creating the agreement, not the consultant returning it — so a brand-new row read as if work had come back.",
  },
  {
    from: "Revision requested",
    status: "REVISION_REQUESTED",
    why: "Plain English, and it now pairs against “Declined by approver” so the direction of the bounce is obvious at a glance.",
  },
  {
    from: "Verified",
    status: "VERIFIED",
    why: "Nobody at Sage verifies anything to reach this state — it is set the moment the consultant signs. “Verified” read as staff having checked the agreement.",
  },
  {
    from: "Awaiting approvals",
    status: "AWAITING_APPROVALS",
    why: "Names the stage the agreement is in rather than the queue it sits in.",
  },
  {
    from: "Approval revision",
    status: "APPROVAL_REVISION_REQUESTED",
    why: "It sits with the ERM, not the consultant — who is never notified and cannot act on it.",
  },
  {
    from: "Ready to sign",
    status: "READY_TO_SIGN",
    why: "It never said who signs. The consultant signed long ago; this is the ERM’s countersignature.",
  },
  {
    from: "Completed",
    status: "COMPLETED",
    why: "An agreement is executed by signature. “Completed” read like a task had been ticked off rather than a contract taking effect.",
  },
  { from: "Draft", status: "DRAFT" },
  { from: "Updated", status: "UPDATED" },
  { from: "Signed", status: "SIGNED" },
  { from: "Expired", status: "EXPIRED" },
];
