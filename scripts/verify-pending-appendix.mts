// Build X verification — runs the REAL pending-appendix derivation against
// every VERIFY scenario. Pure function, no DB/server needed.
//   Run:  node scripts/verify-pending-appendix.mts
// Node ≥ 23.6 runs .mts directly via type-stripping (this repo: Node 24).

import {
  computePendingAppendices,
  type PendingAppendixInput,
} from "../src/lib/pending-appendix.ts";

// The buggy Build W derivation, inlined verbatim, to prove the fix changes
// the screenshot agreement from "None" → 3 pending.
function oldBuildW(app: PendingAppendixInput): { n: number; state: string }[] {
  const required: Record<number, boolean> = {
    1: app.requireAppendix1 === true, 2: app.requireAppendix2 === true,
    3: app.requireAppendix3 === true, 4: app.requireAppendix4 === true,
    5: app.requireAppendix5 === true,
  };
  const affirmed: Record<number, boolean> = {
    1: app.affirmedAppendix1 === true, 2: app.affirmedAppendix2 === true,
    3: app.affirmedAppendix3 === true, 4: app.affirmedAppendix4 === true,
    5: app.affirmedAppendix5 === true,
  };
  const isDraft = app.status === "DRAFT";
  const phase = 1; // screenshot agreement is Phase 1 (Accounts N/A)
  const out: { n: number; state: string }[] = [];
  for (let n = 1; n <= 5; n++) {
    if (!required[n]) continue;
    if (affirmed[n]) continue;
    const sent = !isDraft && (n === 1 ? phase >= 2 : true);
    out.push({ n, state: sent ? "awaiting" : "not-sent" });
  }
  return out;
}

let failures = 0;
function check(name: string, cond: boolean, detail: string) {
  const tag = cond ? "PASS" : "FAIL";
  if (!cond) failures++;
  console.log(`  [${tag}] ${name}${detail ? ` — ${detail}` : ""}`);
}
function summary(app: PendingAppendixInput) {
  const p = computePendingAppendices(app);
  if (p.length === 0) return "None";
  return p.map((x) => `A${x.n}:${x.state}`).join(", ");
}

console.log("\n=== Build X — Pending Appendix derivation ===\n");

// ── Scenario 1: the screenshot agreement (Phase 1 COMPLETED, 3 pending) ──
// Required + affirmed in Phase 1: Appendix 2, 3 (done). Not-required (unsent
// Phase-2 / optional): Appendix 1, 4, 5 → must read pending "Not sent".
const screenshot: PendingAppendixInput = {
  status: "COMPLETED",
  requireAppendix2: true, affirmedAppendix2: true,
  requireAppendix3: true, affirmedAppendix3: true,
  // 1, 4, 5 not-required, not affirmed
};
console.log("Scenario 1 — screenshot agreement (Phase 1 COMPLETED):");
console.log(`    OLD (Build W): ${oldBuildW(screenshot).length === 0 ? "None" : oldBuildW(screenshot).map((x) => `A${x.n}:${x.state}`).join(", ")}`);
console.log(`    NEW (Build X): ${summary(screenshot)}`);
const s1 = computePendingAppendices(screenshot);
check("old logic wrongly returned None", oldBuildW(screenshot).length === 0, "the bug");
check("new logic returns exactly 3 pending", s1.length === 3, `got ${s1.length}`);
check("new logic is NOT None", s1.length > 0, "");
check("the 3 pending are A1, A4, A5 all 'not-sent'",
  s1.every((x) => x.state === "not-sent") && [1, 4, 5].every((n) => s1.some((x) => x.n === n)),
  summary(screenshot));

// ── Scenario 2: Phase-1 completed, unsent Phase 2 appendices → "Not sent" ──
const phase1NoneRequired: PendingAppendixInput = { status: "COMPLETED" };
console.log("\nScenario 2 — Phase-1 completed, nothing required yet:");
console.log(`    NEW (Build X): ${summary(phase1NoneRequired)}`);
const s2 = computePendingAppendices(phase1NoneRequired);
check("all 5 appendices read 'Not sent'",
  s2.length === 5 && s2.every((x) => x.state === "not-sent"), summary(phase1NoneRequired));

// ── Scenario 3: sent-but-unsigned appendix → "Awaiting signature" ──
const sentUnsigned: PendingAppendixInput = {
  status: "SUBMITTED",
  requireAppendix2: true, affirmedAppendix2: false,
};
console.log("\nScenario 3 — Appendix 2 required + sent but unsigned:");
console.log(`    NEW (Build X): ${summary(sentUnsigned)}`);
const s3 = computePendingAppendices(sentUnsigned);
const a2 = s3.find((x) => x.n === 2);
check("Appendix 2 reads 'Awaiting signature'", a2?.state === "awaiting", `got ${a2?.state}`);

// ── Scenario 4: every appendix sent + signed → "None" ──
const allDone: PendingAppendixInput = {
  status: "COMPLETED",
  requireAppendix1: true, affirmedAppendix1: true,
  requireAppendix2: true, affirmedAppendix2: true,
  requireAppendix3: true, affirmedAppendix3: true,
  requireAppendix4: true, affirmedAppendix4: true,
  requireAppendix5: true, affirmedAppendix5: true,
};
console.log("\nScenario 4 — all five required + affirmed:");
console.log(`    NEW (Build X): ${summary(allDone)}`);
check("returns None (no pending)", computePendingAppendices(allDone).length === 0, summary(allDone));

// ── Scenario 5: DRAFT (link never issued) → everything "Not sent" ──
const draft: PendingAppendixInput = {
  status: "DRAFT",
  requireAppendix1: true, requireAppendix2: true, requireAppendix3: true,
};
console.log("\nScenario 5 — DRAFT with required appendices (not yet issued):");
console.log(`    NEW (Build X): ${summary(draft)}`);
const s5 = computePendingAppendices(draft);
check("required-but-draft appendices read 'Not sent'",
  s5.filter((x) => [1, 2, 3].includes(x.n)).every((x) => x.state === "not-sent"),
  summary(draft));

console.log(`\n=== ${failures === 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED"} ===\n`);
process.exit(failures === 0 ? 0 : 1);
