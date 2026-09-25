"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AlertCircle,
  Briefcase,
  CheckCircle2,
  ClipboardList,
  Download,
  FileText,
  GraduationCap,
  Loader2,
  MessageSquare,
  Send,
  Settings,
  Target,
  Users,
} from "lucide-react";
import { formatDateMedium, formatDateTime } from "@/lib/datetime";

import {
  RoleDashboardShell,
  type RoleDashboardTab,
} from "@/components/dashboard/RoleDashboardShell";
import { useAuth } from "@/lib/auth-context";
import {
  addErmNote,
  approvePhase1,
  downloadSignedAgreement,
  getErmParticipantDetail,
  getErmPendingEmployment,
  getErmPendingPhaseApprovals,
  getErmReports,
  getErmRoster,
  markErmAgreementReviewed,
  returnEmployment,
  reviewErmReport,
  verifyEmployment,
  viewErmOfferDocument,
  type ErmPendingEmploymentRow,
  type ErmPendingPhaseRow,
  type ErmReportRow,
  type ErmRosterRow,
  type WeeklyReportDTO,
} from "@/lib/api";

/**
 * ERM dashboard. Eight tabs:
 *
 *   home       -- assigned participant roster + drill-in detail panel
 *   reports    -- weekly reports across all assigned participants,
 *                 with inline review (add notes + mark reviewed)
 *   comms      -- communication log per participant, free-text notes
 *                 with an escalation toggle
 *   interviews -- interview milestones (cross-cut view of reports)
 *   employment -- pending offer verification queue
 *   phase1     -- pending Phase 1 acknowledgment approval queue
 *   coaches    -- coach assignments per participant (read-only)
 *   profile    -- link to the participant /profile editor
 */

type TabId =
  | "home"
  | "reports"
  | "comms"
  | "interviews"
  | "employment"
  | "phase1"
  | "coaches"
  | "profile";

const TABS: ReadonlyArray<RoleDashboardTab> = [
  { id: "home",       label: "My Participants",  Icon: Users },
  { id: "reports",    label: "Weekly Reports",   Icon: ClipboardList },
  { id: "comms",      label: "Communications",   Icon: MessageSquare },
  { id: "interviews", label: "Interviews",       Icon: Target },
  { id: "employment", label: "Employment",       Icon: Briefcase },
  { id: "phase1",     label: "Phase 1",          Icon: CheckCircle2 },
  { id: "coaches",    label: "Coaches",          Icon: GraduationCap },
  { id: "profile",    label: "Profile",          Icon: Settings },
];

