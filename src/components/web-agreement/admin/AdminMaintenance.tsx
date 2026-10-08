"use client";

import { useState, type ReactNode } from "react";
import { Loader2, RefreshCw, ShieldOff, type LucideIcon } from "lucide-react";

import { statusLabel } from "@/lib/web-agreement-status";
import {
  webAdminRegenerateCompletedAgreements,
  webAdminRevokeErmSignatures,
  type WebAdminBackfillSummary,
} from "@/lib/api";
import {
  BTN_DANGER,
  InlineAlert,
  Modal,
  ModalActions,
  ModalField,
  SectionCard,
  modalInput,
} from "@/components/web-agreement/ui/primitives";

/**
 * Maintenance sub-tab: the System Admin's two backfills over every executed
 * website agreement. The website's copy of the console's AdminMaintenanceTab
 * (src/components/admin-console/AdminMaintenanceTab.tsx), calling
 * /api/web-agreement-admin.
 *
 * Both rewrite already-executed agreements, so each runs a DRY RUN first —
 * the server reports `matched` without touching a row — and the System
 * Admin confirms against that exact number, typing the verb, before
 * anything executes.
 *
 * The console's wording is kept, with "consultant" read as "participant".
 * Regenerate's sentence about the copy the consultant can download is left
 * out: participants never get a PDF on the website.
 */

// Status wording is owned by src/lib/web-agreement-status.ts. Resolving the
// labels here (rather than typing "Fully executed" / "Signed by participant")
// keeps these sentences in step with the pills if a label is ever renamed.
const EXECUTED = statusLabel("COMPLETED");
const SIGNED_BY_PARTICIPANT = statusLabel("VERIFIED");

/** Which count in the summary means "actually changed", per endpoint. */
type BackfillResultKey = "reverted" | "regenerated";

/** One dismissible line under an action. Errors and outcomes share the slot. */
interface Feedback {
  tone: "error" | "success" | "info";
  text: string;
}

export default function AdminMaintenance() {
  return (
    <div className="space-y-4">
      <InlineAlert tone="info">
        Every action below previews first. Running one fetches the number of
        affected agreements without changing anything — nothing is written until
        you confirm that number and type the verb.
      </InlineAlert>

      <BackfillAction
        title="Revoke ERM countersignatures"
        verb="Revoke"
        Icon={ShieldOff}
        run={webAdminRevokeErmSignatures}
        resultKey="reverted"
        description={
          <>
            Clears the ERM countersignature on every agreement in “{EXECUTED}”
            and reverts each one to “{SIGNED_BY_PARTICIPANT}”, so the approvals
            and the countersignature all have to run again.
          </>
        }
        preserves={
          <>
            Kept: the participant&apos;s signature and timestamps, the approval
            history, and every PDF already stored in S3.
          </>
        }
        consequences={[
          `Each agreement loses its ERM countersignature and drops back to “${SIGNED_BY_PARTICIPANT}”. The ERM has to re-send it for approval, the approvers have to decide again, and the ERM has to countersign again.`,
          "Participants see this. An agreement they were told was executed will show as not yet countersigned by Sage until the whole chain has been re-run.",
          "The participant's own signature, the uploads and the approval notes are not touched. Previously signed PDFs stay in S3 but stop being the current version.",
        ]}
      />

      <BackfillAction
        title="Regenerate executed agreements"
        verb="Regenerate"
        Icon={RefreshCw}
        run={webAdminRegenerateCompletedAgreements}
        resultKey="regenerated"
        description={
          <>
            Re-renders every agreement in “{EXECUTED}” from the template that is
            live right now, overwrites its stored PDFs and recomputes the
            SHA-256 hash. Statuses are left alone.
          </>
        }
        preserves={
          <>
            Use this to push a template correction into records that are already
            signed. Signatures and approval history are unchanged.
          </>
        }
        consequences={[
          "Each executed agreement is re-rendered from the current template and its stored PDFs are overwritten in place. The old files are not kept.",
          "There is no undo. If the live template is wrong, this propagates that error into every executed record at once.",
        ]}
      />
    </div>
  );
}

