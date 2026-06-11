"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import {
  AlertCircle,
  ArrowLeft,
  Ban,
  Check,
  CheckCircle2,
  Copy,
  FileText,
  KeyRound,
  Loader2,
  RefreshCw,
  ShieldCheck,
  UserPlus,
  Users,
  X,
} from "lucide-react";

import AgreementErmShell from "@/components/agreement-erm/AgreementErmShell";
import AgreementsByErmView from "@/components/agreement-erm/AgreementsByErmView";
import {
  AdminApiError,
  adminCreateUser,
  adminListUsers,
  adminResetUserPassword,
  adminSetUserStatus,
  fetchMe,
  getAgreementErmToken,
  type AgreementMe,
  type AgreementUserDto,
} from "@/lib/api";

type AdminTab = "users" | "agreements";

// One-time credential reveal shown after create / reset. The password
// is never returned by the server again, so the admin must copy it now.
interface RevealedCredential {
  email: string;
  password: string;
  kind: "created" | "reset";
}

export default function AgreementsAdminPage() {
  const router = useRouter();
  const [me, setMe] = useState<AgreementMe | null>(null);
  const [authChecked, setAuthChecked] = useState(false);
  const [users, setUsers] = useState<AgreementUserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const [createOpen, setCreateOpen] = useState(false);
  const [resetTarget, setResetTarget] = useState<AgreementUserDto | null>(null);
  const [revealed, setRevealed] = useState<RevealedCredential | null>(null);
  const [busyRowId, setBusyRowId] = useState<string | null>(null);
  const [tab, setTab] = useState<AdminTab>("users");

  const loadUsers = useCallback(async () => {
    setLoading(true);
    try {
      const list = await adminListUsers();
      setUsers(list);
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load users.");
    } finally {
      setLoading(false);
    }
  }, []);

  // Auth + role gate. Server already enforces 403; this is the UX layer.
  useEffect(() => {
    if (!getAgreementErmToken()) {
      router.replace("/agreements/login");
      return;
    }
    let cancelled = false;
    fetchMe()
      .then((identity) => {
        if (cancelled) return;
        if (identity.role !== "SUPER_ADMIN") {
          router.replace("/agreements");
          return;
        }
        setMe(identity);
        setAuthChecked(true);
        void loadUsers();
      })
      .catch(() => {
        if (!cancelled) router.replace("/agreements/login");
      });
    return () => {
      cancelled = true;
    };
  }, [router, loadUsers]);

  const handleToggleStatus = async (user: AgreementUserDto) => {
    setBusyRowId(user.id);
    setError("");
    try {
      await adminSetUserStatus(user.id, !user.active);
      await loadUsers();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't update status.");
    } finally {
      setBusyRowId(null);
    }
  };

  if (!authChecked) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <AgreementErmShell
      title="Admin console"
      subtitle="Manage agreement-console users and review agreements by ERM."
      Icon={ShieldCheck}
      toolbar={
        <Link
          href="/agreements"
          className="inline-flex items-center gap-1 text-xs font-semibold text-gray-500 hover:text-sage-navy"
        >
          <ArrowLeft size={12} /> Back to list
        </Link>
      }
    >
      <div className="mb-5 inline-flex rounded-lg border border-gray-200 bg-gray-50 p-1 text-xs">
        <button
          type="button"
          onClick={() => setTab("users")}
          className={
            "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md font-semibold cursor-pointer " +
            (tab === "users" ? "bg-sage-navy text-white" : "text-gray-600 hover:text-sage-navy")
          }
        >
          <Users size={12} /> Users
        </button>
        <button
          type="button"
          onClick={() => setTab("agreements")}
          className={
            "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md font-semibold cursor-pointer " +
            (tab === "agreements" ? "bg-sage-navy text-white" : "text-gray-600 hover:text-sage-navy")
          }
        >
          <FileText size={12} /> Agreements by ERM
        </button>
      </div>

      {tab === "agreements" && <AgreementsByErmView />}

      {tab === "users" && (
      <>
      <div className="space-y-4">
        {revealed && (
          <CredentialBanner
            credential={revealed}
            onDismiss={() => setRevealed(null)}
          />
        )}

        <div className="flex items-center justify-between flex-wrap gap-3">
          <p className="text-sm text-gray-500 max-w-xl inline-flex items-center gap-2">
            <Users size={14} className="text-sage-navy" />
            {users.length} console user{users.length === 1 ? "" : "s"}. New
            users are ERMs; share their temporary password out-of-band.
          </p>
          <button
            type="button"
            onClick={() => setCreateOpen(true)}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
          >
            <UserPlus size={12} /> Create user
          </button>
        </div>

        {error && (
          <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
            <AlertCircle size={14} /> {error}
          </p>
        )}

        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
              <tr>
                <th className="text-left px-4 py-2">Email</th>
                <th className="text-left px-4 py-2">Name</th>
                <th className="text-left px-4 py-2">Title</th>
                <th className="text-left px-4 py-2">Role</th>
                <th className="text-left px-4 py-2">Status</th>
                <th className="text-left px-4 py-2">Last login</th>
                <th className="text-right px-4 py-2">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {loading ? (
                <tr>
                  <td colSpan={7} className="text-center py-8">
                    <Loader2 size={18} className="animate-spin text-sage-navy inline" />
                  </td>
                </tr>
              ) : users.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-4 py-6 text-center text-sm text-gray-400 italic">
                    No users yet.
                  </td>
                </tr>
              ) : (
                users.map((u) => {
                  const isSelf = me?.userId === u.id;
                  const isSuperAdmin = u.role === "SUPER_ADMIN";
                  return (
                    <tr key={u.id} className="hover:bg-gray-50">
                      <td className="px-4 py-2 font-medium text-gray-900">{u.email}</td>
                      <td className="px-4 py-2 text-gray-700">{u.fullName}</td>
                      <td className="px-4 py-2 text-gray-500">{u.title}</td>
                      <td className="px-4 py-2">
                        <RoleBadge role={u.role} />
                      </td>
                      <td className="px-4 py-2">
                        <StatusBadge active={u.active} />
                      </td>
                      <td className="px-4 py-2 text-xs text-gray-500">
                        {formatDate(u.lastLoginAt)}
                      </td>
                      <td className="px-4 py-2">
                        <div className="flex items-center justify-end gap-2">
                          {/* Reset is for ERMs only (server blocks super-admins). */}
                          {!isSuperAdmin && (
                            <button
                              type="button"
                              onClick={() => setResetTarget(u)}
                              className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-semibold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer"
                            >
                              <KeyRound size={11} /> Reset
                            </button>
                          )}
                          {/* Hide the disable toggle on the super-admin's own row. */}
                          {!isSelf && (
                            <button
                              type="button"
                              disabled={busyRowId === u.id}
                              onClick={() => handleToggleStatus(u)}
                              className={
                                "inline-flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-semibold border cursor-pointer disabled:opacity-50 " +
                                (u.active
                                  ? "border-red-200 bg-white hover:bg-red-50 text-red-700"
                                  : "border-emerald-200 bg-white hover:bg-emerald-50 text-emerald-700")
                              }
                            >
                              {busyRowId === u.id ? (
                                <Loader2 size={11} className="animate-spin" />
                              ) : u.active ? (
                                <Ban size={11} />
                              ) : (
                                <CheckCircle2 size={11} />
                              )}
                              {u.active ? "Disable" : "Enable"}
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {createOpen && (
        <CreateUserModal
          onClose={() => setCreateOpen(false)}
          onCreated={(email, password) => {
            setCreateOpen(false);
            setRevealed({ email, password, kind: "created" });
            void loadUsers();
          }}
        />
      )}

      {resetTarget && (
        <ResetPasswordModal
          target={resetTarget}
          onClose={() => setResetTarget(null)}
          onReset={(email, password) => {
            setResetTarget(null);
            setRevealed({ email, password, kind: "reset" });
          }}
        />
      )}
      </>
      )}
    </AgreementErmShell>
  );
}

// ── One-time credential banner ──────────────────────────────────

function CredentialBanner({
  credential,
  onDismiss,
}: {
  credential: RevealedCredential;
  onDismiss: () => void;
}) {
  return (
    <div className="rounded-xl border border-sage-copper/40 bg-sage-copper/5 px-4 py-3">
      <div className="flex items-start gap-3">
        <CheckCircle2 size={18} className="text-sage-copper mt-0.5 shrink-0" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-bold text-sage-navy">
            {credential.kind === "created"
              ? "User created"
              : "Password reset"}{" "}
            — share these credentials now
          </p>
          <p className="text-[11px] text-gray-500 mb-2">
            This won&apos;t be shown again. Send it to the user out-of-band.
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <CopyField label="Email" value={credential.email} />
            <CopyField label="Temporary password" value={credential.password} mono />
          </div>
        </div>
        <button
          type="button"
          onClick={onDismiss}
          className="text-gray-400 hover:text-gray-700 cursor-pointer"
          aria-label="Dismiss"
        >
          <X size={16} />
        </button>
      </div>
    </div>
  );
}

function CopyField({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white px-3 py-2">
      <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-400">
        {label}
      </p>
      <div className="flex items-center justify-between gap-2">
        <span className={"text-sm text-gray-900 truncate " + (mono ? "font-mono" : "")}>
          {value}
        </span>
        <CopyButton text={value} />
      </div>
    </div>
  );
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable */
    }
  };
  return (
    <button
      type="button"
      onClick={copy}
      className="shrink-0 inline-flex items-center gap-1 px-1.5 py-1 rounded-md text-[11px] font-semibold text-sage-navy hover:bg-sage-navy/10 cursor-pointer"
    >
      {copied ? <Check size={12} /> : <Copy size={12} />}
      {copied ? "Copied" : "Copy"}
    </button>
  );
}

// ── Create user modal ───────────────────────────────────────────

function CreateUserModal({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: (email: string, password: string) => void;
}) {
  const [email, setEmail] = useState("");
  const [fullName, setFullName] = useState("");
  const [title, setTitle] = useState("");
  const [password, setPassword] = useState("");
  // 3A — assignable console role (never SUPER_ADMIN).
  const [role, setRole] = useState<"ERM" | "MANAGER" | "ACCOUNTS">("ERM");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [emailTaken, setEmailTaken] = useState(false);

  const canSubmit =
    email.trim() && fullName.trim() && title.trim() && password.length >= 8 && !submitting;

  const handleSubmit = async () => {
    setError("");
    setEmailTaken(false);
    if (!canSubmit) {
      setError("Fill every field; password must be at least 8 characters.");
      return;
    }
    setSubmitting(true);
    try {
      await adminCreateUser({
        email: email.trim(),
        fullName: fullName.trim(),
        title: title.trim(),
        temporaryPassword: password,
        role,
      });
      onCreated(email.trim().toLowerCase(), password);
    } catch (e) {
      if (e instanceof AdminApiError && e.status === 409) {
        setEmailTaken(true);
      } else {
        setError(e instanceof Error ? e.message : "Couldn't create user.");
      }
      setSubmitting(false);
    }
  };

  return (
    <Modal title="Create user" onClose={onClose} disabled={submitting}>
      <div className="space-y-3">
        <ModalField label="Email" required>
          <input
            type="email"
            value={email}
            onChange={(e) => {
              setEmail(e.target.value);
              setEmailTaken(false);
            }}
            disabled={submitting}
            autoComplete="off"
            placeholder="erm@sageitco.com"
            className={modalInput}
          />
          {emailTaken && (
            <p className="mt-1 text-[11px] text-red-600 inline-flex items-center gap-1">
              <AlertCircle size={11} /> Email already in use.
            </p>
          )}
        </ModalField>
        <ModalField label="Full name" required>
          <input
            type="text"
            value={fullName}
            onChange={(e) => setFullName(e.target.value)}
            disabled={submitting}
            placeholder="Jane Q. ERM"
            className={modalInput}
          />
        </ModalField>
        <ModalField label="Title" required>
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            disabled={submitting}
            placeholder="Senior ERM"
            className={modalInput}
          />
        </ModalField>
        <ModalField label="Role" required>
          <select
            value={role}
            onChange={(e) =>
              setRole(e.target.value as "ERM" | "MANAGER" | "ACCOUNTS")
            }
            disabled={submitting}
            className={modalInput}
          >
            <option value="ERM">ERM — sends agreements + countersigns</option>
            <option value="MANAGER">Manager — approval gate (Phase 1 + 2)</option>
            <option value="ACCOUNTS">Accounts — approval gate (Phase 2)</option>
          </select>
        </ModalField>
        <ModalField label="Temporary password" required>
          <div className="flex items-center gap-2">
            <input
              type="text"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={submitting}
              autoComplete="off"
              placeholder="At least 8 characters"
              className={modalInput + " font-mono"}
            />
            <button
              type="button"
              onClick={() => setPassword(generatePassword())}
              disabled={submitting}
              className="shrink-0 inline-flex items-center gap-1 px-2.5 py-2 rounded-md text-[11px] font-semibold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer"
            >
              <RefreshCw size={11} /> Generate
            </button>
            {password.length >= 8 && <CopyButton text={password} />}
          </div>
          <p className="mt-1 text-[10px] text-gray-400">
            Shown once after creation — you share it with the user manually.
          </p>
        </ModalField>

        {error && (
          <p className="text-[11px] text-red-600 inline-flex items-center gap-1">
            <AlertCircle size={11} /> {error}
          </p>
        )}
      </div>

      <ModalActions
        onClose={onClose}
        onSubmit={handleSubmit}
        submitting={submitting}
        submitLabel="Create user"
        canSubmit={!!canSubmit}
      />
    </Modal>
  );
}

// ── Reset password modal ────────────────────────────────────────

function ResetPasswordModal({
  target,
  onClose,
  onReset,
}: {
  target: AgreementUserDto;
  onClose: () => void;
  onReset: (email: string, password: string) => void;
}) {
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  const canSubmit = password.length >= 8 && !submitting;

  const handleSubmit = async () => {
    setError("");
    if (!canSubmit) {
      setError("Password must be at least 8 characters.");
      return;
    }
    setSubmitting(true);
    try {
      await adminResetUserPassword(target.id, password);
      onReset(target.email, password);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't reset password.");
      setSubmitting(false);
    }
  };

  return (
    <Modal title={`Reset password — ${target.email}`} onClose={onClose} disabled={submitting}>
      <div className="space-y-3">
        <ModalField label="New temporary password" required>
          <div className="flex items-center gap-2">
            <input
              type="text"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={submitting}
              autoComplete="off"
              placeholder="At least 8 characters"
              className={modalInput + " font-mono"}
            />
            <button
              type="button"
              onClick={() => setPassword(generatePassword())}
              disabled={submitting}
              className="shrink-0 inline-flex items-center gap-1 px-2.5 py-2 rounded-md text-[11px] font-semibold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer"
            >
              <RefreshCw size={11} /> Generate
            </button>
            {password.length >= 8 && <CopyButton text={password} />}
          </div>
        </ModalField>

        {error && (
          <p className="text-[11px] text-red-600 inline-flex items-center gap-1">
            <AlertCircle size={11} /> {error}
          </p>
        )}
      </div>

      <ModalActions
        onClose={onClose}
        onSubmit={handleSubmit}
        submitting={submitting}
        submitLabel="Reset password"
        canSubmit={canSubmit}
      />
    </Modal>
  );
}

// ── Small shared UI ─────────────────────────────────────────────

const modalInput =
  "w-full px-3 py-2 text-sm rounded-md border border-gray-200 " +
  "focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy " +
  "disabled:bg-gray-50 disabled:text-gray-500";

function Modal({
  title,
  onClose,
  disabled,
  children,
}: {
  title: string;
  onClose: () => void;
  disabled?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40">
      <div className="w-full max-w-md bg-white rounded-2xl border border-gray-100 shadow-xl">
        <div className="flex items-center justify-between px-5 py-3 border-b border-gray-100">
          <h2 className="text-sm font-bold text-sage-navy truncate pr-2">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            disabled={disabled}
            className="text-gray-400 hover:text-gray-700 cursor-pointer disabled:opacity-50"
            aria-label="Close"
          >
            <X size={16} />
          </button>
        </div>
        <div className="px-5 py-4">{children}</div>
      </div>
    </div>
  );
}

function ModalActions({
  onClose,
  onSubmit,
  submitting,
  submitLabel,
  canSubmit,
}: {
  onClose: () => void;
  onSubmit: () => void;
  submitting: boolean;
  submitLabel: string;
  canSubmit: boolean;
}) {
  return (
    <div className="flex items-center justify-end gap-2 pt-4 mt-1 border-t border-gray-100">
      <button
        type="button"
        onClick={onClose}
        disabled={submitting}
        className="px-3 py-2 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer disabled:opacity-50"
      >
        Cancel
      </button>
      <button
        type="button"
        onClick={onSubmit}
        disabled={!canSubmit}
        className="inline-flex items-center justify-center gap-1.5 px-4 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 disabled:cursor-not-allowed cursor-pointer"
      >
        {submitting && <Loader2 size={12} className="animate-spin" />}
        {submitLabel}
      </button>
    </div>
  );
}

function ModalField({
  label,
  required,
  children,
}: {
  label: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label className="block text-[11px] font-semibold text-gray-600 mb-0.5">
        {label}
        {required && <span className="text-red-500"> *</span>}
      </label>
      {children}
    </div>
  );
}

function RoleBadge({ role }: { role: string }) {
  // 3A — distinct styling per console role.
  const meta: Record<string, { cls: string; label: string }> = {
    SUPER_ADMIN: { cls: "bg-sage-navy/10 text-sage-navy", label: "Super admin" },
    ERM: { cls: "bg-gray-100 text-gray-600", label: "ERM" },
    MANAGER: { cls: "bg-violet-50 text-violet-700", label: "Manager" },
    ACCOUNTS: { cls: "bg-teal-50 text-teal-700", label: "Accounts" },
  };
  const m = meta[role] ?? meta.ERM;
  return (
    <span
      className={
        "inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold " +
        m.cls
      }
    >
      {m.label}
    </span>
  );
}

function StatusBadge({ active }: { active: boolean }) {
  return (
    <span
      className={
        "inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold " +
        (active
          ? "bg-emerald-50 text-emerald-700"
          : "bg-red-50 text-red-700")
      }
    >
      {active ? "Active" : "Disabled"}
    </span>
  );
}

function formatDate(iso: string | null | undefined) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("en-IN", {
    timeZone: "Asia/Kolkata",
    dateStyle: "medium",
    timeStyle: "short",
  });
}

/**
 * 12-char password from a mixed charset, drawn from crypto.getRandomValues
 * so it's not predictable. The admin shares it manually after creation.
 */
function generatePassword(): string {
  const charset =
    "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
  const len = 12;
  const out: string[] = [];
  if (typeof window !== "undefined" && window.crypto?.getRandomValues) {
    const buf = new Uint32Array(len);
    window.crypto.getRandomValues(buf);
    for (let i = 0; i < len; i++) {
      out.push(charset[buf[i] % charset.length]);
    }
  } else {
    for (let i = 0; i < len; i++) {
      out.push(charset[Math.floor(Math.random() * charset.length)]);
    }
  }
  return out.join("");
}
