"use client";

import { useEffect, useState } from "react";
import { Loader2, UsersRound } from "lucide-react";

import {
  getUsers,
  webAdminFetchErmTeam,
  webAdminSaveErmTeam,
  type UserDTO,
} from "@/lib/api";
import { InlineAlert, Spinner } from "@/components/web-agreement/ui/primitives";

/**
 * The user page's "Approver team" card on an ERM account: which Managers and
 * Accounts approvers this ERM can route an agreement to. These are the only
 * names in the ERM's "Send for approval" pickers; an ERM with no Manager gets
 * "No manager assigned — ask an admin to assign one." A copy of the console's
 * Assign team (UserModals.tsx:540-682), as a card instead of a dialog. System
 * Admin only; the server checks again.
 *
 * Only active MANAGER and ACCOUNTS users are offered: routing an agreement to
 * an account that can't sign in would strand it. Saving replaces both lists.
 */
export function ApproverTeamCard({ ermUserId }: { ermUserId: number }) {
  const [managers, setManagers] = useState<UserDTO[]>([]);
  const [accounts, setAccounts] = useState<UserDTO[]>([]);
  const [managerIds, setManagerIds] = useState<Set<number>>(new Set());
  const [accountsIds, setAccountsIds] = useState<Set<number>>(new Set());
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState("");

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError("");
    Promise.all([webAdminFetchErmTeam(ermUserId), getUsers("active")])
      .then(([team, users]) => {
        if (cancelled) return;
        const active = ((users ?? []) as UserDTO[]).filter((u) => u.isActive !== false);
        setManagers(active.filter((u) => (u.role ?? "").toUpperCase() === "MANAGER"));
        setAccounts(active.filter((u) => (u.role ?? "").toUpperCase() === "ACCOUNTS"));
        setManagerIds(new Set(team.managerIds));
        setAccountsIds(new Set(team.accountsIds));
      })
      .catch((e) => {
        if (!cancelled) setLoadError(e instanceof Error ? e.message : "Couldn't load assignments.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [ermUserId]);

  const toggle = (set: Set<number>, setter: (s: Set<number>) => void, id: number) => {
    const next = new Set(set);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setter(next);
    setDone("");
  };

  const handleSave = async () => {
    setSaving(true);
    setError("");
    setDone("");
    try {
      const saved = await webAdminSaveErmTeam(ermUserId, {
        managerIds: Array.from(managerIds),
        accountsIds: Array.from(accountsIds),
      });
      setManagerIds(new Set(saved.managerIds));
      setAccountsIds(new Set(saved.accountsIds));
      setDone("Assignments saved");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't save assignments.");
    } finally {
      setSaving(false);
    }
  };

  const renderList = (
    heading: string,
    options: UserDTO[],
    selected: Set<number>,
    setter: (s: Set<number>) => void,
  ) => (
    <div className="min-w-0">
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
                className="mt-0.5 h-4 w-4 shrink-0 accent-sage-navy cursor-pointer"
              />
              <span className="text-[13px] leading-tight min-w-0 break-words">
                <span className="font-medium text-gray-900">{u.fullName}</span>
                <span className="block text-[11px] text-gray-500 break-all">{u.email}</span>
              </span>
            </label>
          ))}
        </div>
      )}
    </div>
  );

  return (
    <div className="bg-white border border-zinc-200 rounded-2xl p-6 mb-5">
      <h2 className="text-sm font-bold text-zinc-900 flex items-center gap-2 mb-1">
        <UsersRound size={16} className="text-sage-navy" />
        Approver team
      </h2>
      <p className="text-xs text-zinc-500 mb-4">
        Choose which Managers and Accounts this ERM can route agreements to.
        These drive the approval pickers when the ERM sends for approval.
      </p>

      {loading ? (
        <Spinner />
      ) : loadError ? (
        <InlineAlert tone="error">{loadError}</InlineAlert>
      ) : (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
            {renderList("Managers", managers, managerIds, setManagerIds)}
            {renderList("Accounts", accounts, accountsIds, setAccountsIds)}
          </div>

          {(error || done) && (
            <div className="mt-3">
              {error ? (
                <InlineAlert tone="error" onDismiss={() => setError("")}>{error}</InlineAlert>
              ) : (
                <InlineAlert tone="success" onDismiss={() => setDone("")}>{done}</InlineAlert>
              )}
            </div>
          )}

          <div className="mt-4 flex justify-end">
            <button
              type="button"
              onClick={() => void handleSave()}
              disabled={saving}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer disabled:cursor-not-allowed"
            >
              {saving && <Loader2 size={14} className="animate-spin" />} Save assignments
            </button>
          </div>
        </>
      )}
    </div>
  );
}

export default ApproverTeamCard;
