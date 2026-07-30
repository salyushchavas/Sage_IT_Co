"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  Ban,
  CheckCircle2,
  KeyRound,
  Loader2,
  MoreHorizontal,
  Pencil,
  Search,
  Trash2,
  UserCog,
  UserPlus,
  Users,
  type LucideIcon,
} from "lucide-react";

import {
  adminListUsers,
  adminSetUserStatus,
  fetchMe,
  type AgreementMe,
  type AgreementUserDto,
  type AgreementUserRole,
} from "@/lib/api";
import { formatUsDateTime } from "@/lib/dates";
import {
  BTN_PRIMARY,
  Chip,
  EmptyState,
  InlineAlert,
  SectionCard,
  Spinner,
  TD,
  TH,
  TableShell,
  modalInput,
} from "./primitives";
import {
  AssignTeamModal,
  ChangeRoleModal,
  CreateUserModal,
  CredentialBanner,
  DeleteUserModal,
  EditDetailsModal,
  ResetPasswordModal,
  type RevealedCredential,
} from "./UserModals";

/**
 * People tab — every console account, grouped by what the account can do.
 *
 * The old version was one flat seven-column table with up to six buttons in
 * the last cell. At the console's max-w-6xl that row wrapped onto three lines
 * per user, so a ten-person org read as a wall of buttons and the actual
 * information (who is an approver? who has never signed in?) was unreadable.
 *
 * Two changes fix it. Roles become sections rather than a column, because role
 * is the only thing that changes what a person can do in this system — an ERM
 * and an Accounts user share no capability. And the six per-row actions
 * collapse behind one overflow menu, so a row is now name / title / status /
 * last login and nothing else.
 *
 * The permission rules are unchanged and still mirror the server: the
 * super-admin can't be deleted, role-changed or password-reset from here, and
 * you can't disable, demote or delete yourself.
 */

const ROLE_FILTERS: ReadonlyArray<{
  value: AgreementUserRole | "ALL";
  label: string;
}> = [
  { value: "ALL", label: "All" },
  { value: "ERM", label: "ERM" },
  { value: "MANAGER", label: "Manager" },
  { value: "ACCOUNTS", label: "Accounts" },
  { value: "SUPER_ADMIN", label: "Super admin" },
];

/**
 * Section order is escalating reach, not alphabetical: the accounts that can
 * change the system come first, then the ones that move agreements, then the
 * two approval gates.
 */
const ROLE_SECTIONS: ReadonlyArray<{
  role: AgreementUserRole;
  label: string;
  blurb: string;
}> = [
  {
    role: "SUPER_ADMIN",
    label: "Super admin",
    blurb: "Full console access — users, every agreement, maintenance.",
  },
  {
    role: "ERM",
    label: "ERM",
    blurb: "Sends agreements to consultants and countersigns them.",
  },
  {
    role: "MANAGER",
    label: "Manager",
    blurb: "Approval gate — required on Phase 1 and Phase 2.",
  },
  {
    role: "ACCOUNTS",
    label: "Accounts",
    blurb: "Approval gate — required on Phase 2 only.",
  },
];

/** `initialRole` arrives from a `?filter=` deep link, so it is untrusted. */
function normalizeRole(raw: string | undefined): AgreementUserRole | null {
  if (!raw) return null;
  const upper = raw.trim().toUpperCase();
  const hit = ROLE_SECTIONS.find((s) => s.role === upper);
  return hit ? hit.role : null;
}