export default function ErmDashboardPage() {
  const router = useRouter();
  const { user, isLoading } = useAuth();
  const [active, setActive] = useState<TabId>("home");
  // ?tab=<id>: emails link straight to a tab (e.g. employment to verify).
  useEffect(() => {
    const tab = new URLSearchParams(window.location.search).get("tab");
    if (tab && TABS.some((t) => t.id === tab)) setActive(tab as TabId);
  }, []);
  const [roster, setRoster] = useState<ErmRosterRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.replace("/login");
      return;
    }
    if ((user.role ?? "").toUpperCase() !== "ERM") {
      router.replace("/dashboard");
      return;
    }
    let cancelled = false;
    getErmRoster()
      .then((r) => {
        if (!cancelled) setRoster(r);
      })
      .catch((e) => {
        if (!cancelled)
          setError(e instanceof Error ? e.message : "Couldn't load roster");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isLoading, user, router]);

  if (isLoading || loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <RoleDashboardShell
      title="ERM Dashboard"
      tabs={TABS}
      active={active}
      onSelect={(id) => setActive(id as TabId)}
    >
      {error && (
        <p className="mb-4 inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      {active === "home" && <RosterTab roster={roster} />}
      {active === "reports" && <ReportsTab />}
      {active === "comms" && <CommsTab roster={roster} />}
      {active === "interviews" && <InterviewsTab />}
      {active === "employment" && <EmploymentTab />}
      {active === "phase1" && <Phase1Tab />}
      {active === "coaches" && <CoachesTab roster={roster} />}
      {active === "profile" && (
        <Placeholder
          title="Profile"
          copy="Edit your ERM profile from the standard profile page."
          link={{ label: "Open profile", href: "/dashboard?tab=profile" }}
        />
      )}
    </RoleDashboardShell>
  );
}

/* ── Roster + detail ─────────────────────────────────────────── */

function RosterTab({ roster }: { roster: ErmRosterRow[] }) {
  const [openId, setOpenId] = useState<number | null>(null);
  const [detail, setDetail] = useState<Record<string, unknown> | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const openParticipant = async (id: number) => {
    setOpenId(id);
    setDetailLoading(true);
    try {
      setDetail(await getErmParticipantDetail(id));
    } catch {
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  };

  // The "signed agreement ready for review" email links here with
  // ?participant=<id>: open that participant straight away.
  useEffect(() => {
    const id = Number(new URLSearchParams(window.location.search).get("participant"));
    if (id && roster.some((r) => r.userId === id)) openParticipant(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roster]);

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">My participants</h1>
      <p className="text-sm text-gray-500">
        Every participant on your caseload. Click a row to see the full
        profile, roadmap status, documents, agreement state, and weekly
        reports.
      </p>

      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Name</th>
              <th className="text-left px-4 py-2">ID</th>
              <th className="text-left px-4 py-2">Program</th>
              <th className="text-left px-4 py-2">Technology</th>
              <th className="text-left px-4 py-2">Status</th>
              <th className="text-left px-4 py-2">Last activity</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {roster.length === 0 ? (
              <tr>
                <td
                  colSpan={6}
                  className="px-4 py-6 text-center text-sm text-gray-400 italic"
                >
                  No participants assigned yet.
                </td>
              </tr>
            ) : (
              roster.map((r) => (
                <tr
                  key={r.userId}
                  onClick={() => openParticipant(r.userId)}
                  className="hover:bg-gray-50 cursor-pointer"
                >
                  <td className="px-4 py-2 font-medium text-gray-900">
                    {r.fullName ?? "—"}
                    {r.agreementToReview && (
                      <span className="ml-2 px-2 py-0.5 rounded-full text-[10px] font-bold bg-amber-50 text-amber-700">
                        Agreement to review
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-gray-700">
                    {r.participantId ?? "—"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {r.program ?? "—"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {r.technology ?? "—"}
                  </td>
                  <td className="px-4 py-2">
                    <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-sage-navy/5 text-sage-navy">
                      {r.currentStatus ?? "—"}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-500">
                    {r.lastActivity
                      ? formatDateTime(r.lastActivity)
                      : "—"}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {openId && (
        <div
          className="fixed inset-0 z-40 flex items-end sm:items-center justify-center bg-black/40 p-4"
          onClick={() => {
            setOpenId(null);
            setDetail(null);
          }}
        >
          <div
            className="bg-white rounded-2xl shadow-xl max-w-3xl w-full max-h-[85vh] overflow-y-auto p-6"
            onClick={(e) => e.stopPropagation()}
          >
            {detailLoading ? (
              <div className="text-center py-10">
                <Loader2
                  size={20}
                  className="animate-spin text-sage-navy inline"
                />
              </div>
            ) : (
              <DetailPanel
                detail={detail}
                onClose={() => {
                  setOpenId(null);
                  setDetail(null);
                }}
                onChanged={() => openId && openParticipant(openId)}
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function DetailPanel({
  detail,
  onClose,
  onChanged,
}: {
  detail: Record<string, unknown> | null;
  onClose: () => void;
  onChanged: () => void;
}) {
  if (!detail)
    return (
      <p className="text-sm text-gray-500">Couldn&apos;t load details.</p>
    );
  const program = detail.program as Record<string, string | null> | undefined;
  const agreement = detail.agreement as Record<string, string | boolean> | undefined;
  const documents =
    (detail.documents as Array<Record<string, unknown>>) ?? [];
  const reports = (detail.reports as Array<Record<string, unknown>>) ?? [];
  const coaches = (detail.coaches as Array<Record<string, string>>) ?? [];
  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-xl font-bold text-gray-900">
            {(detail.fullName as string) ?? "Participant"}
          </h2>
          <p className="text-xs text-gray-500 font-mono">
            {(detail.participantId as string) ?? "—"}
          </p>
          <p className="text-xs text-gray-500 mt-0.5">
            {(detail.email as string) ?? "—"}
          </p>
        </div>
        <button
          onClick={onClose}
          className="text-xs text-gray-500 hover:text-red-700 cursor-pointer"
        >
          Close
        </button>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
        <SmallStat label="Status" value={(detail.currentStatus as string) ?? "—"} />
        <SmallStat label="Phone" value={(detail.phone as string) ?? "—"} />
        <SmallStat label="Program" value={program?.program ?? "—"} />
        <SmallStat label="Phase" value={program?.phase ?? "—"} />
        <SmallStat label="Skillset" value={program?.skillset ?? "—"} />
        <SmallStat label="Target role" value={program?.targetJobTitle ?? "—"} />
        <SmallStat label="Availability" value={program?.availability ?? "—"} />
        <SmallStat label="Agreement" value={String(agreement?.status ?? "—")} />
      </div>

      {agreement?.signed === true && (
        <SignedAgreementBlock
          participantUserId={Number(detail.userId)}
          acceptedAt={String(agreement.acceptedAt ?? "")}
          version={String(agreement.version ?? "")}
          reviewedAt={String(agreement.reviewedAt ?? "")}
          onChanged={onChanged}
        />
      )}

      <DetailBlock title={`Documents (${documents.length})`}>
        {documents.length === 0 ? (
          <p className="text-xs text-gray-400 italic">No documents.</p>
        ) : (
          <ul className="text-xs space-y-1">
            {documents.map((d, idx) => (
              <li key={idx} className="flex items-center gap-2">
                <FileText size={11} className="text-gray-400" />
                <span className="flex-1">{String(d.documentType)}</span>
                <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-gray-100 text-gray-700">
                  {String(d.reviewStatus)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </DetailBlock>

      <DetailBlock title={`Weekly reports (${reports.length})`}>
        {reports.length === 0 ? (
          <p className="text-xs text-gray-400 italic">
            No reports submitted yet.
          </p>
        ) : (
          <ul className="text-xs space-y-1">
            {reports.slice(0, 5).map((r, idx) => (
              <li key={idx} className="flex items-center gap-2">
                <span className="font-mono">
                  {String(r.weekStart)} – {String(r.weekEnd)}
                </span>
                <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-gray-100 text-gray-700 ml-auto">
                  {String(r.status)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </DetailBlock>

      <DetailBlock title="Coach team">
        {coaches.length === 0 ? (
          <p className="text-xs text-gray-400 italic">No coaches assigned.</p>
        ) : (
          <ul className="text-xs space-y-1">
            {coaches.map((c, idx) => (
              <li key={idx} className="flex items-center gap-2">
                <span className="font-semibold">{c.coachRole}</span>
                <span className="text-gray-700">
                  — {c.name || "Unassigned"}
                </span>
                {c.email && (
                  <span className="text-gray-400 ml-auto">{c.email}</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </DetailBlock>

      <DetailBlock title="Communication notes">
        <pre className="text-xs whitespace-pre-wrap text-gray-700">
          {(detail.communicationNotes as string) || "—"}
        </pre>
      </DetailBlock>
    </div>
  );
}

/**
 * Checklist 2.3 (roadmap step 10): the ERM opens the participant's signed
 * agreement and confirms they reviewed it.
 */
function SignedAgreementBlock({
  participantUserId,
  acceptedAt,
  version,
  reviewedAt,
  onChanged,
}: {
  participantUserId: number;
  acceptedAt: string;
  version: string;
  reviewedAt: string;
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState<"download" | "review" | null>(null);
  const [error, setError] = useState("");
  const fmt = (iso: string) =>
    iso ? new Date(iso).toLocaleDateString("en-US", { dateStyle: "medium" }) : "—";
  const run = async (what: "download" | "review") => {
    setBusy(what);
    setError("");
    try {
      if (what === "download") await downloadSignedAgreement(participantUserId);
      else {
        await markErmAgreementReviewed(participantUserId);
        onChanged();
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Something went wrong");
    } finally {
      setBusy(null);
    }
  };
  return (
    <DetailBlock title="Signed agreement">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <span className="text-gray-700">
          Signed {fmt(acceptedAt)}
          {version && <span className="text-gray-500"> · {version}</span>}
        </span>
        <span className="flex-1" />
        <button
          type="button"
          onClick={() => run("download")}
          disabled={busy !== null}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy disabled:opacity-60 cursor-pointer"
        >
          {busy === "download" ? <Loader2 size={12} className="animate-spin" /> : <Download size={12} />}
          Download
        </button>
        {reviewedAt ? (
          <span className="inline-flex items-center gap-1 text-emerald-700 font-semibold">
            <CheckCircle2 size={12} /> Reviewed {fmt(reviewedAt)}
          </span>
        ) : (
          <button
            type="button"
            onClick={() => run("review")}
            disabled={busy !== null}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {busy === "review" && <Loader2 size={12} className="animate-spin" />}
            Mark reviewed
          </button>
        )}
      </div>
      {error && (
        <p className="mt-1.5 inline-flex items-center gap-1 text-[11px] text-red-600">
          <AlertCircle size={11} /> {error}
        </p>
      )}
    </DetailBlock>
  );
}

function SmallStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg bg-gray-50 border border-gray-100 px-2 py-1.5">
      <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">
        {label}
      </p>
      <p className="font-medium text-gray-800 truncate">{value}</p>
    </div>
  );
}

function DetailBlock({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-1.5">
        {title}
      </p>
      <div className="rounded-xl border border-gray-100 bg-gray-50/60 p-3">
        {children}
      </div>
    </div>
  );
}

/* ── Reports tab ─────────────────────────────────────────────── */

/**
 * Checklist 4.1: the ERM's weekly reports — each participant's name and
 * ID, the full report, "Needs help" and "Late" flags. Drafts aren't
 * listed, and only a submitted report can be marked reviewed.
 */
function ReportsTab() {
  const [reports, setReports] = useState<ErmReportRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<string>("ALL");
  const [openId, setOpenId] = useState<number | null>(null);
  const [notes, setNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const refresh = async () => {
    setReports(await getErmReports());
  };

  useEffect(() => {
    let cancelled = false;
    getErmReports()
      .then((r) => {
        if (!cancelled) setReports(r);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const visible = useMemo(() => {
    if (filter === "ALL") return reports;
    if (filter === "NEEDS HELP") return reports.filter((r) => r.needsHelp);
    return reports.filter((r) => r.status === filter);
  }, [reports, filter]);

  const open = reports.find((x) => x.id === openId) ?? null;

  const openReport = (id: number) => {
    const r = reports.find((x) => x.id === id);
    setOpenId(id);
    setNotes(r?.ermNotes ?? "");
    setError("");
  };

  const submitReview = async () => {
    if (!openId) return;
    setSaving(true);
    setError("");
    try {
      await reviewErmReport(openId, notes);
      await refresh();
      setOpenId(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't save the review");
    } finally {
      setSaving(false);
    }
  };

  if (loading)
    return (
      <div className="text-center py-10">
        <Loader2 size={20} className="animate-spin text-sage-navy inline" />
      </div>
    );

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <h1 className="text-2xl font-bold text-gray-900">Weekly reports</h1>
        <div className="inline-flex rounded-lg border border-gray-200 bg-gray-50 p-1 text-xs">
          {["ALL", "NEEDS HELP", "SUBMITTED", "OVERDUE", "REVIEWED"].map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => setFilter(s)}
              className={
                "px-2.5 py-1 rounded-md font-semibold cursor-pointer " +
                (filter === s
                  ? "bg-sage-navy text-white"
                  : "text-gray-600 hover:text-sage-navy")
              }
            >
              {s}
            </button>
          ))}
        </div>
      </div>

      <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden divide-y divide-gray-100">
        {visible.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-gray-400 italic">
            No reports match this filter.
          </p>
        ) : (
          visible.map((r) => (
            <div
              key={r.id}
              className="px-4 py-3 flex items-center gap-3 text-sm hover:bg-gray-50 flex-wrap"
            >
              <span className="w-44 shrink-0">
                <span className="block font-medium text-gray-900 truncate">{r.participantName ?? "—"}</span>
                <span className="block font-mono text-[10px] text-gray-400">{r.participantId ?? ""}</span>
              </span>
              <span className="font-mono text-xs text-gray-700 w-44 shrink-0">
                {r.weekStart} – {r.weekEnd}
              </span>
              <span
                className={
                  "px-2 py-0.5 rounded-full text-[10px] font-bold " +
                  (r.status === "SUBMITTED"
                    ? "bg-emerald-50 text-emerald-700"
                    : r.status === "REVIEWED"
                      ? "bg-blue-50 text-blue-700"
                      : r.status === "OVERDUE"
                        ? "bg-red-50 text-red-700"
                        : "bg-gray-100 text-gray-600")
                }
              >
                {r.status}
              </span>
              {r.needsHelp && (
                <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-amber-50 text-amber-700">
                  NEEDS HELP
                </span>
              )}
              {r.late && r.status !== "OVERDUE" && (
                <span className="text-[10px] font-semibold text-gray-500">late</span>
              )}
              <button
                type="button"
                onClick={() => openReport(r.id)}
                className="ml-auto text-xs font-semibold text-sage-navy hover:text-sage-navy-deep cursor-pointer"
              >
                {r.status === "SUBMITTED" ? "Review" : "Open"}
              </button>
            </div>
          ))
        )}
      </div>

      {open && (
        <div
          className="fixed inset-0 z-40 flex items-center justify-center bg-black/40 p-4"
          onClick={() => setOpenId(null)}
        >
          <div
            className="bg-white rounded-2xl shadow-xl max-w-2xl w-full max-h-[85vh] overflow-y-auto p-5"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 className="text-lg font-bold text-gray-900">
              {open.participantName ?? "Participant"}{" "}
              <span className="font-mono text-xs text-gray-400">{open.participantId ?? ""}</span>
            </h2>
            <p className="text-xs text-gray-500 mt-0.5">
              Week of <span className="font-mono">{open.weekStart}</span> –{" "}
              <span className="font-mono">{open.weekEnd}</span>
              {open.dueDate && <> · due <span className="font-mono">{open.dueDate}</span></>}
              {open.submittedAt && <> · submitted {new Date(open.submittedAt).toLocaleDateString("en-US")}</>}
              {open.late && <> · late</>}
            </p>
            {open.status === "OVERDUE" ? (
              <p className="mt-4 text-sm text-gray-500 italic">Not submitted yet.</p>
            ) : (
              <ReportContent data={open.reportData} />
            )}
            {error && <p className="mt-3 text-sm text-red-700">{error}</p>}
            {open.status === "SUBMITTED" ? (
              <>
                <textarea
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  rows={4}
                  className="mt-4 w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
                  placeholder="Notes for the participant + audit trail"
                />
                <div className="mt-3 flex justify-end gap-2">
                  <button
                    type="button"
                    onClick={() => setOpenId(null)}
                    className="px-3 py-1.5 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer"
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    onClick={submitReview}
                    disabled={saving}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
                  >
                    {saving ? <Loader2 size={12} className="animate-spin" /> : <CheckCircle2 size={12} />}
                    {saving ? "Saving…" : "Mark reviewed"}
                  </button>
                </div>
              </>
            ) : (
              <div className="mt-4 flex items-end justify-between gap-3">
                <p className="text-xs text-gray-500">
                  {open.status === "REVIEWED"
                    ? `Reviewed${open.ermReviewDate ? " " + new Date(open.ermReviewDate).toLocaleDateString("en-US") : ""}${open.ermNotes ? ": " + open.ermNotes : ""}`
                    : ""}
                </p>
                <button
                  type="button"
                  onClick={() => setOpenId(null)}
                  className="px-3 py-1.5 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer"
                >
                  Close
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

/** The participant's report, grouped as they filled it in. */
function ReportContent({ data }: { data: string | null }) {
  let parsed: Record<string, unknown> = {};
  try {
    parsed = data ? JSON.parse(data) : {};
  } catch {
    parsed = {};
  }
  const jobs = Array.isArray(parsed.jobSubmissions) ? (parsed.jobSubmissions as Record<string, string>[]) : [];
  const group = (key: string) => (parsed[key] && typeof parsed[key] === "object" ? (parsed[key] as Record<string, string>) : {});
  const resume = group("resumeActivities");
  const interview = group("interviewTraining");
  const comms = group("communications");
  const rows = (entries: [string, string | undefined][]) =>
    entries.filter(([, v]) => v && String(v).trim() && v !== "false");
  const fieldList = (entries: [string, string | undefined][]) => {
    const shown = rows(entries);
    return shown.length === 0 ? (
      <p className="text-xs text-gray-400 italic">Nothing entered.</p>
    ) : (
      <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-1 text-xs">
        {shown.map(([k, v]) => (
          <div key={k}>
            <dt className="text-gray-500">{k}</dt>
            <dd className="text-gray-800 whitespace-pre-wrap">{v}</dd>
          </div>
        ))}
      </dl>
    );
  };
  return (
    <div className="mt-4 space-y-3">
      <DetailBlock title={`Job submissions (${jobs.length})`}>
        {jobs.length === 0 ? (
          <p className="text-xs text-gray-400 italic">None this week.</p>
        ) : (
          <table className="w-full text-xs">
            <thead className="text-[10px] uppercase tracking-wider text-gray-500">
              <tr>
                <th className="text-left py-1">Company</th>
                <th className="text-left py-1">Job title</th>
                <th className="text-left py-1">Portal</th>
                <th className="text-left py-1">Status</th>
                <th className="text-left py-1">Follow-up</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((j, i) => (
                <tr key={i} className="border-t border-gray-100">
                  <td className="py-1 text-gray-800">{j.company}</td>
                  <td className="py-1 text-gray-700">{j.jobTitle}</td>
                  <td className="py-1 text-gray-700">{j.portal}</td>
                  <td className="py-1 text-gray-700">{j.status}</td>
                  <td className="py-1 text-gray-700">{j.followUpDate}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </DetailBlock>
      <DetailBlock title="Resume and profile">
        {fieldList([
          ["Resume version", resume.resumeVersion],
          ["Profile updates", resume.profileUpdates],
          ["Portal updates", resume.portalUpdates],
          ["LinkedIn updates", resume.linkedinUpdates],
        ])}
      </DetailBlock>
      <DetailBlock title="Interview training">
        {fieldList([
          ["Mock interview", interview.mockDate],
          ["Topic", interview.topic],
          ["Coach", interview.coach],
          ["Feedback", interview.feedback],
          ["Improvements", interview.improvements],
          ["Next practice", interview.nextPracticeDate],
        ])}
      </DetailBlock>
      <DetailBlock title="Communications">
        {fieldList([
          ["Messages acknowledged", comms.messagesAcknowledged],
          ["Questions", comms.questions],
          ["Needs help", comms.escalation === "true" ? comms.escalationDetail || "Yes" : undefined],
        ])}
      </DetailBlock>
    </div>
  );
}

/* ── Comms tab ───────────────────────────────────────────────── */

function CommsTab({ roster }: { roster: ErmRosterRow[] }) {
  const [participantId, setParticipantId] = useState<number | "">("");
  const [note, setNote] = useState("");
  const [escalation, setEscalation] = useState(false);
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [error, setError] = useState("");

  const handleAdd = async () => {
    if (!participantId || !note.trim()) {
      setError("Pick a participant and write a note.");
      return;
    }
    setSaving(true);
    setError("");
    setFeedback("");
    try {
      await addErmNote(Number(participantId), note.trim(), escalation);
      setFeedback("Note logged.");
      setNote("");
      setEscalation(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't log note");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Communications</h1>
      <p className="text-sm text-gray-500">
        Log communication notes and escalations. Notes are timestamped and
        appended to the participant&apos;s ERM record.
      </p>

      <div className="rounded-2xl border border-gray-100 bg-white p-4 space-y-3">
        <div>
          <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
            Participant
          </label>
          <select
            value={participantId}
            onChange={(e) =>
              setParticipantId(e.target.value ? Number(e.target.value) : "")
            }
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200"
          >
            <option value="">— Pick one —</option>
            {roster.map((r) => (
              <option key={r.userId} value={r.userId}>
                {r.fullName ?? "—"} ({r.participantId ?? "—"})
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
            Note
          </label>
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={3}
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200"
            placeholder="What happened, what's the next step, who's owning it..."
          />
        </div>
        <label className="inline-flex items-center gap-2 text-sm cursor-pointer">
          <input
            type="checkbox"
            checked={escalation}
            onChange={(e) => setEscalation(e.target.checked)}
            className="w-4 h-4 rounded border-gray-300 text-red-700 focus:ring-red-500"
          />
          <span className="text-gray-700">Flag this as an escalation</span>
        </label>
        {error && (
          <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
            <AlertCircle size={14} /> {error}
          </p>
        )}
        {feedback && (
          <p className="inline-flex items-center gap-1.5 text-sm text-emerald-700">
            <CheckCircle2 size={14} /> {feedback}
          </p>
        )}
        <div className="flex justify-end">
          <button
            type="button"
            onClick={handleAdd}
            disabled={saving}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {saving ? (
              <Loader2 size={12} className="animate-spin" />
            ) : (
              <Send size={12} />
            )}
            {saving ? "Logging…" : "Log note"}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ── Employment tab ───────────────────────────────────────────── */

/**
 * Checklist 4.5: the ERM verifies a participant's employment details, or
 * sends them back with what needs correcting (the participant is emailed
 * and submits corrected details). The offer letter opens through the
 * portal; each view is recorded.
 */
function EmploymentTab() {
  const [rows, setRows] = useState<ErmPendingEmploymentRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [dialog, setDialog] = useState<{ userId: number; mode: "verify" | "return" } | null>(null);
  const [notes, setNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const refresh = async () => setRows(await getErmPendingEmployment());

  useEffect(() => {
    let cancelled = false;
    refresh()
      .catch((e) => {
        if (!cancelled)
          setError(e instanceof Error ? e.message : "Couldn't load queue");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const open = (userId: number, mode: "verify" | "return") => {
    setDialog({ userId, mode });
    setNotes("");
    setError("");
  };

  const handleSave = async () => {
    if (dialog == null) return;
    setSaving(true);
    setError("");
    try {
      if (dialog.mode === "verify") await verifyEmployment(dialog.userId, notes);
      else await returnEmployment(dialog.userId, notes.trim());
      await refresh();
      setDialog(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't save");
    } finally {
      setSaving(false);
    }
  };

  const viewOffer = async (userId: number) => {
    setError("");
    try {
      await viewErmOfferDocument(userId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't open the offer letter");
    }
  };

  if (loading)
    return (
      <div className="text-center py-10">
        <Loader2 size={20} className="animate-spin text-sage-navy inline" />
      </div>
    );

  const returning = dialog?.mode === "return";
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">
        Employment verification
      </h1>
      <p className="text-sm text-gray-500">
        Employment details from your participants. Verify them to unlock the
        Phase 1 acknowledgment, or send them back with what needs correcting.
      </p>
      {error && !dialog && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Participant</th>
              <th className="text-left px-4 py-2">Employer</th>
              <th className="text-left px-4 py-2">Job title</th>
              <th className="text-left px-4 py-2">Start date</th>
              <th className="text-left px-4 py-2">Submitted</th>
              <th className="text-left px-4 py-2">Offer doc</th>
              <th className="text-right px-4 py-2">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {rows.length === 0 ? (
              <tr>
                <td
                  colSpan={7}
                  className="px-4 py-6 text-center text-sm text-gray-400 italic"
                >
                  No pending verifications.
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr key={r.userId}>
                  <td className="px-4 py-2">
                    <div className="font-medium text-gray-900">
                      {r.fullName ?? "—"}
                    </div>
                    <div className="font-mono text-[10px] text-gray-400">
                      {r.participantId ?? "—"}
                    </div>
                    {r.resubmitted && !r.returned && (
                      <div className="text-[10px] font-semibold text-sage-navy">Corrected details</div>
                    )}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {r.employerClient ?? "—"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {r.jobTitle ?? "—"}
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-gray-700">
                    {r.startDate ?? "—"}
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-500">
                    {r.acceptanceDate
                      ? formatDateMedium(r.acceptanceDate)
                      : "—"}
                  </td>
                  <td className="px-4 py-2">
                    {r.hasOffer ? (
                      <button
                        onClick={() => viewOffer(r.userId)}
                        className="text-xs font-semibold text-sage-navy hover:underline cursor-pointer"
                      >
                        View
                      </button>
                    ) : (
                      <span className="text-xs text-gray-400">—</span>
                    )}
                  </td>
                  <td className="px-4 py-2 text-right">
                    {r.returned ? (
                      <div className="text-left sm:text-right">
                        <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-amber-50 text-amber-700">
                          Sent back
                        </span>
                        <div className="mt-1 text-[11px] text-gray-500 italic max-w-[220px] sm:ml-auto">
                          {r.returnReason}
                        </div>
                        <div className="text-[10px] text-gray-400">Waiting for the participant</div>
                      </div>
                    ) : (
                      <div className="inline-flex gap-1.5">
                        <button
                          onClick={() => open(r.userId, "verify")}
                          className="px-3 py-1 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
                        >
                          Verify
                        </button>
                        <button
                          onClick={() => open(r.userId, "return")}
                          className="px-3 py-1 rounded-md text-xs font-bold bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy cursor-pointer"
                        >
                          Send back
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {dialog != null && (
        <div
          className="fixed inset-0 z-40 flex items-center justify-center bg-black/40 p-4"
          onClick={() => setDialog(null)}
        >
          <div
            className="bg-white rounded-2xl shadow-xl max-w-md w-full p-5"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 className="text-lg font-bold text-gray-900">
              {returning ? "Send back for correction" : "Verify employment"}
            </h2>
            <p className="text-xs text-gray-500 mt-1">
              {returning
                ? "The participant is emailed your reason and asked to submit corrected details."
                : "Confirms the offer details on file and unlocks Phase 1 for the participant."}
            </p>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={3}
              maxLength={2000}
              className="mt-3 w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
              placeholder={
                returning
                  ? "What needs correcting (emailed to the participant)"
                  : "Optional ERM notes (audit trail)"
              }
            />
            {error && (
              <p className="mt-2 inline-flex items-center gap-1.5 text-xs text-red-700">
                <AlertCircle size={12} /> {error}
              </p>
            )}
            <div className="mt-3 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setDialog(null)}
                className="px-3 py-1.5 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleSave}
                disabled={saving || (returning && notes.trim().length < 5)}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
              >
                {saving ? (
                  <Loader2 size={12} className="animate-spin" />
                ) : returning ? (
                  <Send size={12} />
                ) : (
                  <CheckCircle2 size={12} />
                )}
                {returning
                  ? saving ? "Sending…" : "Send back and email"
                  : saving ? "Verifying…" : "Verify ✓"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/* ── Phase 1 tab ──────────────────────────────────────────────── */

function Phase1Tab() {
  const [rows, setRows] = useState<ErmPendingPhaseRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<number | null>(null);
  const [error, setError] = useState("");

  const refresh = async () => setRows(await getErmPendingPhaseApprovals());

  useEffect(() => {
    let cancelled = false;
    refresh()
      .catch((e) => {
        if (!cancelled)
          setError(e instanceof Error ? e.message : "Couldn't load queue");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const approve = async (uid: number) => {
    setBusy(uid);
    setError("");
    try {
      await approvePhase1(uid);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Approve failed");
    } finally {
      setBusy(null);
    }
  };

  if (loading)
    return (
      <div className="text-center py-10">
        <Loader2 size={20} className="animate-spin text-sage-navy inline" />
      </div>
    );

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Phase 1 approvals</h1>
      <p className="text-sm text-gray-500">
        Participants who accepted the Phase 1 completion acknowledgment.
        Approving closes Phase 1 and starts their Phase 2 post-offer support
        from their employment start date. Finance sets up the payment plan.
      </p>
      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Participant</th>
              <th className="text-left px-4 py-2">Employment</th>
              <th className="text-left px-4 py-2">Accepted</th>
              <th className="text-left px-4 py-2">Version</th>
              <th className="text-right px-4 py-2">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {rows.length === 0 ? (
              <tr>
                <td
                  colSpan={5}
                  className="px-4 py-6 text-center text-sm text-gray-400 italic"
                >
                  No Phase 1 approvals pending.
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr key={r.userId}>
                  <td className="px-4 py-2">
                    <div className="font-medium text-gray-900">
                      {r.fullName ?? "—"}
                    </div>
                    <div className="font-mono text-[10px] text-gray-400">
                      {r.participantId ?? "—"}
                    </div>
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    <div>{r.employerClient ?? "—"}</div>
                    {r.startDate && (
                      <div className="font-mono text-[10px] text-gray-400">starts {r.startDate}</div>
                    )}
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-500">
                    {r.acceptedAt
                      ? formatDateMedium(r.acceptedAt)
                      : "—"}
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-gray-700">
                    {r.acknowledgmentVersion ?? "—"}
                  </td>
                  <td className="px-4 py-2 text-right">
                    <button
                      onClick={() => approve(r.userId)}
                      disabled={busy === r.userId}
                      className="inline-flex items-center gap-1.5 px-3 py-1 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
                    >
                      {busy === r.userId ? (
                        <Loader2 size={11} className="animate-spin" />
                      ) : (
                        <CheckCircle2 size={11} />
                      )}
                      Approve
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/* ── Interviews tab ──────────────────────────────────────────── */

function InterviewsTab() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Interview milestones</h1>
      <p className="text-sm text-gray-500">
        Interview milestones are recorded inside each weekly report&apos;s
        interview-training section. Use the Weekly Reports tab to drill in
        per participant.
      </p>
    </div>
  );
}

/* ── Coaches tab ─────────────────────────────────────────────── */

function CoachesTab({ roster }: { roster: ErmRosterRow[] }) {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Coach assignments</h1>
      <p className="text-sm text-gray-500">
        Read-only view of which coaches are paired with each of your
        participants. Open a participant from the My Participants tab to see
        their full team.
      </p>
      <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden divide-y divide-gray-100">
        {roster.map((r) => (
          <div
            key={r.userId}
            className="px-4 py-2.5 text-sm flex items-center gap-3"
          >
            <span className="font-medium text-gray-900 flex-1 truncate">
              {r.fullName ?? "—"}
            </span>
            <span className="font-mono text-xs text-gray-700">
              {r.participantId ?? "—"}
            </span>
            <span className="text-xs text-gray-500">{r.program ?? "—"}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function Placeholder({
  title,
  copy,
  link,
}: {
  title: string;
  copy: string;
  link?: { label: string; href: string };
}) {
  return (
    <div className="space-y-3">
      <h1 className="text-2xl font-bold text-gray-900">{title}</h1>
      <div className="rounded-2xl border border-dashed border-gray-200 bg-gray-50/60 p-6">
        <p className="text-sm text-gray-700">{copy}</p>
        {link && (
          <a
            href={link.href}
            className="mt-3 inline-block text-sm font-semibold text-sage-navy hover:underline"
          >
            {link.label} →
          </a>
        )}
      </div>
    </div>
  );
}
