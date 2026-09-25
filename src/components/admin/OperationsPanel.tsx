"use client";

import { useEffect, useMemo, useState } from "react";
import {
  AlertCircle,
  ClipboardList,
  FileText,
  Inbox,
  Loader2,
  Mail,
  ShieldCheck,
  UserCog,
  Users,
} from "lucide-react";
import { formatDateTime } from "@/lib/datetime";

import {
  assignCoachToParticipant,
  assignErmToParticipant,
  downloadSignedAgreement,
  getAgreementQueue,
  getAssignmentQueue,
  getAuditTrail,
  getDocumentReviewQueue,
  getEmailLog,
  getEnrollmentQueue,
  getOperationsExceptions,
  getStaffPool,
  updateCoachProfile,
  COACH_SKILLS,
  reviewDocument,
  viewParticipantDocument,
  type AgreementQueueRow,
  type AuditRow,
  type DocumentReviewRow,
  type DocumentReviewStatus,
  type EmailLogRow,
  type OperationsException,
  type OperationsQueueRow,
  type StaffMember,
  type StaffPool,
} from "@/lib/api";

/**
 * Operations Admin panel. Six sub-tabs cover the participant-
 * lifecycle operations work that doesn't fit the LMS-side
 * admin tabs:
 *
 *   enrollment  -- incomplete signups stuck at draft / basic info
 *   docReview   -- document review: view, approve, send back with a
 *                  reason (emailed), decide "not applicable" requests
 *   agreement   -- agreement signing queue
 *   assignments -- pending ERM / coach assignments + assign actions
 *   audit       -- user_records log with filters
 *   emails      -- every email sent or not (SENT / FAILED / SKIPPED)
 *   exceptions  -- every roadmap exception case, worked out from the records
 *
 * Self-contained: this panel is the body of /operations and can
 * also be embedded as a tab inside the legacy /admin LMS dashboard.
 */
type OpsTab =
  | "enrollment"
  | "docReview"
  | "agreement"
  | "assignments"
  | "audit"
  | "emails"
  | "exceptions";

const SUB_TABS: { id: OpsTab; label: string; Icon: typeof Users }[] = [
  { id: "enrollment",  label: "Enrollment queue", Icon: Inbox },
  { id: "docReview",   label: "Document review",  Icon: FileText },
  { id: "agreement",   label: "Agreement queue",  Icon: ShieldCheck },
  { id: "assignments", label: "Assignments",      Icon: UserCog },
  { id: "audit",       label: "Audit trail",      Icon: ClipboardList },
  { id: "emails",      label: "Email log",        Icon: Mail },
  { id: "exceptions",  label: "Exceptions",       Icon: AlertCircle },
];

export function OperationsPanel() {
  const [tab, setTab] = useState<OpsTab>("enrollment");
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-1.5">
        {SUB_TABS.map((s) => (
          <button
            key={s.id}
            type="button"
            onClick={() => setTab(s.id)}
            className={
              "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold transition cursor-pointer " +
              (tab === s.id
                ? "bg-sage-navy text-white shadow-sm"
                : "bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy")
            }
          >
            <s.Icon size={12} />
            {s.label}
          </button>
        ))}
      </div>
      {tab === "enrollment" && <EnrollmentQueue />}
      {tab === "docReview" && <DocumentReview />}
      {tab === "agreement" && <AgreementQueue />}
      {tab === "assignments" && <AssignmentsPanel />}
      {tab === "audit" && <AuditPanel />}
      {tab === "emails" && <EmailLogPanel />}
      {tab === "exceptions" && <ExceptionsPanel />}
    </div>
  );
}

/* ── Enrollment + agreement queues ───────────────────────────── */