export default function AdminPeopleTab({
  initialRole,
}: {
  initialRole?: string;
}) {
  const [me, setMe] = useState<AgreementMe | null>(null);
  const [users, setUsers] = useState<AgreementUserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const [roleFilter, setRoleFilter] = useState<AgreementUserRole | "ALL">(
    () => normalizeRole(initialRole) ?? "ALL",
  );
  const [query, setQuery] = useState("");
  const [activeOnly, setActiveOnly] = useState(false);

  const [createOpen, setCreateOpen] = useState(false);
  const [resetTarget, setResetTarget] = useState<AgreementUserDto | null>(null);
  const [assignTarget, setAssignTarget] = useState<AgreementUserDto | null>(null);
  const [roleTarget, setRoleTarget] = useState<AgreementUserDto | null>(null);
  const [detailsTarget, setDetailsTarget] = useState<AgreementUserDto | null>(
    null,
  );
  const [deleteTarget, setDeleteTarget] = useState<AgreementUserDto | null>(null);
  const [revealed, setRevealed] = useState<RevealedCredential | null>(null);
  const [busyRowId, setBusyRowId] = useState<string | null>(null);

  // A later navigation into this tab (e.g. clicking "3 ERMs" on the overview)
  // re-seeds the filter; a manual filter change is not clobbered because this
  // only fires when the incoming value itself changes.
  useEffect(() => {
    const seeded = normalizeRole(initialRole);
    if (seeded) setRoleFilter(seeded);
  }, [initialRole]);

  const loadUsers = useCallback(async () => {
    try {
      const list = await adminListUsers();
      setUsers(list);
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load users.");
    }
  }, []);

  // Identity is fetched before the rows render, not alongside them: the menu
  // hides self-destructive actions by comparing against it, and a row drawn
  // while `me` is still null would briefly offer the super-admin a "Disable"
  // on their own account.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const identity = await fetchMe();
        if (cancelled) return;
        setMe(identity);
        const list = await adminListUsers();
        if (cancelled) return;
        setUsers(list);
        setError("");
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : "Couldn't load users.");
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

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

  const q = query.trim().toLowerCase();
  const visible = users.filter((u) => {
    if (roleFilter !== "ALL" && u.role !== roleFilter) return false;
    if (activeOnly && !u.active) return false;
    if (!q) return true;
    // join() renders a null field as "", so this is safe on partial rows.
    return [u.fullName, u.email, u.title].join(" ").toLowerCase().includes(q);
  });

  const filtering = roleFilter !== "ALL" || activeOnly || q.length > 0;

  return (
    <div className="space-y-4">
      {revealed && (
        <CredentialBanner
          credential={revealed}
          onDismiss={() => setRevealed(null)}
        />
      )}

      {error && (
        <InlineAlert tone="error" onDismiss={() => setError("")}>
          {error}
        </InlineAlert>
      )}

      {/* ── Header strip ───────────────────────────────────────── */}
      <SectionCard>
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <p className="text-sm text-gray-600 inline-flex items-center gap-2">
            <Users size={14} className="text-sage-navy" />
            <span>
              <span className="font-bold text-sage-navy tabular-nums">
                {filtering ? `${visible.length} of ${users.length}` : users.length}
              </span>{" "}
              console user{users.length === 1 ? "" : "s"}
            </span>
          </p>
          <button
            type="button"
            onClick={() => setCreateOpen(true)}
            className={BTN_PRIMARY}
          >
            <UserPlus size={12} /> Create user
          </button>
        </div>

        <div className="mt-3 flex items-center gap-3 flex-wrap">
          <div className="inline-flex rounded-lg border border-gray-200 bg-gray-50 p-1 text-xs">
            {ROLE_FILTERS.map((f) => {
              const count =
                f.value === "ALL"
                  ? users.length
                  : users.filter((u) => u.role === f.value).length;
              return (
                <button
                  key={f.value}
                  type="button"
                  onClick={() => setRoleFilter(f.value)}
                  // Selection was navy-fill only, which is colour alone.
                  aria-pressed={roleFilter === f.value}
                  className={
                    "inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md font-semibold cursor-pointer " +
                    (roleFilter === f.value
                      ? "bg-sage-navy text-white"
                      : "text-gray-600 hover:text-sage-navy")
                  }
                >
                  {f.label}
                  <span
                    className={
                      "tabular-nums text-[10px] " +
                      (roleFilter === f.value ? "text-white/70" : "text-gray-400")
                    }
                  >
                    {count}
                  </span>
                </button>
              );
            })}
          </div>

          <div className="relative flex-1 min-w-[180px]">
            <Search
              size={13}
              className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400"
            />
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search name, email or title"
              className={modalInput + " pl-8"}
            />
          </div>

          <label className="inline-flex items-center gap-1.5 text-[12px] text-gray-600 cursor-pointer whitespace-nowrap">
            <input
              type="checkbox"
              checked={activeOnly}
              onChange={(e) => setActiveOnly(e.target.checked)}
              className="h-3.5 w-3.5 accent-sage-navy cursor-pointer"
            />
            Active only
          </label>
        </div>
      </SectionCard>

      {/* ── One card per role ──────────────────────────────────── */}
      {loading ? (
        <SectionCard>
          <Spinner label="Loading users…" />
        </SectionCard>
      ) : visible.length === 0 ? (
        <SectionCard>
          {/* An empty list after a FAILED load is not an empty list — saying
              "No users yet" there invites the admin to create a duplicate of
              someone who already exists. */}
          <EmptyState
            Icon={Users}
            title={
              error
                ? "Couldn't load users"
                : filtering
                  ? "No users match"
                  : "No users yet"
            }
            hint={
              error
                ? "The list above is empty because the request failed, not because there are no users."
                : filtering
                  ? "Clear the role filter, the search or the active-only toggle."
                  : "Create the first console user to get started."
            }
          />
        </SectionCard>
      ) : (
        ROLE_SECTIONS.map((section) => {
          const rows = visible.filter((u) => u.role === section.role);
          // A role with nothing in it after filtering is noise, not news.
          if (rows.length === 0) return null;
          return (
            <SectionCard
              key={section.role}
              title={section.label}
              description={section.blurb}
              action={
                <Chip>
                  {rows.length} {rows.length === 1 ? "user" : "users"}
                </Chip>
              }
              padded={false}
            >
              <TableShell
                head={
                  <tr>
                    <th className={TH}>Name</th>
                    <th className={TH}>Title</th>
                    <th className={TH}>Status</th>
                    <th className={TH}>Last login</th>
                    <th className={TH + " text-right"}>
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                }
              >
                {rows.map((u) => (
                  <PersonRow
                    key={u.id}
                    user={u}
                    isSelf={me?.userId === u.id}
                    busy={busyRowId === u.id}
                    onEditDetails={() => setDetailsTarget(u)}
                    onAssignTeam={() => setAssignTarget(u)}
                    onChangeRole={() => setRoleTarget(u)}
                    onResetPassword={() => setResetTarget(u)}
                    onToggleStatus={() => void handleToggleStatus(u)}
                    onDelete={() => setDeleteTarget(u)}
                  />
                ))}
              </TableShell>
            </SectionCard>
          );
        })
      )}

      {createOpen && (
        <CreateUserModal
          onClose={() => setCreateOpen(false)}
          onCreated={(id, email, password) => {
            setCreateOpen(false);
            setRevealed({ id, email, password, kind: "created" });
            void loadUsers();
          }}
        />
      )}

      {resetTarget && (
        <ResetPasswordModal
          target={resetTarget}
          onClose={() => setResetTarget(null)}
          onReset={(id, email, password) => {
            setResetTarget(null);
            setRevealed({ id, email, password, kind: "reset" });
          }}
        />
      )}

      {detailsTarget && (
        <EditDetailsModal
          user={detailsTarget}
          onClose={() => setDetailsTarget(null)}
          onDone={() => {
            setDetailsTarget(null);
            void loadUsers();
          }}
        />
      )}

      {assignTarget && (
        <AssignTeamModal
          erm={assignTarget}
          allUsers={users}
          onClose={() => setAssignTarget(null)}
        />
      )}

      {roleTarget && (
        <ChangeRoleModal
          user={roleTarget}
          onClose={() => setRoleTarget(null)}
          onDone={() => {
            setRoleTarget(null);
            void loadUsers();
          }}
        />
      )}

      {deleteTarget && (
        <DeleteUserModal
          user={deleteTarget}
          onClose={() => setDeleteTarget(null)}
          onDone={() => {
            setDeleteTarget(null);
            void loadUsers();
          }}
        />
      )}
    </div>
  );
}

