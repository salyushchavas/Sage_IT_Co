"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  Loader2,
  Power,
  RotateCcw,
  ShieldCheck,
  Trash2,
} from "lucide-react";

import { useAuth } from "@/lib/auth-context";
import {
  getUserProfileAsAdmin,
  reactivateUserAsAdmin,
  softDeleteUserAsAdmin,
  updateUserRoleAsAdmin,
  updateUserStatusAsAdmin,
} from "@/lib/api";

interface UserProfile {
  id: number;
  fullName: string | null;
  email: string;
  role: string;
  bio?: string | null;
  isActive?: boolean;
  createdAt?: string | null;
  currentStatus?: string | null;
  participantId?: string | null;
  profileCompletionPct?: number | null;
}

const ROLE_OPTIONS = [
  "STUDENT",
  "INSTRUCTOR",
  "ERM",
  "COACH",
  "TECHNICAL_ADVISOR",
  "FINANCE",
  "OPERATIONS_ADMIN",
  "ADMIN",
  "SYSTEM_ADMIN",
];

export default function AdminUserDetailPage() {
  const router = useRouter();
  const params = useParams<{ userId: string }>();
  const userId = params?.userId;
  const { user: me, isLoading: authLoading, isAuthenticated } = useAuth();

  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<{
    kind: "success" | "error";
    message: string;
  } | null>(null);

  const role = me?.role?.toUpperCase();

  const loadProfile = useCallback(async () => {
    if (!userId) return;
    setLoading(true);
    setError("");
    try {
      const data = await getUserProfileAsAdmin(userId);
      // Backend returns the wrapper.data shape; pull out the canonical
      // user fields we care about for this view.
      const p = (data ?? {}) as Record<string, unknown>;
      setProfile({
        id: Number(p.id),
        fullName: (p.fullName as string) ?? null,
        email: (p.email as string) ?? "",
        role: (p.role as string) ?? "STUDENT",
        bio: (p.bio as string) ?? null,
        isActive: (p.isActive as boolean | undefined) ?? true,
        createdAt: (p.createdAt as string) ?? null,
        currentStatus: (p.currentStatus as string) ?? null,
        participantId: (p.participantId as string) ?? null,
        profileCompletionPct:
          (p.profileCompletionPct as number | undefined) ?? null,
      });
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Couldn't load user profile",
      );
    } finally {
      setLoading(false);
    }
  }, [userId]);

  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) {
      router.replace(`/login?redirect=/admin/users/${userId ?? ""}`);
      return;
    }
    if (role !== "ADMIN" && role !== "SYSTEM_ADMIN") {
      router.replace("/dashboard");
      return;
    }
    void loadProfile();
  }, [authLoading, isAuthenticated, role, router, userId, loadProfile]);

  const showToast = (kind: "success" | "error", message: string) => {
    setToast({ kind, message });
    setTimeout(() => setToast(null), 3000);
  };

  const handleRoleChange = async (newRole: string) => {
    if (!profile || newRole === profile.role) return;
    setBusy(true);
    try {
      await updateUserRoleAsAdmin(profile.id, newRole);
      setProfile({ ...profile, role: newRole });
      showToast("success", `Role updated to ${newRole}`);
    } catch (err) {
      showToast(
        "error",
        err instanceof Error ? err.message : "Couldn't update role",
      );
    } finally {
      setBusy(false);
    }
  };

  const handleDeactivate = async () => {
    if (!profile) return;
    if (
      !window.confirm(
        `Deactivate ${profile.fullName ?? profile.email}? They won't be able to sign in until reactivated.`,
      )
    ) {
      return;
    }
    setBusy(true);
    try {
      await updateUserStatusAsAdmin(profile.id, false);
      setProfile({ ...profile, isActive: false });
      showToast("success", "User deactivated");
    } catch (err) {
      showToast(
        "error",
        err instanceof Error ? err.message : "Couldn't deactivate",
      );
    } finally {
      setBusy(false);
    }
  };

  const handleReactivate = async () => {
    if (!profile) return;
    setBusy(true);
    try {
      await reactivateUserAsAdmin(profile.id);
      setProfile({ ...profile, isActive: true });
      showToast("success", "User reactivated");
    } catch (err) {
      showToast(
        "error",
        err instanceof Error ? err.message : "Couldn't reactivate",
      );
    } finally {
      setBusy(false);
    }
  };

  const handleSoftDelete = async () => {
    if (!profile) return;
    if (
      !window.confirm(
        `Soft-delete ${profile.fullName ?? profile.email}? This deactivates the account and scrubs personal data. Audit + enrollment history stays intact. This action cannot be undone from the UI.`,
      )
    ) {
      return;
    }
    setBusy(true);
    try {
      await softDeleteUserAsAdmin(profile.id);
      setProfile({ ...profile, isActive: false });
      showToast("success", "User soft-deleted and anonymized");
    } catch (err) {
      showToast(
        "error",
        err instanceof Error ? err.message : "Couldn't soft-delete",
      );
    } finally {
      setBusy(false);
    }
  };

  if (authLoading || loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  if (error || !profile) {
    return (
      <section className="min-h-screen pt-28 pb-20 px-6">
        <div className="mx-auto max-w-3xl">
          <Link
            href="/admin"
            className="inline-flex items-center gap-1 text-sm text-sage-copper hover:underline mb-5"
          >
            <ArrowLeft size={14} /> Back to admin
          </Link>
          <div className="p-4 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
            {error || "User not found"}
          </div>
        </div>
      </section>
    );
  }

  const isMe = me?.id === profile.id;

  return (
    <section className="min-h-screen pt-28 pb-20 px-6 bg-gray-50">
      <div className="mx-auto max-w-4xl">
        <Link
          href="/admin"
          className="inline-flex items-center gap-1 text-sm text-sage-copper hover:underline mb-5"
        >
          <ArrowLeft size={14} /> Back to admin
        </Link>

        {toast && (
          <div
            className={
              "mb-4 px-4 py-2.5 rounded-lg text-sm font-semibold inline-flex items-center gap-2 " +
              (toast.kind === "success"
                ? "bg-emerald-50 border border-emerald-200 text-emerald-800"
                : "bg-red-50 border border-red-200 text-red-700")
            }
          >
            {toast.kind === "success" ? (
              <CheckCircle2 size={16} />
            ) : (
              <AlertTriangle size={16} />
            )}
            {toast.message}
          </div>
        )}

        <div className="bg-white border border-zinc-200 rounded-2xl p-6 mb-5">
          <div className="flex items-start gap-4 flex-wrap">
            <div className="w-16 h-16 rounded-full bg-gradient-to-br from-sage-navy to-sage-copper flex items-center justify-center text-white font-bold text-xl shrink-0">
              {(profile.fullName ?? profile.email).charAt(0).toUpperCase()}
            </div>
            <div className="flex-1 min-w-0">
              <h1 className="text-xl font-bold text-zinc-900 truncate">
                {profile.fullName ?? "(no name)"}
              </h1>
              <p className="text-sm text-zinc-600 truncate">{profile.email}</p>
              <div className="flex flex-wrap items-center gap-2 mt-2 text-xs">
                <span className="px-2 py-0.5 rounded-full font-semibold bg-sage-navy/10 text-sage-navy">
                  {profile.role}
                </span>
                <span
                  className={
                    "px-2 py-0.5 rounded-full font-semibold " +
                    (profile.isActive
                      ? "bg-emerald-50 text-emerald-700"
                      : "bg-red-50 text-red-700")
                  }
                >
                  {profile.isActive ? "Active" : "Deactivated"}
                </span>
                {profile.participantId && (
                  <span className="px-2 py-0.5 rounded-full font-semibold bg-zinc-100 text-zinc-700 font-mono">
                    {profile.participantId}
                  </span>
                )}
                <span className="text-zinc-400">User #{profile.id}</span>
              </div>
            </div>
          </div>

          {profile.bio && (
            <p className="mt-4 text-sm text-zinc-600">{profile.bio}</p>
          )}

          <div className="mt-5 grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm">
            {profile.createdAt && (
              <div>
                <p className="text-[11px] uppercase tracking-wider font-semibold text-zinc-500">
                  Joined
                </p>
                <p className="text-zinc-900">
                  {new Date(profile.createdAt).toLocaleDateString("en-IN", {
                    year: "numeric",
                    month: "short",
                    day: "numeric",
                  })}
                </p>
              </div>
            )}
            {profile.currentStatus && (
              <div>
                <p className="text-[11px] uppercase tracking-wider font-semibold text-zinc-500">
                  Workflow status
                </p>
                <p className="text-zinc-900 font-mono text-xs">
                  {profile.currentStatus}
                </p>
              </div>
            )}
            {profile.profileCompletionPct != null && (
              <div className="sm:col-span-2">
                <p className="text-[11px] uppercase tracking-wider font-semibold text-zinc-500 mb-1">
                  Profile completion
                </p>
                <div className="flex items-center gap-3">
                  <div className="flex-1 h-2 rounded-full bg-zinc-100 overflow-hidden">
                    <div
                      className="h-full bg-sage-navy"
                      style={{ width: `${profile.profileCompletionPct}%` }}
                    />
                  </div>
                  <span className="text-xs font-bold text-sage-navy">
                    {profile.profileCompletionPct}%
                  </span>
                </div>
              </div>
            )}
          </div>
        </div>

        <div className="bg-white border border-zinc-200 rounded-2xl p-6 mb-5">
          <h2 className="text-sm font-bold text-zinc-900 flex items-center gap-2 mb-4">
            <ShieldCheck size={16} className="text-sage-navy" />
            Role
          </h2>
          <div className="flex items-center gap-3">
            <select
              value={profile.role}
              onChange={(e) => handleRoleChange(e.target.value)}
              disabled={busy || isMe}
              className="px-3 py-2 text-sm rounded-md border border-zinc-200 bg-white focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy disabled:opacity-60 cursor-pointer"
            >
              {ROLE_OPTIONS.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
            <p className="text-xs text-zinc-500">
              {isMe
                ? "You can't change your own role."
                : "Saves immediately on selection."}
            </p>
          </div>
        </div>

        <div className="bg-white border border-zinc-200 rounded-2xl p-6">
          <h2 className="text-sm font-bold text-zinc-900 mb-1">Danger zone</h2>
          <p className="text-xs text-zinc-500 mb-4">
            Account status changes are auditable but reversible (except
            soft-delete which scrubs personal data).
          </p>
          <div className="flex flex-wrap gap-3">
            {profile.isActive ? (
              <button
                onClick={handleDeactivate}
                disabled={busy || isMe}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-amber-100 text-amber-800 hover:bg-amber-200 disabled:opacity-60 cursor-pointer"
              >
                <Power size={14} /> Deactivate
              </button>
            ) : (
              <button
                onClick={handleReactivate}
                disabled={busy || isMe}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-emerald-100 text-emerald-800 hover:bg-emerald-200 disabled:opacity-60 cursor-pointer"
              >
                <RotateCcw size={14} /> Reactivate
              </button>
            )}
            <button
              onClick={handleSoftDelete}
              disabled={busy || isMe}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-red-700 text-white hover:bg-red-800 disabled:opacity-60 cursor-pointer"
            >
              <Trash2 size={14} /> Soft-delete + anonymize
            </button>
          </div>
          {isMe && (
            <p className="mt-3 text-xs text-zinc-500 italic">
              Actions are disabled on your own account.
            </p>
          )}
        </div>
      </div>
    </section>
  );
}
