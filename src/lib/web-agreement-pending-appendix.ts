/**
 * The website agreement's copy of src/lib/pending-appendix.ts, under the
 * same export names, so the website's ERM list and the approver's "All
 * agreements" table only change their import path. Kept separate so a
 * change here never reaches the console (edit the console's own file for
 * the console).
 *
 * Derives the "Pending appendix" column from the real per-appendix state
 * across all five appendices, with no dependence on the headline status (a
 * Phase 1 "Fully executed" must not hide unsent Phase 2 appendices):
 *   - sent   ⇐ requireAppendixN  (the ERM included it)
 *   - signed ⇐ affirmedAppendixN (the participant signed it)
 *   - done   = sent AND signed   → not pending
 *   - "not-sent" = !sent
 *   - "awaiting" = sent && !signed
 * The console treats a never-issued DRAFT as "nothing sent yet"; a website
 * agreement is never a DRAFT, so that check never applies here but is kept
 * to match. "None" shows only when all five are sent and signed.
 */

/** The fields of a WebAgreement this derivation reads. */
export interface PendingAppendixInput {
  status?: string | null;
  requireAppendix1?: boolean | null;
  requireAppendix2?: boolean | null;
  requireAppendix3?: boolean | null;
  requireAppendix4?: boolean | null;
  requireAppendix5?: boolean | null;
  affirmedAppendix1?: boolean | null;
  affirmedAppendix2?: boolean | null;
  affirmedAppendix3?: boolean | null;
  affirmedAppendix4?: boolean | null;
  affirmedAppendix5?: boolean | null;
}

export interface PendingAppendix {
  n: number;
  label: string;
  state: "not-sent" | "awaiting";
}

export const APPENDIX_LABELS: Record<number, string> = {
  1: "Appendix 1 — Employment Confirmation",
  2: "Appendix 2 — ACH Authorization",
  3: "Appendix 3 — Background Check",
  4: "Appendix 4 — Portal Access",
  5: "Appendix 5 — Security Cheque",
};

export function computePendingAppendices(
  app: PendingAppendixInput,
): PendingAppendix[] {
  const required: Record<number, boolean> = {
    1: app.requireAppendix1 === true,
    2: app.requireAppendix2 === true,
    3: app.requireAppendix3 === true,
    4: app.requireAppendix4 === true,
    5: app.requireAppendix5 === true,
  };
  const affirmed: Record<number, boolean> = {
    1: app.affirmedAppendix1 === true,
    2: app.affirmedAppendix2 === true,
    3: app.affirmedAppendix3 === true,
    4: app.affirmedAppendix4 === true,
    5: app.affirmedAppendix5 === true,
  };
  const isDraft = app.status === "DRAFT";
  const pending: PendingAppendix[] = [];
  for (let n = 1; n <= 5; n++) {
    const sent = required[n] && !isDraft;
    const signed = affirmed[n];
    if (sent && signed) continue; // sent AND signed: done
    pending.push({
      n,
      label: APPENDIX_LABELS[n],
      state: sent ? "awaiting" : "not-sent",
    });
  }
  return pending;
}