// ── Row ─────────────────────────────────────────────────────────

function PersonRow({
  user,
  isSelf,
  busy,
  onEditDetails,
  onAssignTeam,
  onChangeRole,
  onResetPassword,
  onToggleStatus,
  onDelete,
}: {
  user: AgreementUserDto;
  isSelf: boolean;
  busy: boolean;
  onEditDetails: () => void;
  onAssignTeam: () => void;
  onChangeRole: () => void;
  onResetPassword: () => void;
  onToggleStatus: () => void;
  onDelete: () => void;
}) {
  const isSuperAdmin = user.role === "SUPER_ADMIN";

  // Same visibility rules the flat table enforced, in the same order.
  const actions: MenuAction[] = [
    // Name + title (the signature block) and, except for the super-admin, the
    // login email (Build AN). Available for every user.
    { key: "edit", label: "Edit details", Icon: Pencil, onSelect: onEditDetails },
    // Build K — assign Managers/Accounts to an ERM.
    ...(user.role === "ERM"
      ? [
          {
            key: "team",
            label: "Assign team",
            Icon: Users,
            onSelect: onAssignTeam,
          },
        ]
      : []),
    // Build K2 — change role (not super-admin, not self).
    ...(!isSuperAdmin && !isSelf
      ? [
          {
            key: "role",
            label: "Change role",
            Icon: UserCog,
            onSelect: onChangeRole,
          },
        ]
      : []),
    // Reset is for ERMs only (the server blocks super-admins outright).
    ...(!isSuperAdmin
      ? [
          {
            key: "reset",
            label: "Reset password",
            Icon: KeyRound,
            onSelect: onResetPassword,
          },
        ]
      : []),
    // Hide the disable toggle on your own row — locking yourself out of the
    // only super-admin account is unrecoverable from the UI.
    ...(!isSelf
      ? [
          {
            key: "status",
            label: user.active ? "Disable" : "Enable",
            Icon: user.active ? Ban : CheckCircle2,
            onSelect: onToggleStatus,
          },
        ]
      : []),
    // Build K2 — delete (not super-admin, not self).
    ...(!isSuperAdmin && !isSelf
      ? [
          {
            key: "delete",
            label: "Delete",
            Icon: Trash2,
            onSelect: onDelete,
            danger: true,
          },
        ]
      : []),
  ];

  return (
    <tr className="hover:bg-gray-50">
      <td className={TD}>
        <div className="min-w-0">
          <p className="font-medium text-gray-900 truncate">
            {user.fullName}
            {isSelf && (
              <span className="ml-1.5 text-[10px] font-semibold uppercase tracking-wider text-gray-400">
                you
              </span>
            )}
          </p>
          <p className="text-[12px] text-gray-500 truncate">{user.email}</p>
        </div>
      </td>
      <td className={TD + " text-[13px] text-gray-600"}>{user.title || "—"}</td>
      <td className={TD}>
        <Chip tone={user.active ? "done" : "danger"}>
          {user.active ? "Active" : "Disabled"}
        </Chip>
      </td>
      <td className={TD + " text-[12px] text-gray-500 whitespace-nowrap"}>
        {/* Build N — US MM-DD-YYYY HH:mm. Never signed in reads as a dash. */}
        {user.lastLoginAt ? formatUsDateTime(user.lastLoginAt) : "—"}
      </td>
      <td className={TD + " text-right"}>
        <RowMenu actions={actions} busy={busy} label={user.fullName} />
      </td>
    </tr>
  );
}

