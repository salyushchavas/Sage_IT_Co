"use client";

import { useEffect, useState } from "react";
import {
  AlertCircle,
  Check,
  CheckCircle2,
  Loader2,
  Mail,
  RefreshCw,
  X,
} from "lucide-react";

import {
  AdminApiError,
  adminChangeUserRole,
  adminCreateUser,
  adminDeleteUser,
  adminGetErmAssignments,
  adminResetUserPassword,
  adminSendUserCredentials,
  adminSetErmAssignments,
  adminUpdateUserDetails,
  type AgreementUserDto,
} from "@/lib/api";
import {
  BTN_SECONDARY,
  CopyButton,
  CopyField,
  Modal,
  ModalActions,
  ModalField,
  Spinner,
  generatePassword,
  modalInput,
} from "./primitives";

/**
 * Every dialog the People tab opens.
 *
 * These all used to live at the bottom of src/app/agreements/admin/page.tsx,
 * where three of them (assign team, change role, delete) hand-rolled their own
 * overlay, a `font-serif` heading and a bordered Cancel button while the other
 * three used the page's shared Modal. Same console, two visual languages. They
 * are now uniformly built on Modal / ModalActions / ModalField from
 * primitives.tsx, so the only thing that varies between them is the body.
 *
 * The rules encoded here are deliberate and server-mirrored — the email
 * conflict test, the super-admin email lock, the 8-character minimum — so they
 * were carried across unchanged.
 */

/** Roles a super-admin may hand out. SUPER_ADMIN is never user-assignable. */
type AssignableRole = "ERM" | "MANAGER" | "ACCOUNTS";

/**
 * One-time credential reveal shown after create / reset. The server never
 * returns the password again (it is hashed on write), so the admin has to copy
 * it now — or have the banner POST it to the send-credentials endpoint, which
 * is why the user id travels alongside it.
 */
export interface RevealedCredential {
  id: string;
  email: string;
  password: string;
  kind: "created" | "reset";
}

// ── One-time credential banner ──────────────────────────────────

