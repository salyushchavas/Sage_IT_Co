"use client";

import { useEffect, useMemo, useState } from "react";
import {
  AlertCircle,
  ClipboardList,
  FileText,
  Inbox,
  Loader2,
  ShieldCheck,
  UserCog,
  Users,
} from "lucide-react";

import {
  assignCoachToParticipant,
  assignErmToParticipant,
  getAgreementQueue,
  getAssignmentQueue,
  getAuditTrail,
  getEnrollmentQueue,
  getOperationsExceptions,
  getStaffPool,
  type AuditRow,
  type OperationsException,
  type OperationsQueueRow,
  type StaffPool,
} from "@/lib/api";

/**
 * Operations Admin panel. Six sub-tabs cover the participant-
 * lifecycle operations work that doesn't fit the LMS-side
 * admin tabs:
 *
 *   enrollment  -- incomplete signups stuck at draft / basic info
 *   docReview   -- pointer to the existing Documents reviewer
 *   agreement   -- agreement signing queue
 *   assignments -- pending ERM / coach assignments + assign actions
 *   audit       -- user_records log with filters
 *   exceptions  -- derived exceptions (stalled docs / agreements)
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
  | "exceptions";

const SUB_TABS: { id: OpsTab; label: string; Icon: typeof Users }[] = [
  { id: "enrollment",  label: "Enrollment queue", Icon: Inbox },
  { id: "docReview",   label: "Document review",  Icon: FileText },
  { id: "agreement",   label: "Agreement queue",  Icon: ShieldCheck },
  { id: "assignments", label: "Assignments",      Icon: UserCog },
  { id: "audit",       label: "Audit trail",      Icon: ClipboardList },
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
      {tab === "docReview" && <DocReviewPointer />}
      {tab === "agreement" && <AgreementQueue />}
      {tab === "assignments" && <AssignmentsPanel />}
      {tab === "audit" && <AuditPanel />}
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
              ? new Date(r.createdAt).toLocaleString("en-IN")
              : "—"}
          </Td>
        </tr>
      ))}
    />
  );
}

function AgreementQueue() {
  const [rows, setRows] = useState<OperationsQueueRow[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let cancelled = false;
    getAgreementQueue()
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
      headers={["Name", "Email", "Workflow", "Agreement", "Sent"]}
      empty="No agreements in flight."
      rows={rows.map((r) => (
        <tr key={r.userId}>
          <Td>{r.fullName ?? "—"}</Td>
          <Td>{r.email ?? "—"}</Td>
          <Td>
            <Pill>{r.currentStatus}</Pill>
          </Td>
          <Td>
            <Pill>{r.agreementStatus ?? "—"}</Pill>
          </Td>
          <Td>
            {r.agreementSentAt
              ? new Date(r.agreementSentAt).toLocaleString("en-IN")
              : "—"}
          </Td>
        </tr>
      ))}
    />
  );
}

function DocReviewPointer() {
  return (
    <div className="rounded-2xl border border-dashed border-gray-200 bg-gray-50/60 p-6">
      <p className="text-sm text-gray-700">
        Document review continues to live on the existing admin Documents
        endpoint (
        <code className="font-mono text-xs">GET /api/admin/documents</code>
        ). The Approve / Reject actions write back through the same path the
        Phase 2B reviewer used; the Operations panel surfaces only derived
        queues here.
      </p>
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
                        {allCoaches.map((u) => (
                          <option key={u.id} value={u.id}>
                            {u.fullName}
                          </option>
                        ))}
                      </select>
                      <select
                        id={`coach-role-${uid}`}
                        className="px-2 py-1.5 text-xs rounded-md border border-gray-200"
                        defaultValue="CAREER_COACH"
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
            <Td>{new Date(r.createdAt).toLocaleString("en-IN")}</Td>
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

/* ── Exceptions panel ────────────────────────────────────────── */

function ExceptionsPanel() {
  const [rows, setRows] = useState<OperationsException[]>([]);
  const [loading, setLoading] = useState(true);
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
  return (
    <Table
      headers={["Type", "Participant", "Status", "Open since"]}
      empty="No outstanding exceptions."
      rows={rows.map((r, idx) => (
        <tr key={idx}>
          <Td>
            <Pill>{r.type}</Pill>
          </Td>
          <Td>
            {r.fullName} (#{r.userId})
          </Td>
          <Td>{r.currentStatus}</Td>
          <Td>
            {r.openSince
              ? new Date(r.openSince).toLocaleString("en-IN")
              : "—"}
          </Td>
        </tr>
      ))}
    />
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