// ── Overflow menu ───────────────────────────────────────────────

interface MenuAction {
  key: string;
  label: string;
  Icon: LucideIcon;
  onSelect: () => void;
  danger?: boolean;
}

const MENU_WIDTH = 200; // px — kept in sync with the w-[200px] below.
const MENU_ITEM_HEIGHT = 32;

function RowMenu({
  actions,
  busy,
  label,
}: {
  actions: MenuAction[];
  busy: boolean;
  label: string;
}) {
  const [open, setOpen] = useState(false);
  const [coords, setCoords] = useState<{ top: number; left: number }>({
    top: 0,
    left: 0,
  });
  const rootRef = useRef<HTMLDivElement | null>(null);
  const btnRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const onMouseDown = (e: MouseEvent) => {
      // The panel lives inside rootRef in the DOM (fixed positioning does not
      // move it), so one containment test covers the trigger and the items.
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    // The panel is anchored to viewport coordinates captured at open time, so
    // it has to close rather than drift when anything moves underneath it.
    const onReflow = () => setOpen(false);
    document.addEventListener("mousedown", onMouseDown);
    document.addEventListener("keydown", onKeyDown);
    window.addEventListener("resize", onReflow);
    window.addEventListener("scroll", onReflow, true);
    return () => {
      document.removeEventListener("mousedown", onMouseDown);
      document.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("resize", onReflow);
      window.removeEventListener("scroll", onReflow, true);
    };
  }, [open]);

  const toggle = () => {
    if (open) {
      setOpen(false);
      return;
    }
    const rect = btnRef.current?.getBoundingClientRect();
    if (rect) {
      // Viewport-anchored, not absolute-in-the-card: both SectionCard and
      // TableShell clip their overflow, so a panel positioned inside the card
      // would be cut off on the last row of every section.
      const height = actions.length * MENU_ITEM_HEIGHT + 10;
      setCoords({
        top: Math.max(
          8,
          Math.min(rect.bottom + 4, window.innerHeight - height - 8),
        ),
        left: Math.max(
          8,
          Math.min(rect.right - MENU_WIDTH, window.innerWidth - MENU_WIDTH - 8),
        ),
      });
    }
    setOpen(true);
  };

  return (
    <div ref={rootRef} className="inline-block">
      <button
        ref={btnRef}
        type="button"
        onClick={toggle}
        disabled={busy}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Actions for ${label}`}
        className={
          "inline-flex items-center justify-center h-7 w-7 rounded-md border border-gray-200 bg-white text-gray-500 " +
          "hover:bg-gray-50 hover:text-sage-navy cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed " +
          (open ? "bg-gray-50 text-sage-navy" : "")
        }
      >
        {busy ? (
          <Loader2 size={13} className="animate-spin" />
        ) : (
          <MoreHorizontal size={14} />
        )}
      </button>

      {open && (
        <div
          role="menu"
          style={{ top: coords.top, left: coords.left, width: MENU_WIDTH }}
          className="fixed z-50 rounded-xl border border-gray-100 bg-white shadow-lg py-1"
        >
          {actions.map((a) => (
            <button
              key={a.key}
              type="button"
              role="menuitem"
              onClick={() => {
                setOpen(false);
                a.onSelect();
              }}
              className={
                "w-full flex items-center gap-2 px-3 py-1.5 text-left text-[12px] font-semibold cursor-pointer " +
                (a.danger
                  ? "text-red-600 hover:bg-red-50"
                  : "text-gray-700 hover:bg-gray-50")
              }
            >
              <a.Icon size={12} className="shrink-0" />
              {a.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