function EnrollmentQueue() {
  const [rows, setRows] = useState<OperationsQueueRow[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let cancelled = false;
    getEnrollmentQueue()
      .then((r) => {
        if (!cancelled) setRows(r);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);
  if (loading) return <Spinner />;
  return (
    <Table
      headers={["Name", "Email", "Status", "Verified", "Created"]}
      empty="No incomplete enrollments."
      rows={rows.map((r) => (
        <tr key={r.userId}>
          <Td>{r.fullName ?? "—"}</Td>
          <Td>{r.email ?? "—"}</Td>
          <Td>
            <Pill>{r.currentStatus}</Pill>
          </Td>
          <Td>{r.emailVerified ? "✓" : "—"}</Td>
          <Td>
            {r.createdAt
              ? formatDateTime(r.createdAt)
              : "—"}
          </Td>
        </tr>
      ))}
    />
  );
}

const STAGE_LABEL: Record<string, string> = {
  DECLINED: "DECLINED",
  EXPIRED: "EXPIRED",
  NEEDS_ERM: "NEEDS AN ERM",
  WAITING: "WAITING TO SIGN",
  CHECK_STEP: "SIGNED · CHECK STEP OPEN",
  ERM_REVIEW: "WITH ERM FOR REVIEW",
};

/**
 * Checklist 2.5: agreements that need attention — declined, expired and
 * waiting ones, signed ones still missing the check step or an ERM, and
 * ones with the ERM for review. Signed agreements can be opened here.
 */
function AgreementQueue() {
  const [rows, setRows] = useState<AgreementQueueRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  useEffect(() => {
    let cancelled = false;
    getAgreementQueue()
      .then((r) => {
        if (!cancelled) setRows(r);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "Couldn't load the queue");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);
  const open = async (userId: number) => {
    setError("");
    try {
      await downloadSignedAgreement(userId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't open the agreement");
    }
  };
  if (loading) return <Spinner />;
  return (
    <div className="space-y-3">
      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      <Table
        headers={["Name", "Email", "Stage", "Since", "Details"]}
        empty="No agreements need attention."
        rows={rows.map((r) => (
          <tr key={r.userId}>
            <Td>
              <span className="font-medium text-gray-900">{r.fullName ?? "—"}</span>
              <span className="block font-mono text-[10px] text-gray-400">{r.participantId ?? ""}</span>
            </Td>
            <Td>{r.email ?? "—"}</Td>
            <Td>
              <Pill>{STAGE_LABEL[r.stage] ?? r.stage}</Pill>
            </Td>
            <Td>{r.since ? formatDateTime(r.since) : "—"}</Td>
            <Td>
              <span className="text-xs text-gray-600">{r.detail ?? ""}</span>
              {(r.stage === "CHECK_STEP" || r.stage === "NEEDS_ERM" || r.stage === "ERM_REVIEW") && (
                <button
                  type="button"
                  onClick={() => open(r.userId)}
                  className="ml-2 text-xs font-semibold text-sage-navy hover:text-sage-navy-deep cursor-pointer"
                >
                  Open agreement
                </button>
              )}
            </Td>
          </tr>
        ))}
      />
    </div>
  );
}

/* ── Document review ─────────────────────────────────────────── */

const DOC_STATUS_LABEL: Record<DocumentReviewStatus, string> = {
  PENDING: "NEEDS REVIEW",
  APPROVED: "APPROVED",
  REJECTED: "SENT BACK",
  NOT_APPLICABLE: "NOT APPLICABLE",
  EXCEPTION_REQUESTED: "N/A REQUEST",
  EXCEPTION_APPROVED: "N/A APPROVED",
  EXCEPTION_DECLINED: "N/A DECLINED",
};

function formatBytes(bytes: number | null): string {
  if (!bytes && bytes !== 0) return "—";
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + " KB";
  return (bytes / (1024 * 1024)).toFixed(1) + " MB";
}

/**
 * Operations reviews each document (roadmap step 5). Opening a file is
 * recorded on the participant's audit trail. Sending a document back
 * needs a reason, which is emailed to the participant and shown on
 * their upload page. "Not applicable" on a required document arrives
 * here as a request with the participant's reason.
 */
function DocumentReview() {
  const [filter, setFilter] = useState("NEEDS_REVIEW");
  const [rows, setRows] = useState<DocumentReviewRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<number | null>(null);
  const [error, setError] = useState("");
  const [sendBackFor, setSendBackFor] = useState<number | null>(null);
  const [reason, setReason] = useState("");

  const load = async (f: string = filter) => {
    setLoading(true);
    setError("");
    try {
      setRows(await getDocumentReviewQueue(f));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load documents");
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    load();
    /* eslint-disable-next-line react-hooks/exhaustive-deps */
  }, []);

  const decide = async (row: DocumentReviewRow, status: "APPROVED" | "REJECTED") => {
    setBusy(row.id);
    setError("");
    try {
      await reviewDocument(row.id, status, status === "REJECTED" ? reason.trim() : undefined);
      setSendBackFor(null);
      setReason("");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't save the decision");
    } finally {
      setBusy(null);
    }
  };

  const view = async (row: DocumentReviewRow) => {
    setError("");
    try {
      await viewParticipantDocument(row.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't open the document");
    }
  };

  const secondary =
    "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy disabled:opacity-60 cursor-pointer";
  const primary =
    "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer";

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2 items-end">
        <div>
          <label className="block text-[10px] uppercase tracking-wider font-semibold text-gray-500 mb-0.5">
            Show
          </label>
          <select
            value={filter}
            onChange={(e) => {
              setFilter(e.target.value);
              load(e.target.value);
            }}
            className="px-2 py-1.5 text-xs rounded-md border border-gray-200"
          >
            <option value="NEEDS_REVIEW">Needs review</option>
            <option value="ALL">All documents</option>
          </select>
        </div>
        <button onClick={() => load()} disabled={loading} className={primary}>
          {loading && <Loader2 size={12} className="animate-spin" />} Refresh
        </button>
      </div>

      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}

      {loading ? (
        <Spinner />
      ) : rows.length === 0 ? (
        <p className="text-sm text-gray-400 italic px-4 py-3">
          {filter === "NEEDS_REVIEW" ? "No documents waiting for review." : "No documents."}
        </p>
      ) : (
        <div className="space-y-2.5">
          {rows.map((r) => {
            const request = r.notApplicable;
            const decidable = r.reviewStatus !== "NOT_APPLICABLE";
            const approved = r.reviewStatus === "APPROVED" || r.reviewStatus === "EXCEPTION_APPROVED";
            const sentBack = r.reviewStatus === "REJECTED" || r.reviewStatus === "EXCEPTION_DECLINED";
            return (
              <div key={r.id} className="rounded-2xl border border-gray-100 bg-white p-3.5">
                <div className="flex items-center gap-3 mb-2">
                  <div className="min-w-0 flex-1">
                    <p className="font-medium text-gray-900 truncate">
                      {r.participantName ?? "—"}
                    </p>
                    <p className="text-xs text-gray-500 truncate">
                      {r.participantEmail ?? ""} ·{" "}
                      <span className="font-mono">{r.participantId ?? ""}</span>
                    </p>
                  </div>
                  <Pill>{DOC_STATUS_LABEL[r.reviewStatus] ?? r.reviewStatus}</Pill>
                </div>
                <p className="text-sm text-gray-700">
                  <span className="font-semibold">{r.documentLabel}</span>
                  <span className="text-gray-500">{r.required ? " (required)" : " (optional)"}</span>
                  {!request && r.fileName && (
                    <span className="text-gray-500">
                      {" "}· {r.fileName} · {formatBytes(r.fileSize)}
                    </span>
                  )}
                  {r.uploadedAt && (
                    <span className="text-gray-500">
                      {" "}· {formatDateTime(r.uploadedAt)}
                    </span>
                  )}
                </p>
                {request && (
                  <p className="mt-1 text-xs text-gray-600">
                    Asked to mark it not applicable. Reason:{" "}
                    {r.exceptionReason ?? "none given (asked before reasons were collected)"}
                  </p>
                )}
                {r.reviewerNotes && (
                  <p className="mt-1 text-xs text-gray-500 italic">Reason sent: {r.reviewerNotes}</p>
                )}
                {decidable && (
                  <div className="mt-2 flex flex-wrap items-center gap-1.5">
                    {!request && (
                      <button type="button" onClick={() => view(r)} className={secondary}>
                        View
                      </button>
                    )}
                    {!approved && (
                      <button
                        type="button"
                        onClick={() => decide(r, "APPROVED")}
                        disabled={busy === r.id}
                        className={primary}
                      >
                        {busy === r.id && sendBackFor !== r.id && (
                          <Loader2 size={12} className="animate-spin" />
                        )}
                        {request ? "Approve request" : "Approve"}
                      </button>
                    )}
                    {!sentBack && sendBackFor !== r.id && (
                      <button
                        type="button"
                        onClick={() => {
                          setSendBackFor(r.id);
                          setReason("");
                        }}
                        disabled={busy === r.id}
                        className={secondary}
                      >
                        {request ? "Decline" : "Send back"}
                      </button>
                    )}
                  </div>
                )}
                {sendBackFor === r.id && (
                  <div className="mt-2 flex flex-col sm:flex-row gap-1.5">
                    <input
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                      maxLength={1000}
                      placeholder={
                        request
                          ? "Why it's still needed (emailed to the participant)"
                          : "What's wrong (emailed to the participant)"
                      }
                      className="flex-1 px-2 py-1.5 text-xs rounded-md border border-gray-200"
                    />
                    <div className="flex gap-1.5">
                      <button
                        type="button"
                        onClick={() => decide(r, "REJECTED")}
                        disabled={busy === r.id || !reason.trim()}
                        className={primary}
                      >
                        {busy === r.id && <Loader2 size={12} className="animate-spin" />}
                        {request ? "Decline request" : "Send back"}
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          setSendBackFor(null);
                          setReason("");
                        }}
                        className={secondary}
                      >
                        Cancel
                      </button>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/* ── Assignments panel ───────────────────────────────────────── */

function AssignmentsPanel() {
  const [rows, setRows] = useState<Record<string, unknown>[]>([]);
  const [staff, setStaff] = useState<StaffPool | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<number | null>(null);
  const [error, setError] = useState("");
  // Checklist 3.2: the chosen coach slot per participant; the coach list
  // only offers coaches of that type.
  const [slotFor, setSlotFor] = useState<Record<number, string>>({});

  const refresh = async () => {
    const [q, s] = await Promise.all([getAssignmentQueue(), getStaffPool()]);
    setRows(q);
    setStaff(s);
  };

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

  const allCoaches = useMemo(
    () => [...(staff?.coach ?? []), ...(staff?.technicalAdvisor ?? [])],
    [staff],
  );

  const handleErm = async (participantId: number, ermUserId: number) => {
    setBusy(participantId);
    setError("");
    try {
      await assignErmToParticipant(participantId, ermUserId);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Assign failed");
    } finally {
      setBusy(null);
    }
  };

  const handleCoach = async (
    participantId: number,
    coachUserId: number,
    role: string,
  ) => {
    setBusy(participantId);
    setError("");
    try {
      await assignCoachToParticipant(participantId, coachUserId, role);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Assign failed");
    } finally {
      setBusy(null);
    }
  };

  if (loading) return <Spinner />;

  return (
    <div className="space-y-3">
      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      {rows.length === 0 ? (
        <p className="text-sm text-gray-400 italic px-4 py-3">
          No participants need manual assignment.
        </p>
      ) : (
        <div className="space-y-2.5">
          {rows.map((r) => {
            const uid = Number(r.userId);
            return (
              <div
                key={uid}
                className="rounded-2xl border border-gray-100 bg-white p-3.5"
              >
                <div className="flex items-center gap-3 mb-2">
                  <div className="min-w-0 flex-1">
                    <p className="font-medium text-gray-900 truncate">
                      {String(r.fullName)}
                    </p>
                    <p className="text-xs text-gray-500 truncate">
                      {String(r.email ?? "")} ·{" "}
                      <span className="font-mono">
                        {String(r.participantId ?? "")}
                      </span>
                    </p>
                  </div>
                  <Pill>{String(r.currentStatus)}</Pill>
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  <div>
                    <label className="block text-[10px] uppercase tracking-wider font-semibold text-gray-500 mb-0.5">
                      ERM {r.ermAssigned ? "(assigned)" : ""}
                    </label>
                    <div className="flex gap-1">
                      <select
                        className="flex-1 px-2 py-1.5 text-xs rounded-md border border-gray-200"
                        defaultValue=""
                        onChange={(e) => {
                          const v = Number(e.target.value);
                          if (v) handleErm(uid, v);
                        }}
                        disabled={busy === uid}
                      >
                        <option value="">— Pick ERM —</option>
                        {(staff?.erm ?? []).map((u) => (
                          <option key={u.id} value={u.id}>
                            {u.fullName} ({u.email})
                          </option>
                        ))}
                      </select>
                    </div>
                  </div>
                  <div>
                    <label className="block text-[10px] uppercase tracking-wider font-semibold text-gray-500 mb-0.5">
                      Coach {r.coachesAssigned ? "(some assigned)" : ""}
                    </label>
                    <div className="flex gap-1">
                      <select
                        id={`coach-pick-${uid}`}
                        className="flex-1 px-2 py-1.5 text-xs rounded-md border border-gray-200"
                        defaultValue=""
                      >
                        <option value="">— Pick coach —</option>
                        {allCoaches
                          .filter((u) => (u.coachTypes ?? []).includes(slotFor[uid] ?? "CAREER_COACH"))
                          .map((u) => (
                            <option key={u.id} value={u.id}>
                              {u.fullName}
                              {(u.coachSkills ?? []).length > 0 ? ` (${(u.coachSkills ?? []).join(", ")})` : ""}
                            </option>
                          ))}
                      </select>
                      <select
                        id={`coach-role-${uid}`}
                        className="px-2 py-1.5 text-xs rounded-md border border-gray-200"
                        value={slotFor[uid] ?? "CAREER_COACH"}
                        onChange={(e) => setSlotFor((prev) => ({ ...prev, [uid]: e.target.value }))}
                      >
                        <option value="CAREER_COACH">Career</option>
                        <option value="RESUME_SPECIALIST">Resume</option>
                        <option value="TECHNICAL_ADVISOR">Tech</option>
                        <option value="INTERVIEW_COACH">Interview</option>
                      </select>
                      <button
                        type="button"
                        onClick={() => {
                          const cidEl = document.getElementById(
                            `coach-pick-${uid}`,
                          ) as HTMLSelectElement | null;
                          const roleEl = document.getElementById(
                            `coach-role-${uid}`,
                          ) as HTMLSelectElement | null;
                          if (!cidEl?.value) return;
                          handleCoach(
                            uid,
                            Number(cidEl.value),
                            roleEl?.value ?? "CAREER_COACH",
                          );
                        }}
                        disabled={busy === uid}
                        className="px-2 py-1.5 rounded-md text-[10px] font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
                      >
                        Assign
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
      <CoachProfilesEditor coaches={allCoaches} onSaved={refresh} />
    </div>
  );
}

const COACH_TYPE_LABEL: Record<string, string> = {
  CAREER_COACH: "Career",
  RESUME_SPECIALIST: "Resume",
  TECHNICAL_ADVISOR: "Tech",
  INTERVIEW_COACH: "Interview",
};

/**
 * Checklist 3.2: which coach slots each coach fills and which skills they
 * cover — what automatic matching uses.
 */
function CoachProfilesEditor({
  coaches,
  onSaved,
}: {
  coaches: StaffMember[];
  onSaved: () => Promise<void>;
}) {
  const [draft, setDraft] = useState<Record<number, { types: string[]; skills: string[] }>>({});
  const [busy, setBusy] = useState<number | null>(null);
  const [error, setError] = useState("");
  const current = (c: StaffMember) => draft[c.id] ?? { types: c.coachTypes ?? [], skills: c.coachSkills ?? [] };
  const toggle = (c: StaffMember, key: "types" | "skills", value: string) => {
    const d = current(c);
    const list = d[key].includes(value) ? d[key].filter((v) => v !== value) : [...d[key], value];
    setDraft((prev) => ({ ...prev, [c.id]: { ...d, [key]: list } }));
  };
  const save = async (c: StaffMember) => {
    setBusy(c.id);
    setError("");
    try {
      const d = current(c);
      await updateCoachProfile(c.id, d.types, d.skills);
      setDraft((prev) => {
        const next = { ...prev };
        delete next[c.id];
        return next;
      });
      await onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't save");
    } finally {
      setBusy(null);
    }
  };
  if (coaches.length === 0) return null;
  const pill = (on: boolean) =>
    "px-2 py-0.5 rounded-full text-[10px] font-bold cursor-pointer transition " +
    (on ? "bg-sage-navy text-white" : "bg-gray-100 text-gray-500 hover:text-gray-800");
  return (
    <div className="space-y-2">
      <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mt-4">
        Coach profiles (used to match coaches automatically)
      </p>
      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      {coaches.map((c) => {
        const d = current(c);
        return (
          <div key={c.id} className="rounded-2xl border border-gray-100 bg-white p-3.5 space-y-2">
            <div className="flex items-center gap-3">
              <div className="min-w-0 flex-1">
                <p className="font-medium text-gray-900 truncate">{c.fullName}</p>
                <p className="text-xs text-gray-500 truncate">{c.email}</p>
              </div>
              {draft[c.id] && (
                <button
                  type="button"
                  onClick={() => save(c)}
                  disabled={busy === c.id}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
                >
                  {busy === c.id && <Loader2 size={12} className="animate-spin" />} Save
                </button>
              )}
            </div>
            <div className="flex flex-wrap gap-1">
              {Object.entries(COACH_TYPE_LABEL).map(([key, label]) => (
                <button key={key} type="button" onClick={() => toggle(c, "types", key)} className={pill(d.types.includes(key))}>
                  {label}
                </button>
              ))}
            </div>
            <div className="flex flex-wrap gap-1">
              {COACH_SKILLS.map((skill) => (
                <button key={skill} type="button" onClick={() => toggle(c, "skills", skill)} className={pill(d.skills.includes(skill))}>
                  {skill}
                </button>
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}

/* ── Audit panel ─────────────────────────────────────────────── */

function AuditPanel() {
  const [rows, setRows] = useState<AuditRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [category, setCategory] = useState("");
  const [userId, setUserId] = useState("");

  const load = async () => {
    setLoading(true);
    try {
      setRows(
        await getAuditTrail({
          category: category || undefined,
          userId: userId ? Number(userId) : undefined,
          limit: 200,
        }),
      );
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    load();
    /* eslint-disable-next-line react-hooks/exhaustive-deps */
  }, []);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2 items-end">
        <div>
          <label className="block text-[10px] uppercase tracking-wider font-semibold text-gray-500 mb-0.5">
            Category
          </label>
          <select
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            className="px-2 py-1.5 text-xs rounded-md border border-gray-200"
          >
            <option value="">All</option>
            {[
              "ACCOUNT",
              "LEARNING",
              "ASSESSMENT",
              "MENTORSHIP",
              "PAYMENT",
              "CERTIFICATE",
              "SECURITY",
            ].map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="block text-[10px] uppercase tracking-wider font-semibold text-gray-500 mb-0.5">
            User ID
          </label>
          <input
            value={userId}
            onChange={(e) => setUserId(e.target.value)}
            className="px-2 py-1.5 text-xs rounded-md border border-gray-200 w-24"
            placeholder="optional"
          />
        </div>
        <button
          onClick={load}
          disabled={loading}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
        >
          {loading && <Loader2 size={12} className="animate-spin" />} Refresh
        </button>
      </div>

      <Table
        headers={["Time", "User", "Category", "Type", "Title"]}
        empty="No audit rows."
        rows={rows.map((r) => (
          <tr key={r.id}>
            <Td>{formatDateTime(r.createdAt)}</Td>
            <Td>{r.userId}</Td>
            <Td>
              <Pill>{r.category}</Pill>
            </Td>
            <Td>{r.recordType}</Td>
            <Td>{r.title}</Td>
          </tr>
        ))}
      />
    </div>
  );
}

/* ── Email log ───────────────────────────────────────────────── */

/** DOCUMENT_REMINDER → "Document reminder". */
function emailTypeLabel(type: string): string {
  const t = type.replace(/_/g, " ").toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
}

/**
 * Every email the platform tried to send (checklist 1.4). FAILED shows
 * the mail server's reason; SKIPPED means email isn't set up on that
 * server. Email bodies are never stored.
 */
function EmailLogPanel() {
  const [rows, setRows] = useState<EmailLogRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState("");
  const [email, setEmail] = useState("");
  const [error, setError] = useState("");

  const load = async () => {
    setLoading(true);
    setError("");
    try {
      setRows(await getEmailLog({ status: status || undefined, email: email.trim() || undefined }));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load the email log");
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    load();
    /* eslint-disable-next-line react-hooks/exhaustive-deps */
  }, []);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2 items-end">
        <div>
          <label className="block text-[10px] uppercase tracking-wider font-semibold text-gray-500 mb-0.5">
            Status
          </label>
          <select
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            className="px-2 py-1.5 text-xs rounded-md border border-gray-200"
          >
            <option value="">All</option>
            <option value="FAILED">Failed</option>
            <option value="SKIPPED">Not sent (email not set up)</option>
            <option value="SENT">Sent</option>
          </select>
        </div>
        <div>
          <label className="block text-[10px] uppercase tracking-wider font-semibold text-gray-500 mb-0.5">
            Email address
          </label>
          <input
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="px-2 py-1.5 text-xs rounded-md border border-gray-200 w-56"
            placeholder="optional"
          />
        </div>
        <button
          onClick={load}
          disabled={loading}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
        >
          {loading && <Loader2 size={12} className="animate-spin" />} Refresh
        </button>
      </div>

      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}

      {loading ? (
        <Spinner />
      ) : (
        <Table
          headers={["Time", "To", "Email", "Status", "Details"]}
          empty="No emails recorded yet."
          rows={rows.map((r) => (
            <tr key={r.id}>
              <Td>{r.sentAt ? formatDateTime(r.sentAt) : "—"}</Td>
              <Td>{r.recipient ?? "—"}</Td>
              <Td>{emailTypeLabel(r.emailType)}</Td>
              <Td>
                <Pill>{r.status === "SKIPPED" ? "NOT SENT" : r.status}</Pill>
              </Td>
              <Td>
                {r.status === "SENT" ? (
                  <span className="text-gray-500">{r.subject ?? ""}</span>
                ) : (
                  <span className="text-red-700">{r.errorMessage ?? "Not sent"}</span>
                )}
              </Td>
            </tr>
          ))}
        />
      )}
    </div>
  );
}

/* ── Exceptions panel ────────────────────────────────────────── */

/**
 * Checklist 6.2: every exception case in the roadmap in one list, worked
 * out from the records (a row disappears once it's resolved). Filter by
 * type; each row says who (with Participant ID), what, since when and
 * where to fix it.
 */
function ExceptionsPanel() {
  const [rows, setRows] = useState<OperationsException[]>([]);
  const [loading, setLoading] = useState(true);
  const [type, setType] = useState("ALL");
  useEffect(() => {
    let cancelled = false;
    getOperationsExceptions()
      .then((r) => {
        if (!cancelled) setRows(r);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);
  if (loading) return <Spinner />;
  const counts = new Map<string, { label: string; n: number }>();
  for (const r of rows) {
    const c = counts.get(r.type) ?? { label: r.label, n: 0 };
    c.n += 1;
    counts.set(r.type, c);
  }
  const shown = type === "ALL" ? rows : rows.filter((r) => r.type === type);
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-1.5">
        <button
          onClick={() => setType("ALL")}
          className={
            "px-2.5 py-1 rounded-md text-[11px] font-semibold cursor-pointer " +
            (type === "ALL" ? "bg-sage-navy text-white" : "bg-white border border-gray-200 text-gray-600 hover:text-sage-navy")
          }
        >
          All ({rows.length})
        </button>
        {Array.from(counts.entries()).map(([t, c]) => (
          <button
            key={t}
            onClick={() => setType(t)}
            className={
              "px-2.5 py-1 rounded-md text-[11px] font-semibold cursor-pointer " +
              (type === t ? "bg-sage-navy text-white" : "bg-white border border-gray-200 text-gray-600 hover:text-sage-navy")
            }
          >
            {c.label} ({c.n})
          </button>
        ))}
      </div>
      <Table
        headers={["What", "Participant", "Detail", "Since", "What to do"]}
        empty="No open exceptions."
        rows={shown.map((r, idx) => (
          <tr key={idx}>
            <Td>
              <Pill>{r.label}</Pill>
            </Td>
            <Td>
              <div className="font-medium text-gray-900">{r.fullName ?? "—"}</div>
              <div className="font-mono text-[10px] text-gray-400">{r.participantId ?? ""}</div>
            </Td>
            <Td>
              <span className="text-xs text-gray-700">{r.detail ?? ""}</span>
            </Td>
            <Td>{r.since ? formatDateTime(r.since) : "—"}</Td>
            <Td>
              <span className="text-xs text-gray-500">{r.where ?? ""}</span>
            </Td>
          </tr>
        ))}
      />
    </div>
  );
}

/* ── UI primitives ──────────────────────────────────────────── */

function Table({
  headers,
  rows,
  empty,
}: {
  headers: string[];
  rows: React.ReactNode[];
  empty: string;
}) {
  return (
    <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
          <tr>
            {headers.map((h) => (
              <th key={h} className="text-left px-4 py-2">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {rows.length === 0 ? (
            <tr>
              <td
                colSpan={headers.length}
                className="px-4 py-6 text-center text-sm text-gray-400 italic"
              >
                {empty}
              </td>
            </tr>
          ) : (
            rows
          )}
        </tbody>
      </table>
    </div>
  );
}

function Td({ children }: { children: React.ReactNode }) {
  return <td className="px-4 py-2 text-gray-700">{children}</td>;
}

function Pill({ children }: { children: React.ReactNode }) {
  return (
    <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-gray-100 text-gray-700">
      {children}
    </span>
  );
}

function Spinner() {
  return (
    <div className="text-center py-10">
      <Loader2 size={20} className="animate-spin text-sage-navy inline" />
    </div>
  );
}
