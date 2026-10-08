"use client";

import { PenLine, Route } from "lucide-react";

import AgreementStatusPill from "@/components/web-agreement/erm/AgreementStatusPill";
import {
  Chip,
  SectionCard,
  TableShell,
  TD,
  TH,
} from "@/components/web-agreement/ui/primitives";
import {
  describeStatus,
  LIVE_STATUSES,
  statusLabel,
  WEB_AGREEMENT_PIPELINE,
} from "@/lib/web-agreement-status";
import type { WebAgreementStatus } from "@/lib/api";

/**
 * Lifecycle sub-tab: in-product reference for how a website agreement moves.
 * The website's copy of the console's AdminLifecycleTab
 * (src/components/admin-console/AdminLifecycleTab.tsx).
 *
 * Every label, meaning and next actor is read out of
 * src/lib/web-agreement-status.ts at render time rather than retyped here,
 * so this page cannot drift away from the pills on the other screens. The
 * only strings this file owns are the connective prose.
 *
 * Left out because the website has no such thing: the retired statuses,
 * the access-link expiry and the console's "What changed" rename history.
 *
 * Pure reference — no props, no fetches, no state.
 */
export default function AdminLifecycle() {
  // The Phase 2 reading of the approval gate. Derived rather than written out
  // so the footnote stays true if the gate's owner ever changes.
  const phase2Approval = describeStatus("AWAITING_APPROVALS", { phase: 2 });

  return (
    <div className="space-y-5">
      {/* ── Intro ──────────────────────────────────────────────── */}
      <header className="max-w-3xl">
        <div className="flex items-center gap-2">
          <Route size={16} className="text-sage-copper" />
          <h2 className="text-base font-bold text-sage-navy">
            How an agreement moves
          </h2>
        </div>
        <p className="mt-2 text-sm text-gray-600 leading-relaxed">
          This is the lifecycle every participant agreement follows, from the
          agreement an ERM creates to the countersignature that executes it.
          The labels below are not a paraphrase — they are rendered by the same
          code the rest of the agreement screens use, so a status named here is
          worded exactly the way you will meet it on a row, a pill or a filter
          chip.
        </p>
      </header>

      {/* ── The happy path ─────────────────────────────────────── */}
      <SectionCard
        title="The happy path"
        description="Five steps. Everything else is a loop back to one of these or a terminal off-ramp."
      >
        {/* Five across only from xl: beside the admin sidebar a narrower row
            would push the status pills out of their column. */}
        <ol className="grid gap-6 xl:grid-cols-5 xl:gap-0">
          {WEB_AGREEMENT_PIPELINE.map((stage, i) => {
            const isLast = i === WEB_AGREEMENT_PIPELINE.length - 1;
            return (
              <li key={stage.step} className="relative flex gap-3 xl:block">
                {/*
                  Connectors are rules, not images: horizontal between the
                  circles on xl, vertical down the gutter below it. Both are
                  positioned off the 28px circle (centre 14px), and the
                  vertical one runs -1.5rem past the item to close the gap-6
                  stack.
                */}
                {!isLast && (
                  <>
                    <span
                      aria-hidden
                      className="hidden xl:block absolute left-9 right-0 top-[14px] h-px bg-gray-200"
                    />
                    <span
                      aria-hidden
                      className="xl:hidden absolute left-[13px] top-8 bottom-[-1.5rem] w-px bg-gray-200"
                    />
                  </>
                )}

                <span className="relative z-10 shrink-0 flex items-center justify-center h-7 w-7 rounded-full bg-sage-navy text-white text-[12px] font-bold tabular-nums">
                  {stage.step}
                </span>

                <div className="min-w-0 xl:mt-3 xl:pr-5">
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

      {/* ── Every status, defined ──────────────────────────────── */}
      <SectionCard
        title="Every status, defined"
        description="One row per value the system can store."
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
        </TableShell>

        <p className="px-4 py-3 border-t border-gray-100 text-[11px] text-gray-400 leading-relaxed">
          “Waiting on” is the Phase 1 reading. In Phase 2 the Accounts gate opens
          as well, so “{statusLabel("AWAITING_APPROVALS")}” waits on{" "}
          {phase2Approval.blockedOn}.
        </p>
      </SectionCard>

      {/* ── The two phases ─────────────────────────────────────── */}
      <SectionCard
        title="The two phases"
        description="The lifecycle above runs once per phase. Phase is a separate axis from status — an agreement is always at one phase AND one status."
      >
        <div className="grid gap-4 sm:grid-cols-2 max-w-4xl">
          <div className="rounded-xl border border-gray-200 p-4">
            <div className="flex flex-wrap items-center gap-2">
              <Chip>Phase 1</Chip>
              <span className="text-[13px] font-semibold text-gray-900">
                Coaching agreement
              </span>
            </div>
            <p className="mt-2 text-[13px] leading-relaxed text-gray-600">
              Where every agreement starts. It runs the full path — participant
              signs, <strong>Manager</strong> approves, ERM countersigns. The
              Accounts gate does not exist here, which is why the Accounts
              column reads “N/A” on a Phase 1 row.
            </p>
          </div>
          <div className="rounded-xl border border-violet-200 bg-violet-50/30 p-4">
            <div className="flex flex-wrap items-center gap-2">
              <Chip tone="review">Phase 2</Chip>
              <span className="text-[13px] font-semibold text-gray-900">
                Post-offer support
              </span>
            </div>
            <p className="mt-2 text-[13px] leading-relaxed text-gray-600">
              The ERM reopens an executed Phase 1 agreement, promoting the
              sections the participant skipped. It is the{" "}
              <strong>same agreement</strong>, not a new one: it returns to the
              start of the path and needs{" "}
              <strong>{phase2Approval.blockedOn}</strong> before the ERM
              countersigns again.
            </p>
          </div>
        </div>
        <p className="mt-4 max-w-3xl text-[12px] leading-relaxed text-gray-500">
          Only an executed agreement can be advanced, so reaching Phase 2 is
          itself proof that Phase 1 was countersigned. That is why the Overview
          tab keeps counting a reopened agreement under an ERM’s Phase 1
          executions — the milestone happened and does not un-happen. Phase 1
          content is locked once reopened; the participant re-signs only the
          final execution block and the promoted sections.
        </p>
      </SectionCard>

      {/* ── Looks like a status, isn't ─────────────────────────── */}
      <SectionCard
        title="One thing that looks like a status but is not"
        description="It changes the pill you see without changing the status stored on the agreement."
      >
        <div className="max-w-3xl">
          <div className="flex items-center gap-2">
            <PenLine size={13} className="text-gray-400 shrink-0" />
            <h3 className="text-sm font-bold text-sage-navy">
              “{statusLabel("VERIFIED")}” hides two situations
            </h3>
          </div>
          <p className="mt-2 text-[13px] text-gray-600 leading-relaxed">
            One stored value covers both sides of a decision the ERM still has
            to make, and the only thing separating them is whether the ERM has
            verified the agreement.
          </p>
          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            <ReleaseCase released={false} />
            <ReleaseCase released />
          </div>
        </div>
      </SectionCard>
    </div>
  );
}

/* ── Pieces ───────────────────────────────────────────────────── */

function StatusRow({ status }: { status: WebAgreementStatus }) {
  const meta = describeStatus(status);

  return (
    <tr>
      <td className={`${TD} align-top whitespace-nowrap`}>
        {/*
          Tooltips are off here: the tooltip text is the meaning, and the
          meaning already has its own column to the right.
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
        {meta.nextAction ? (
          <span className="text-gray-600">{meta.nextAction}</span>
        ) : (
          <span className="text-gray-300">—</span>
        )}
      </td>
    </tr>
  );
}

/**
 * One side of the ERM's Verify. Both the label and the copy come out of
 * describeStatus with the flag set, which is exactly what the list and
 * detail do.
 */
function ReleaseCase({ released }: { released: boolean }) {
  const meta = describeStatus("VERIFIED", { consultantCopyReleased: released });

  return (
    <div className="rounded-xl border border-gray-100 bg-gray-50/60 px-3.5 py-3">
      <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-400">
        {released ? "Verified by the ERM" : "Not verified yet"}
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
