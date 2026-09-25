"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { Loader2, Mail, Search, UserPlus, X } from "lucide-react";

import {
  STAFF_ROLE_OPTIONS,
  createStaffUser,
  getUsers,
  inviteParticipant,
  type UserDTO,
} from "@/lib/api";
import { formatDateMedium } from "@/lib/datetime";
import { cn } from "@/lib/utils";

/** Plain names for every role in the database (old LMS ones marked). */
const ROLE_LABEL: Record<string, string> = {
  PARTICIPANT: "Participant",
  ERM: "ERM",
  COACH: "Coach",
  TECHNICAL_ADVISOR: "Technical advisor",
  FINANCE: "Finance",
  OPERATIONS_ADMIN: "Operations admin",
  SYSTEM_ADMIN: "System admin",
  ADMIN: "LMS admin (old)",
  INSTRUCTOR: "Instructor (old)",
  TRAINER: "Trainer (old)",
  STUDENT: "Student (old)",
};

const ROLE_FILTERS: { value: string; label: string; roles: string[] }[] = [
  { value: "ALL", label: "All roles", roles: [] },
  { value: "PARTICIPANT", label: "Participants", roles: ["PARTICIPANT"] },
  { value: "ERM", label: "ERMs", roles: ["ERM"] },
  { value: "COACH", label: "Coaches", roles: ["COACH"] },
  { value: "TECHNICAL_ADVISOR", label: "Technical advisors", roles: ["TECHNICAL_ADVISOR"] },
  { value: "FINANCE", label: "Finance", roles: ["FINANCE"] },
  { value: "OPERATIONS_ADMIN", label: "Operations admins", roles: ["OPERATIONS_ADMIN"] },
  { value: "SYSTEM_ADMIN", label: "System admins", roles: ["SYSTEM_ADMIN"] },
  { value: "STAFF", label: "All staff", roles: ["ERM", "COACH", "TECHNICAL_ADVISOR", "FINANCE", "OPERATIONS_ADMIN", "SYSTEM_ADMIN"] },
  { value: "OLD", label: "Old LMS accounts", roles: ["ADMIN", "INSTRUCTOR", "TRAINER", "STUDENT"] },
];

/** Where a participant is on the roadmap, from their status. */
const STAGES: { value: string; label: string; statuses: string[] }[] = [
  { value: "SIGNUP", label: "Signing up (steps 1–3)", statuses: ["DRAFT_STARTED", "BASIC_INFO_SUBMITTED", "EMAIL_VERIFICATION_PENDING", "EMAIL_VERIFIED", "PARTICIPANT_ID_CREATED", "ID_EMAIL_SENT"] },
  { value: "ONBOARDING", label: "Onboarding (steps 4–9)", statuses: ["ACKNOWLEDGMENT_ACCEPTED", "DOCUMENTS_SUBMITTED", "DOC_REVIEW_PENDING", "PROGRAM_SELECTED", "AGREEMENT_SENT", "AGREEMENT_COMPLETED", "CHECK_COPY_UPLOADED"] },
  { value: "TEAM", label: "Team being set up (steps 10–15)", statuses: ["SIGNED_AGREEMENT_SENT_TO_ERM", "WELCOME_SENT", "DEEPTHI_INTRO_SENT", "ERM_ASSIGNED", "COACHES_ASSIGNED", "DASHBOARD_ENABLED"] },
  { value: "SEARCH", label: "Job search (step 16)", statuses: ["WEEKLY_REPORTING_ACTIVE"] },
  { value: "EMPLOYED", label: "Employed / Phase 1 (step 17)", statuses: ["EMPLOYMENT_ACCEPTED", "PHASE_1_COMPLETED"] },
  { value: "PAYING", label: "Payments (steps 18–20)", statuses: ["PAYMENT_PLAN_ACCEPTED", "CHECK_TRACKING_ADDED", "INVOICING_ACTIVE", "PAYMENTS_TRACKED"] },
];

const stageOf = (status: string | null | undefined) =>
  STAGES.find((s) => s.statuses.includes(status ?? ""))?.label ?? "Not started";

const FIELD =
  "w-full px-3 py-2 text-sm rounded-lg border border-zinc-200 bg-white text-zinc-900 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy";