function BackfillAction({
  title,
  description,
  preserves,
  verb,
  consequences,
  Icon,
  run,
  resultKey,
}: {
  title: string;
  description: ReactNode;
  /** Short line under the button: what this action deliberately does NOT touch. */
  preserves: ReactNode;
  /** Drives the button label AND the typed confirmation, so they can't disagree. */
  verb: "Revoke" | "Regenerate";
  /** Plain-language bullets shown in the confirmation modal. */
  consequences: readonly string[];
  Icon: LucideIcon;
  run: (dryRun: boolean) => Promise<WebAdminBackfillSummary>;
  resultKey: BackfillResultKey;
}) {
  const [previewing, setPreviewing] = useState(false);
  const [executing, setExecuting] = useState(false);
  // Holding the dry-run summary is what opens the modal — the confirmation can
  // never be shown without a real, freshly-counted `matched` behind it.
  const [preview, setPreview] = useState<WebAdminBackfillSummary | null>(null);
  const [typed, setTyped] = useState("");
  const [feedback, setFeedback] = useState<Feedback | null>(null);

  const confirmWord = verb.toUpperCase();
  // Trimmed because a pasted value picks up stray whitespace, but otherwise
  // exact: the casing has to match, which is the point of the gate.
  const armed = typed.trim() === confirmWord;

  const closeModal = () => {
    if (executing) return;
    setPreview(null);
    setTyped("");
  };

  const startPreview = async () => {
    setPreviewing(true);
    setFeedback(null);
    try {
      const dry = await run(true);
      if (dry.matched === 0) {
        // No point arming a destructive button for a no-op.
        setFeedback({
          tone: "info",
          text: `Nothing to do — no agreements are in “${EXECUTED}”.`,
        });
        return;
      }
      setTyped("");
      setPreview(dry);
    } catch (e) {
      setFeedback({
        tone: "error",
        text: e instanceof Error ? e.message : "Couldn't preview the affected agreements.",
      });
    } finally {
      setPreviewing(false);
    }
  };

  const execute = async () => {
    if (!preview || !armed) return;
    setExecuting(true);
    try {
      const summary = await run(false);
      // The endpoints report their own verb-specific count; `processed` is the
      // fallback so a summary that omits it still reports something truthful.
      const done = summary[resultKey] ?? summary.processed;
      const failedPart = summary.failed ? `, ${summary.failed} failed` : "";
      const firstError = summary.errors.length
        ? ` First error: ${summary.errors[0]}`
        : "";
      setFeedback({
        tone: summary.failed ? "error" : "success",
        text: `Done — ${done} of ${summary.matched} processed${failedPart}.${firstError}`,
      });
      setPreview(null);
      setTyped("");
    } catch (e) {
      // Close on failure rather than leaving the button armed: the run may have
      // got part-way through server-side, so the count just confirmed is stale
      // and a retry has to start from a fresh dry run.
      setFeedback({
        tone: "error",
        text: e instanceof Error ? e.message : `Couldn't ${verb.toLowerCase()} the agreements.`,
      });
      setPreview(null);
      setTyped("");
    } finally {
      setExecuting(false);
    }
  };

  return (
    <SectionCard title={title} description={description} tone="danger">
      <div className="space-y-3">
        <button
          type="button"
          onClick={() => void startPreview()}
          disabled={previewing || executing}
          className={BTN_DANGER}
        >
          {previewing ? (
            <Loader2 size={12} className="animate-spin" />
          ) : (
            <Icon size={12} />
          )}
          {previewing ? "Counting…" : "Preview affected agreements"}
        </button>

        <p className="text-[11px] text-gray-500 leading-relaxed">{preserves}</p>

        {feedback && (
          <InlineAlert tone={feedback.tone} onDismiss={() => setFeedback(null)}>
            {feedback.text}
          </InlineAlert>
        )}
      </div>

      {preview && (
        <Modal
          title={title}
          subtitle="This cannot be undone"
          onClose={closeModal}
          disabled={executing}
          width="lg"
        >
          <div className="space-y-3">
            <div className="rounded-xl border border-red-200 bg-red-50 px-3 py-2.5">
              <p className="text-[10px] uppercase tracking-wider font-semibold text-red-700">
                Agreements affected
              </p>
              <p className="mt-1 text-2xl font-bold text-red-700 tabular-nums leading-none">
                {preview.matched}
              </p>
              <p className="mt-1.5 text-[11px] text-red-700/80">
                Counted by a dry run a moment ago. Nothing has changed yet.
              </p>
            </div>

            <ul className="space-y-1.5">
              {consequences.map((line) => (
                <li key={line} className="flex gap-2 text-[12px] text-gray-600 leading-relaxed">
                  <span aria-hidden className="text-red-400">
                    •
                  </span>
                  <span>{line}</span>
                </li>
              ))}
            </ul>

            <ModalField
              label={`Type ${confirmWord} to confirm`}
              required
              hint={`Case-sensitive. The ${verb.toLowerCase()} button stays disabled until it matches exactly.`}
            >
              <input
                className={modalInput}
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
                disabled={executing}
                placeholder={confirmWord}
                autoFocus
                autoComplete="off"
                autoCorrect="off"
                autoCapitalize="off"
                spellCheck={false}
                aria-label={`Type ${confirmWord} to confirm`}
              />
            </ModalField>

            <ModalActions
              onClose={closeModal}
              onSubmit={() => void execute()}
              submitting={executing}
              submitLabel={`${verb} ${preview.matched} agreement${preview.matched === 1 ? "" : "s"}`}
              canSubmit={armed && !executing}
              danger
            />
          </div>
        </Modal>
      )}
    </SectionCard>
  );
}
