import type { AgreementRequestStatus, ProfileCompletion } from "./api";

/**
 * The participant's part of the agreement is done once they've signed and
 * sent it (their ERM is checking it, has verified it, or it moved on). An
 * ERM change request makes it their turn again.
 */
export function agreementPartDone(state: AgreementRequestStatus | null): boolean {
  const ag = state?.agreement ?? null;
  return ag != null && (ag.executed || (ag.step >= 2 && !ag.yourTurn));
}

/**
 * What the "Complete Your Profile" list shows: the profile steps plus the
 * agreement row after the consent. The sidebar badge and the banner use the
 * same numbers, so they never disagree. (Unlocking the dashboard still
 * follows the profile steps alone.)
 */
export function shownProgress(completion: ProfileCompletion, agreement: AgreementRequestStatus | null) {
  const hasAgreementRow = completion.steps.some((s) => s.key === "AGREEMENT");
  const total = completion.totalSteps + (hasAgreementRow ? 1 : 0);
  const done = completion.completedSteps + (hasAgreementRow && agreementPartDone(agreement) ? 1 : 0);
  const pct = total > 0 ? Math.round((done * 100) / total) : 0;
  return { done, total, pct };
}