/**
 * Admin → Users: everyone on the portal with filters (search, role,
 * participant stage, accounts that need attention), plus adding staff and
 * inviting participants (asked for on 25 Sep).
 */
export function AdminUsersTab({ canAddStaff }: { canAddStaff: boolean }) {
  const router = useRouter();
  const [all, setAll] = useState<UserDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState<"active" | "inactive">("active");
  const [q, setQ] = useState("");
  const [role, setRole] = useState("ALL");
  const [stage, setStage] = useState("ALL");
  const [only, setOnly] = useState("ALL");
  const [sort, setSort] = useState<"newest" | "name">("newest");
  const [dialog, setDialog] = useState<null | "staff" | "invite">(null);
  const [notice, setNotice] = useState<{ kind: "success" | "error"; text: string } | null>(null);

  const load = useCallback(async () => {
    try {
      setAll(((await getUsers("all")) ?? []) as UserDTO[]);
    } catch (e) {
      // A failed load must not look like "No active users found".
      setNotice({ kind: "error", text: e instanceof Error ? `Couldn't load the users: ${e.message}` : "Couldn't load the users" });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const activeCount = all.filter((u) => u.isActive !== false).length;
  const shown = useMemo(() => {
    const roles = ROLE_FILTERS.find((r) => r.value === role)?.roles ?? [];
    const stageStatuses = STAGES.find((s) => s.value === stage)?.statuses;
    const needle = q.trim().toLowerCase();
    const rows = all.filter((u) => {
      if ((u.isActive !== false) !== (status === "active")) return false;
      const r = (u.role ?? "").toUpperCase();
      if (roles.length && !roles.includes(r)) return false;
      if (stage !== "ALL" && (r !== "PARTICIPANT" || !stageStatuses?.includes(u.currentStatus ?? ""))) return false;
      if (only === "FIRST_SIGNIN" && !u.mustChangePassword) return false;
      if (only === "NOT_VERIFIED" && u.emailVerified !== false) return false;
      if (needle) {
        const hay = [u.fullName, u.email, u.personalEmail, u.participantId].filter(Boolean).join(" ").toLowerCase();
        if (!hay.includes(needle)) return false;
      }
      return true;
    });
    return rows.sort((a, b) =>
      sort === "name"
        ? (a.fullName ?? "").localeCompare(b.fullName ?? "")
        : (b.id ?? 0) - (a.id ?? 0),
    );
  }, [all, status, role, stage, only, q, sort]);

  const countFor = (value: string) => {
    const roles = ROLE_FILTERS.find((r) => r.value === value)?.roles ?? [];
    return all.filter((u) => (u.isActive !== false) === (status === "active")
      && (!roles.length || roles.includes((u.role ?? "").toUpperCase()))).length;
  };
  const filtered = q || role !== "ALL" || stage !== "ALL" || only !== "ALL";

  return (
    <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
      <div className="flex items-center gap-2 flex-wrap mb-5">
        <h1 className="text-2xl font-bold text-zinc-900 mr-auto">All Users</h1>
        {canAddStaff && (
          <button
            onClick={() => setDialog("invite")}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-semibold bg-white/60 border border-zinc-200 text-zinc-700 hover:border-sage-navy hover:text-sage-navy transition cursor-pointer"
          >
            <Mail className="w-4 h-4" /> Invite participant
          </button>
        )}
        {canAddStaff && (
          <button
            onClick={() => setDialog("staff")}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep shadow-sm transition cursor-pointer"
          >
            <UserPlus className="w-4 h-4" /> Add staff member
          </button>
        )}
      </div>

      {notice && (
        <div
          className={cn(
            "mb-4 px-4 py-3 rounded-lg text-sm flex items-start gap-2",
            notice.kind === "success" ? "bg-emerald-50 border border-emerald-200 text-emerald-800" : "bg-red-50 border border-red-200 text-red-700",
          )}
        >
          <span className="flex-1">{notice.text}</span>
          <button onClick={() => setNotice(null)} className="cursor-pointer" aria-label="Dismiss">
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      <div className="flex items-center gap-2 mb-3">
        <button
          onClick={() => setStatus("active")}
          className={cn(
            "px-4 py-2 rounded-lg text-sm font-semibold transition cursor-pointer",
            status === "active"
              ? "bg-sage-navy text-white shadow-sm"
              : "bg-white/60 border border-zinc-200 text-zinc-600 hover:border-sage-navy hover:text-sage-navy",
          )}
        >
          Active users ({activeCount})
        </button>
        <button
          onClick={() => setStatus("inactive")}
          className={cn(
            "px-4 py-2 rounded-lg text-sm font-semibold transition cursor-pointer",
            status === "inactive"
              ? "bg-red-700 text-white shadow-sm"
              : "bg-white/60 border border-zinc-200 text-zinc-600 hover:border-red-400 hover:text-red-700",
          )}
        >
          Deactivated users ({all.length - activeCount})
        </button>
      </div>

      {/* Filters */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-2 mb-2">
        <div className="relative lg:col-span-2">
          <Search className="w-4 h-4 text-zinc-400 absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search name, email or Participant ID"
            className={FIELD + " pl-9"}
          />
        </div>
        <select value={role} onChange={(e) => { setRole(e.target.value); if (e.target.value !== "PARTICIPANT" && e.target.value !== "ALL") setStage("ALL"); }} className={FIELD} aria-label="Role">
          {ROLE_FILTERS.map((r) => (
            <option key={r.value} value={r.value}>
              {r.label} ({countFor(r.value)})
            </option>
          ))}
        </select>
        <select value={stage} onChange={(e) => { setStage(e.target.value); if (e.target.value !== "ALL") setRole("PARTICIPANT"); }} className={FIELD} aria-label="Participant stage">
          <option value="ALL">Any participant stage</option>
          {STAGES.map((s) => (
            <option key={s.value} value={s.value}>{s.label}</option>
          ))}
        </select>
        <select value={only} onChange={(e) => setOnly(e.target.value)} className={FIELD} aria-label="Needs attention">
          <option value="ALL">Everyone</option>
          <option value="FIRST_SIGNIN">Waiting for first sign-in</option>
          <option value="NOT_VERIFIED">Email not verified</option>
        </select>
      </div>
      <div className="flex items-center gap-3 mb-4 text-xs text-zinc-500">
        <span>
          {shown.length} {shown.length === 1 ? "person" : "people"}
        </span>
        {filtered && (
          <button
            onClick={() => { setQ(""); setRole("ALL"); setStage("ALL"); setOnly("ALL"); }}
            className="font-semibold text-sage-navy hover:underline cursor-pointer"
          >
            Clear filters
          </button>
        )}
        <label className="ml-auto inline-flex items-center gap-1.5">
          Sort
          <select value={sort} onChange={(e) => setSort(e.target.value as "newest" | "name")} className="px-2 py-1 rounded-md border border-zinc-200 bg-white text-zinc-700">
            <option value="newest">Newest first</option>
            <option value="name">Name A–Z</option>
          </select>
        </label>
        <span className="hidden sm:block">Click a row to view and manage</span>
      </div>

      <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-zinc-200 text-zinc-600">
                <th className="text-left px-4 py-3 font-medium">Name</th>
                <th className="text-left px-4 py-3 font-medium">Sign-in email</th>
                <th className="text-left px-4 py-3 font-medium">Role</th>
                <th className="text-left px-4 py-3 font-medium">Participant ID · stage</th>
                <th className="text-left px-4 py-3 font-medium">{status === "active" ? "Joined" : "Status"}</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center">
                    <Loader2 className="w-5 h-5 animate-spin text-sage-navy inline" />
                  </td>
                </tr>
              ) : shown.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-4 py-6 text-center text-zinc-400">
                    {filtered ? "Nobody matches these filters." : status === "active" ? "No active users found." : "No deactivated users."}
                  </td>
                </tr>
              ) : (
                shown.map((u) => {
                  const r = (u.role ?? "").toUpperCase();
                  return (
                    <tr
                      key={u.id}
                      onClick={() => router.push(`/admin/users/${u.id}`)}
                      className="border-b border-zinc-200 hover:bg-sage-navy/5 cursor-pointer transition-colors"
                    >
                      <td className="px-4 py-3 text-zinc-900">
                        {u.fullName}
                        {u.mustChangePassword && (
                          <span className="ml-2 text-[10px] px-2 py-0.5 rounded-full bg-amber-50 text-amber-700 font-semibold whitespace-nowrap">
                            Waiting for first sign-in
                          </span>
                        )}
                        {u.emailVerified === false && (
                          <span className="ml-2 text-[10px] px-2 py-0.5 rounded-full bg-zinc-100 text-zinc-600 font-semibold whitespace-nowrap">
                            Email not verified
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-zinc-600">
                        {u.email}
                        {u.personalEmail && (
                          <div className="text-[11px] text-zinc-400">emails go to {u.personalEmail}</div>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={cn(
                            "text-xs px-2 py-0.5 rounded-full whitespace-nowrap",
                            r === "ADMIN" || r === "SYSTEM_ADMIN"
                              ? "bg-[#C87D5C]/20 text-[#C87D5C]"
                              : r === "INSTRUCTOR"
                                ? "bg-[#1B2A5C]/20 text-[#1B2A5C]"
                                : "bg-white/80 text-zinc-600",
                          )}
                        >
                          {ROLE_LABEL[r] ?? r}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-zinc-600">
                        {r === "PARTICIPANT" ? (
                          <>
                            <span className="font-mono text-xs">{u.participantId ?? "—"}</span>
                            <div className="text-[11px] text-zinc-400">{stageOf(u.currentStatus)}</div>
                          </>
                        ) : (
                          <span className="text-zinc-300">—</span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-zinc-500">
                        {status === "active" ? (
                          formatDateMedium(u.createdAt)
                        ) : (
                          <span className="text-xs px-2 py-0.5 rounded-full bg-red-50 text-red-700">Deactivated</span>
                        )}
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {dialog === "staff" && (
        <AddStaffDialog
          onClose={() => setDialog(null)}
          onDone={async (text, ok) => {
            setDialog(null);
            setNotice({ kind: ok ? "success" : "error", text });
            await load();
          }}
        />
      )}
      {dialog === "invite" && (
        <InviteDialog
          onClose={() => setDialog(null)}
          onDone={(text, ok) => {
            setDialog(null);
            setNotice({ kind: ok ? "success" : "error", text });
          }}
        />
      )}
    </motion.div>
  );
}

function Dialog({ title, subtitle, onClose, children }: {
  title: string;
  subtitle: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-xl max-w-lg w-full p-6 max-h-[90vh] overflow-y-auto" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-start gap-3 mb-1">
          <h2 className="text-lg font-bold text-zinc-900 flex-1">{title}</h2>
          <button onClick={onClose} className="text-zinc-400 hover:text-zinc-700 cursor-pointer" aria-label="Close">
            <X className="w-5 h-5" />
          </button>
        </div>
        <p className="text-xs text-zinc-500 mb-4">{subtitle}</p>
        {children}
      </div>
    </div>
  );
}

function Label({ children, hint }: { children: React.ReactNode; hint?: string }) {
  return (
    <label className="block text-xs font-semibold text-zinc-700 mb-1">
      {children}
      {hint && <span className="block font-normal text-[11px] text-zinc-400 mt-0.5">{hint}</span>}
    </label>
  );
}

/**
 * A staff member: company sign-in email, role and their own email. The
 * temporary password goes to their own email; they choose their password
 * at first sign-in.
 */
function AddStaffDialog({ onClose, onDone }: { onClose: () => void; onDone: (text: string, ok: boolean) => Promise<void> }) {
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [personalEmail, setPersonalEmail] = useState("");
  const [role, setRole] = useState("ERM");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  // Suggest a company address from the name.
  const suggestion = fullName.trim()
    ? fullName.trim().toLowerCase().replace(/[^a-z\s]/g, "").split(/\s+/).filter(Boolean).join(".") + "@sageitco.com"
    : "";

  const submit = async () => {
    setSaving(true);
    setError("");
    try {
      const r = await createStaffUser({ fullName: fullName.trim(), email: email.trim(), personalEmail: personalEmail.trim() || undefined, role });
      await onDone(r.message, r.emailSent);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't add the staff member");
      setSaving(false);
    }
  };

  const ready = fullName.trim().length >= 2 && email.includes("@") && !saving;
  return (
    <Dialog
      title="Add staff member"
      subtitle="They sign in with the company email. A temporary password is emailed to their own email, and they choose their own password when they first sign in."
      onClose={onClose}
    >
      <div className="space-y-3">
        <div>
          <Label>Full name</Label>
          <input value={fullName} onChange={(e) => setFullName(e.target.value)} placeholder="e.g. Riya Sharma" className={FIELD} />
        </div>
        <div>
          <Label hint="What they sign in with (e.g. their @sageitco.com address).">Company email</Label>
          <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder={suggestion || "firstname.lastname@sageitco.com"} className={FIELD} />
          {suggestion && !email && (
            <button type="button" onClick={() => setEmail(suggestion)} className="mt-1 text-[11px] font-semibold text-sage-navy hover:underline cursor-pointer">
              Use {suggestion}
            </button>
          )}
        </div>
        <div>
          <Label hint="The login details and every email from the portal go here. Leave empty if the company email is a real inbox.">
            Their own email
          </Label>
          <input value={personalEmail} onChange={(e) => setPersonalEmail(e.target.value)} placeholder="e.g. riya@gmail.com" className={FIELD} />
        </div>
        <div>
          <Label>Role</Label>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5">
            {STAFF_ROLE_OPTIONS.map((o) => (
              <button
                key={o.value}
                type="button"
                onClick={() => setRole(o.value)}
                className={cn(
                  "text-left px-3 py-2 rounded-lg border text-xs transition cursor-pointer",
                  role === o.value ? "border-sage-navy bg-sage-navy/5" : "border-zinc-200 hover:border-sage-navy",
                )}
              >
                <span className="block font-semibold text-zinc-900">{o.label}</span>
                <span className="block text-[11px] text-zinc-500">{o.hint}</span>
              </button>
            ))}
          </div>
        </div>
      </div>
      {error && <p className="mt-3 text-sm text-red-700">{error}</p>}
      <div className="mt-5 flex justify-end gap-2">
        <button onClick={onClose} className="px-4 py-2 rounded-lg text-sm font-semibold text-zinc-600 hover:text-zinc-900 cursor-pointer">
          Cancel
        </button>
        <button
          onClick={submit}
          disabled={!ready}
          className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 cursor-pointer"
        >
          {saving && <Loader2 className="w-4 h-4 animate-spin" />} Create and email login details
        </button>
      </div>
    </Dialog>
  );
}

/** Participants enroll themselves; this emails them the link with their details filled in. */
/** Also used on the Operations page (Operations admins invite participants too). */
export function InviteDialog({ onClose, onDone }: { onClose: () => void; onDone: (text: string, ok: boolean) => void }) {
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const submit = async () => {
    setSaving(true);
    setError("");
    try {
      const r = await inviteParticipant({ fullName: fullName.trim(), email: email.trim() });
      // A failed email is shown as a problem, not a success.
      onDone(r.emailSent ? `${r.message} to ${email.trim()}.` : `${r.message} (${email.trim()})`, r.emailSent);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't send the invitation");
      setSaving(false);
    }
  };

  return (
    <Dialog
      title="Invite a participant"
      subtitle="Participants enroll themselves: they confirm their email and get their Participant ID. This emails them the enrollment link with their name and email filled in."
      onClose={onClose}
    >
      <div className="space-y-3">
        <div>
          <Label>Full name</Label>
          <input value={fullName} onChange={(e) => setFullName(e.target.value)} placeholder="e.g. Jordan Rivera" className={FIELD} />
        </div>
        <div>
          <Label>Their email</Label>
          <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="e.g. jordan@gmail.com" className={FIELD} />
        </div>
      </div>
      {error && <p className="mt-3 text-sm text-red-700">{error}</p>}
      <div className="mt-5 flex justify-end gap-2">
        <button onClick={onClose} className="px-4 py-2 rounded-lg text-sm font-semibold text-zinc-600 hover:text-zinc-900 cursor-pointer">
          Cancel
        </button>
        <button
          onClick={submit}
          disabled={fullName.trim().length < 2 || !email.includes("@") || saving}
          className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 cursor-pointer"
        >
          {saving && <Loader2 className="w-4 h-4 animate-spin" />} Send invitation
        </button>
      </div>
    </Dialog>
  );
}
