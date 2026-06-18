// ── Build X — Pending Appendix derivation (pure, testable) ───────────
//
// Extracted from ConsultantsListView so the derivation can be unit-tested
// in isolation (see scripts/verify-pending-appendix.mts). The ERM "All"
// dashboard column renders straight from this.
//
// Build W shipped this required-only, which masked genuinely-pending
// appendices: a Phase-1 COMPLETED agreement skipped its Phase-2 appendices
// (not yet require…=true) and read "None" despite unsent appendices.
//
// Build X derives from the REAL per-appendix state across the FULL defined
// set (Appendix 1–5), with NO dependence on the headline agreement status
// (a Phase-1 "Completed" must not mask pending Phase-2 appendices):
//   - sent   ⇐ requireAppendixN  (the ERM included/sent it; the only signal)
//   - signed ⇐ affirmedAppendixN (the consultant signed/completed it)
//   - done   = sent AND signed   → not pending
//   - "not-sent" = !sent  (not-required appendices included — they read here)
//   - "awaiting" = sent && !signed
// Status is used ONLY to treat a never-issued DRAFT as "nothing sent yet";
// it is never used to short-circuit to "None". "None" shows only when every
// one of the five appendices is sent + signed.

/** Structural subset of a ConsultantApplication this derivation reads. */
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
  // DRAFT = the consultant link was never issued → nothing is sent yet. This
  // is the ONLY status use; COMPLETED/SIGNED/etc. never mask pending here.
  const isDraft = app.status === "DRAFT";
  const pending: PendingAppendix[] = [];
  for (let n = 1; n <= 5; n++) {
    const sent = required[n] && !isDraft;
    const signed = affirmed[n];
    if (sent && signed) continue; // genuinely done — sent AND signed
    pending.push({
      n,
      label: APPENDIX_LABELS[n],
      state: sent ? "awaiting" : "not-sent",
    });
  }
  return pending;
}