export function CredentialBanner({
  credential,
  onDismiss,
}: {
  credential: RevealedCredential;
  onDismiss: () => void;
}) {
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [sendError, setSendError] = useState("");

  const emailToUser = async () => {
    setSending(true);
    setSendError("");
    try {
      await adminSendUserCredentials(credential.id, credential.password);
      setSent(true);
    } catch (e) {
      setSendError(
        e instanceof Error ? e.message : "Couldn't email the credentials.",
      );
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="rounded-xl border border-sage-copper/40 bg-sage-copper/5 px-4 py-3">
      <div className="flex items-start gap-3">
        <CheckCircle2 size={18} className="text-sage-copper mt-0.5 shrink-0" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-bold text-sage-navy">
            {credential.kind === "created" ? "User created" : "Password reset"} —
            share these credentials now
          </p>
          <p className="text-[11px] text-gray-500 mb-2">
            This won&apos;t be shown again. Copy it, or email it to the user
            (from noreply@sageitco.com).
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <CopyField label="Email" value={credential.email} />
            <CopyField
              label="Temporary password"
              value={credential.password}
              mono
            />
          </div>
          <div className="mt-2 flex items-center gap-3 flex-wrap">
            <button
              type="button"
              onClick={() => void emailToUser()}
              disabled={sending || sent}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer disabled:opacity-50"
            >
              {sending ? (
                <Loader2 size={12} className="animate-spin" />
              ) : sent ? (
                <Check size={12} />
              ) : (
                <Mail size={12} />
              )}
              {sent ? `Emailed to ${credential.email}` : "Email to user"}
            </button>
            {sendError && (
              <span className="text-[11px] text-red-600 inline-flex items-center gap-1">
                <AlertCircle size={11} /> {sendError}
              </span>
            )}
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

// ── Shared bits ─────────────────────────────────────────────────

/** Inline form-level error. Every modal below renders failures the same way. */
function FieldError({ children }: { children: string }) {
  return (
    <p className="text-[11px] text-red-600 inline-flex items-start gap-1">
      <AlertCircle size={11} className="mt-0.5 shrink-0" /> {children}
    </p>
  );
}

/** Password input + generate + copy. Shared by create and reset verbatim. */
function PasswordRow({
  value,
  onChange,
  disabled,
}: {
  value: string;
  onChange: (next: string) => void;
  disabled: boolean;
}) {
  return (
    <div className="flex items-center gap-2">
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        autoComplete="off"
        placeholder="At least 8 characters"
        className={modalInput + " font-mono"}
      />
      <button
        type="button"
        onClick={() => onChange(generatePassword())}
        disabled={disabled}
        className={BTN_SECONDARY + " shrink-0"}
      >
        <RefreshCw size={11} /> Generate
      </button>
      {/* Copy only once it is actually usable — the server rejects < 8. */}
      {value.length >= 8 && <CopyButton text={value} />}
    </div>
  );
}

// ── Create user ─────────────────────────────────────────────────

export function CreateUserModal({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: (id: string, email: string, password: string) => void;
}) {
  const [email, setEmail] = useState("");
  const [fullName, setFullName] = useState("");
  const [title, setTitle] = useState("");
  const [password, setPassword] = useState("");
  // 3A — assignable console role (never SUPER_ADMIN).
  const [role, setRole] = useState<AssignableRole>("ERM");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [emailTaken, setEmailTaken] = useState(false);

  const canSubmit =
    email.trim().length > 0 &&
    fullName.trim().length > 0 &&
    title.trim().length > 0 &&
    password.length >= 8 &&
    !submitting;

  const handleSubmit = async () => {
    setError("");
    setEmailTaken(false);
    if (!canSubmit) {
      setError("Fill every field; password must be at least 8 characters.");
      return;
    }
    setSubmitting(true);
    try {
      const created = await adminCreateUser({
        email: email.trim(),
        fullName: fullName.trim(),
        title: title.trim(),
        temporaryPassword: password,
        role,
      });
      onCreated(created.id, email.trim().toLowerCase(), password);
    } catch (e) {
      // Build K3 — only the genuine "email already in use" conflict should
      // show the inline email error. Every other server error (incl. other
      // 409s like a data-integrity failure) is surfaced verbatim instead of
      // being mislabeled as a duplicate email.
      if (e instanceof AdminApiError) {
        const msg = e.message ?? "";
        const isEmailConflict =
          /email/i.test(msg) && /(in use|exist|taken|already|duplicate)/i.test(msg);
        if (isEmailConflict) {
          setEmailTaken(true);
        } else {
          setError(msg || `Couldn't create user (status ${e.status}).`);
        }
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
            onChange={(e) => setRole(e.target.value as AssignableRole)}
            disabled={submitting}
            className={modalInput}
          >
            <option value="ERM">ERM — sends agreements + countersigns</option>
            <option value="MANAGER">Manager — approval gate (Phase 1 + 2)</option>
            <option value="ACCOUNTS">Accounts — approval gate (Phase 2)</option>
          </select>
        </ModalField>
        <ModalField
          label="Temporary password"
          required
          hint="Shown once after creation — you share it with the user manually."
        >
          <PasswordRow
            value={password}
            onChange={setPassword}
            disabled={submitting}
          />
        </ModalField>

        {error && <FieldError>{error}</FieldError>}
      </div>

      <ModalActions
        onClose={onClose}
        onSubmit={() => void handleSubmit()}
        submitting={submitting}
        submitLabel="Create user"
        canSubmit={canSubmit}
      />
    </Modal>
  );
}

// ── Reset password ──────────────────────────────────────────────

export function ResetPasswordModal({
  target,
  onClose,
  onReset,
}: {
  target: AgreementUserDto;
  onClose: () => void;
  onReset: (id: string, email: string, password: string) => void;
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
      onReset(target.id, target.email, password);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't reset password.");
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="Reset password"
      subtitle={target.email}
      onClose={onClose}
      disabled={submitting}
    >
      <div className="space-y-3">
        <ModalField label="New temporary password" required>
          <PasswordRow
            value={password}
            onChange={setPassword}
            disabled={submitting}
          />
        </ModalField>

        {error && <FieldError>{error}</FieldError>}
      </div>

      <ModalActions
        onClose={onClose}
        onSubmit={() => void handleSubmit()}
        submitting={submitting}
        submitLabel="Reset password"
        canSubmit={canSubmit}
      />
    </Modal>
  );
}

// ── Edit details (name + email + title) ─────────────────────────

export function EditDetailsModal({
  user,
  onClose,
  onDone,
}: {
  user: AgreementUserDto;
  onClose: () => void;
  onDone: () => void;
}) {
  const [fullName, setFullName] = useState(user.fullName ?? "");
  const [title, setTitle] = useState(user.title ?? "");
  const [email, setEmail] = useState(user.email ?? "");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  // Build AN — the super-admin's login email is provisioned from the
  // environment at boot; editing it here would mint a duplicate admin on the
  // next restart, so the field is locked for that account.
  const emailLocked = user.role === "SUPER_ADMIN";

  const trimmedName = fullName.trim();
  const trimmedTitle = title.trim();
  const trimmedEmail = email.trim().toLowerCase();
  const emailChanged =
    !emailLocked && trimmedEmail !== (user.email ?? "").trim().toLowerCase();
  const emailValid =
    !emailChanged || /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(trimmedEmail);
  const changed =
    trimmedName !== (user.fullName ?? "").trim() ||
    trimmedTitle !== (user.title ?? "").trim() ||
    emailChanged;
  const canSubmit =
    trimmedName.length > 0 &&
    trimmedTitle.length > 0 &&
    emailValid &&
    changed &&
    !submitting;

  const handleSubmit = async () => {
    setError("");
    if (trimmedName.length === 0 || trimmedTitle.length === 0) {
      setError("Name and title are both required.");
      return;
    }
    if (!emailValid) {
      setError("Enter a valid email address.");
      return;
    }
    setSubmitting(true);
    try {
      await adminUpdateUserDetails(user.id, {
        fullName: trimmedName,
        title: trimmedTitle,
        // Omitting the key leaves the address untouched server-side.
        ...(emailChanged ? { email: trimmedEmail } : {}),
      });
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't update details.");
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="Edit details"
      subtitle={user.email}
      onClose={onClose}
      disabled={submitting}
    >
      <div className="space-y-3">
        <ModalField label="Full name" required>
          <input
            type="text"
            value={fullName}
            onChange={(e) => setFullName(e.target.value)}
            disabled={submitting}
            placeholder="Jane Q. ERM"
            className={modalInput}
            autoFocus
          />
        </ModalField>
        <ModalField
          label="Login email"
          required
          hint={
            emailLocked
              ? "The super-admin email is set from the environment and can't be changed here."
              : "Changing the email takes effect at their next sign-in — they'll log in with the new address."
          }
        >
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={submitting || emailLocked}
            placeholder="jane@sageitco.com"
            className={modalInput}
          />
        </ModalField>
        <ModalField
          label="Title"
          required
          hint="Name + title show in the console and stamp into the agreement signature block. To change the role, use the role action."
        >
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            disabled={submitting}
            placeholder="e.g. Program Manager"
            className={modalInput}
          />
        </ModalField>

        {error && <FieldError>{error}</FieldError>}
      </div>

      <ModalActions
        onClose={onClose}
        onSubmit={() => void handleSubmit()}
        submitting={submitting}
        submitLabel="Save details"
        canSubmit={canSubmit}
      />
    </Modal>
  );
}

// ── Assign team (Build K) ───────────────────────────────────────

export function AssignTeamModal({
  erm,
  allUsers,
  onClose,
}: {
  erm: AgreementUserDto;
  allUsers: AgreementUserDto[];
  onClose: () => void;
}) {
  const [managerIds, setManagerIds] = useState<Set<string>>(new Set());
  const [accountsIds, setAccountsIds] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  // Disabled approvers are deliberately not offered: routing an agreement to
  // an account that can't sign in would strand it.
  const managers = allUsers.filter((u) => u.role === "MANAGER" && u.active);
  const accounts = allUsers.filter((u) => u.role === "ACCOUNTS" && u.active);

  useEffect(() => {
    let cancelled = false;
    adminGetErmAssignments(erm.id)
      .then((a) => {
        if (cancelled) return;
        setManagerIds(new Set(a.managerIds));
        setAccountsIds(new Set(a.accountsIds));
      })
      .catch((e) =>
        setError(
          e instanceof AdminApiError ? e.message : "Couldn't load assignments.",
        ),
      )
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [erm.id]);

  const toggle = (
    set: Set<string>,
    setter: (s: Set<string>) => void,
    id: string,
  ) => {
    const next = new Set(set);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setter(next);
  };

  const handleSave = async () => {
    setSaving(true);
    setError("");
    try {
      await adminSetErmAssignments(erm.id, {
        managerIds: Array.from(managerIds),
        accountsIds: Array.from(accountsIds),
      });
      onClose();
    } catch (e) {
      setError(
        e instanceof AdminApiError ? e.message : "Couldn't save assignments.",
      );
      setSaving(false);
    }
  };

  const renderList = (
    heading: string,
    options: AgreementUserDto[],
    selected: Set<string>,
    setter: (s: Set<string>) => void,
  ) => (
    <div>
      <p className="text-[10px] font-semibold uppercase tracking-wider text-sage-navy mb-2">
        {heading}
      </p>
      {options.length === 0 ? (
        <p className="text-xs text-gray-400 italic">
          No active {heading.toLowerCase()} users exist yet.
        </p>
      ) : (
        <div className="space-y-1.5 max-h-44 overflow-y-auto pr-1">
          {options.map((u) => (
            <label
              key={u.id}
              className="flex items-start gap-2 px-2 py-1.5 rounded-md border border-gray-100 hover:bg-gray-50 cursor-pointer"
            >
              <input
                type="checkbox"
                checked={selected.has(u.id)}
                onChange={() => toggle(selected, setter, u.id)}
                disabled={saving}
                className="mt-0.5 h-4 w-4 accent-sage-navy cursor-pointer"
              />
              <span className="text-[13px] leading-tight">
                <span className="font-medium text-gray-900">{u.fullName}</span>
                <span className="block text-[11px] text-gray-500">{u.email}</span>
              </span>
            </label>
          ))}
        </div>
      )}
    </div>
  );

  return (
    <Modal
      title="Assign team"
      subtitle={`${erm.fullName} · ${erm.email}`}
      onClose={onClose}
      disabled={saving}
      width="lg"
    >
      <div className="space-y-4">
        <p className="text-[12px] text-gray-500">
          Choose which Managers and Accounts this ERM can route agreements to.
          These drive the approval pickers when the ERM sends for approval.
        </p>
        {loading ? (
          <Spinner />
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
            {renderList("Managers", managers, managerIds, setManagerIds)}
            {renderList("Accounts", accounts, accountsIds, setAccountsIds)}
          </div>
        )}
        {error && <FieldError>{error}</FieldError>}
      </div>

      <ModalActions
        onClose={onClose}
        onSubmit={() => void handleSave()}
        submitting={saving}
        submitLabel="Save assignments"
        canSubmit={!loading && !saving}
      />
    </Modal>
  );
}

// ── Change role (Build K2) ──────────────────────────────────────

// Build K2 — assignable role options (SUPER_ADMIN is never user-assignable).
const ROLE_OPTIONS: { value: AssignableRole; label: string }[] = [
  { value: "ERM", label: "ERM — creates & manages agreements" },
  { value: "MANAGER", label: "Manager — approval gate (Phase 1 + 2)" },
  { value: "ACCOUNTS", label: "Accounts — approval gate (Phase 2)" },
];

export function ChangeRoleModal({
  user,
  onClose,
  onDone,
}: {
  user: AgreementUserDto;
  onClose: () => void;
  onDone: () => void;
}) {
  const [role, setRole] = useState<AssignableRole>(
    user.role === "MANAGER" || user.role === "ACCOUNTS" ? user.role : "ERM",
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const changed = role !== user.role;

  const handleSave = async () => {
    setSaving(true);
    setError("");
    try {
      await adminChangeUserRole(user.id, role);
      onDone();
    } catch (e) {
      setError(e instanceof AdminApiError ? e.message : "Couldn't change role.");
      setSaving(false);
    }
  };

  return (
    <Modal
      title="Change role"
      subtitle={`${user.fullName} · ${user.email}`}
      onClose={onClose}
      disabled={saving}
    >
      <div className="space-y-3">
        <ModalField label="Role" required>
          <select
            value={role}
            onChange={(e) => setRole(e.target.value as AssignableRole)}
            disabled={saving}
            className={modalInput}
          >
            {ROLE_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </ModalField>
        {/* Only warn once the selection actually differs — the side effects
            below only fire on a real role change. */}
        {changed && (
          <p className="text-[11px] text-amber-700 bg-amber-50 border border-amber-200 rounded-md px-3 py-2 flex items-start gap-1.5">
            <AlertCircle size={12} className="mt-0.5 shrink-0" />
            Changing the role clears this user&apos;s team assignments and
            un-routes any pending approvals routed to them.
          </p>
        )}
        {error && <FieldError>{error}</FieldError>}
      </div>

      <ModalActions
        onClose={onClose}
        onSubmit={() => void handleSave()}
        submitting={saving}
        submitLabel="Save role"
        canSubmit={changed && !saving}
      />
    </Modal>
  );
}

// ── Delete user (Build K2) ──────────────────────────────────────

export function DeleteUserModal({
  user,
  onClose,
  onDone,
}: {
  user: AgreementUserDto;
  onClose: () => void;
  onDone: () => void;
}) {
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState("");

  const handleDelete = async () => {
    setDeleting(true);
    setError("");
    try {
      await adminDeleteUser(user.id);
      onDone();
    } catch (e) {
      setError(e instanceof AdminApiError ? e.message : "Couldn't delete user.");
      setDeleting(false);
    }
  };

  return (
    <Modal
      title="Delete user"
      subtitle={`${user.fullName} · ${user.email}`}
      onClose={onClose}
      disabled={deleting}
    >
      <div className="space-y-3">
        <p className="text-[13px] text-gray-700">
          Permanently delete{" "}
          <span className="font-semibold">{user.fullName}</span> ({user.email})?
          This frees the email for reuse and removes their team assignments.
          Past approval decisions stay in the audit trail.
        </p>
        <p className="text-[11px] text-gray-500">
          Prefer <span className="font-semibold">Disable</span> if you only want
          to revoke access. An ERM that still owns agreements can&apos;t be
          deleted.
        </p>
        {error && <FieldError>{error}</FieldError>}
      </div>

      <ModalActions
        onClose={onClose}
        onSubmit={() => void handleDelete()}
        submitting={deleting}
        submitLabel="Delete user"
        canSubmit={!deleting}
        danger
      />
    </Modal>
  );
}
