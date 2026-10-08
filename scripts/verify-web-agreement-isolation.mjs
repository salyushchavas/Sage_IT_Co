// Website agreement isolation check: no website agreement file uses the
// console's (/agreements, /consultant) frontend code, neither its folders and
// lib modules nor the console's part of src/lib/api.ts. The backend's own
// check is WebAgreementIsolationGuardTest; this is the frontend's.
//   Run:  node scripts/verify-web-agreement-isolation.mjs
// Reads files only. Exits 1 and lists every use it finds.

import fs from "node:fs";
import path from "node:path";

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const rel = (p) => path.relative(ROOT, p);

/** The website agreement's own files. */
const WEBSITE = [
  "src/components/web-agreement",
  "src/app/approver-dashboard",
  "src/app/dashboard/agreement",
  "src/components/dashboard/MasterAgreementStep.tsx",
  "src/components/dashboard/tabs/AgreementTab.tsx",
];

/** The console's frontend: folders and lib modules (import paths, "@/" = src/). */
const CONSOLE_PATHS = [
  "src/app/agreements",
  "src/app/consultant",
  "src/components/agreement-erm",
  "src/components/consultant",
  "src/components/admin-console",
  "src/lib/agreement-sections",
  "src/lib/agreement-status",
  "src/lib/pending-appendix",
];

function walk(p) {
  const abs = path.join(ROOT, p);
  if (!fs.existsSync(abs)) return [];
  if (fs.statSync(abs).isFile()) return [abs];
  return fs.readdirSync(abs, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? walk(path.join(p, e.name)) : /\.(ts|tsx|mts|js|mjs)$/.test(e.name) ? [path.join(abs, e.name)] : []);
}

const websiteFiles = [
  ...WEBSITE.flatMap(walk),
  ...fs.readdirSync(path.join(ROOT, "src/lib"))
    .filter((n) => /^web-agreement-.*\.ts$/.test(n))
    .map((n) => path.join(ROOT, "src/lib", n)),
];

/** Every module specifier a file imports or re-exports, resolved to a repo path when it is ours. */
function imports(file) {
  const src = fs.readFileSync(file, "utf8");
  const out = [];
  const re = /(?:import|export)\s+(type\s+)?(?:([\s\S]*?)\s+from\s+)?["']([^"']+)["']|import\(\s*["']([^"']+)["']\s*\)/g;
  for (const m of src.matchAll(re)) {
    const spec = m[3] ?? m[4];
    let resolved = null;
    if (spec.startsWith("@/")) resolved = path.join("src", spec.slice(2));
    else if (spec.startsWith(".")) resolved = rel(path.resolve(path.dirname(file), spec));
    out.push({ spec, resolved, names: m[2] ?? "" });
  }
  return out;
}

const isConsolePath = (p) => p != null && CONSOLE_PATHS.some((c) => p === c || p.startsWith(c + "/") || p.startsWith(c + "."));

// The console's part of api.ts: every top-level name declared from its
// section marker to the next top-level marker.
const apiFile = path.join(ROOT, "src/lib/api.ts");
const api = fs.readFileSync(apiFile, "utf8").split("\n");
const start = api.findIndex((l) => /^\/\/ ─── Agreement-ERM \+ Consultant/.test(l));
const end = api.findIndex((l, i) => i > start && /^\/\/ ─── /.test(l));
const websiteStart = api.findIndex((l) => /^\/\/ ─── Website agreement/.test(l));
if (start < 0 || end < 0 || websiteStart < 0) {
  console.error("api.ts section markers not found; update this check.");
  process.exit(2);
}
const consoleNames = new Set();
for (let i = start; i < end; i++) {
  const m = api[i].match(/^(?:export\s+)?(?:default\s+)?(?:async\s+)?(?:function\*?|const|let|var|interface|type|class|enum)\s+([A-Za-z0-9_$]+)/);
  if (m) consoleNames.add(m[1]);
}

const problems = [];

for (const file of websiteFiles) {
  for (const { spec, resolved, names } of imports(file)) {
    if (isConsolePath(resolved)) problems.push(`${rel(file)}: imports the console's ${spec}`);
    if (resolved === "src/lib/api") {
      for (let n of names.replace(/[{}]/g, "").split(",")) {
        n = n.trim().replace(/^type\s+/, "").split(/\s+as\s+/)[0].trim();
        if (consoleNames.has(n)) problems.push(`${rel(file)}: imports the console's ${n} from @/lib/api`);
      }
    }
  }
}

// The website section of api.ts itself, comments left out.
const websiteCode = api.slice(websiteStart).join("\n")
  .replace(/\/\*[\s\S]*?\*\//g, "")
  .replace(/(^|[^:])\/\/.*$/gm, "$1");
for (const n of consoleNames) {
  if (new RegExp(`(?<![A-Za-z0-9_$])${n.replace(/\$/g, "\\$")}(?![A-Za-z0-9_$])`).test(websiteCode)) {
    problems.push(`src/lib/api.ts (website agreement section): uses the console's ${n}`);
  }
}

// Not blind: the console's own files are found importing its modules.
const consoleList = path.join(ROOT, "src/components/agreement-erm/ConsultantsListView.tsx");
const seesConsole = fs.existsSync(consoleList) && imports(consoleList).some((i) => isConsolePath(i.resolved));
if (websiteFiles.length < 30 || consoleNames.size < 50 || !seesConsole) {
  console.error(`The check found too little (website files ${websiteFiles.length}, console names ${consoleNames.size}, `
    + `console import seen ${seesConsole}); update it.`);
  process.exit(2);
}

if (problems.length) {
  console.error(`Website agreement code uses the console's code (${problems.length}):`);
  for (const p of problems) console.error("  " + p);
  process.exit(1);
}
console.log(`ok: ${websiteFiles.length} website agreement files and the website part of api.ts use none of the `
  + `console's ${CONSOLE_PATHS.length} folders/modules or ${consoleNames.size} api.ts names`);
