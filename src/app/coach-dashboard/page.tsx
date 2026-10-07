"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  AlertCircle,
  ClipboardList,
  Loader2,
  MessageSquare,
  PenLine,
  Settings,
  Users,
} from "lucide-react";

import {
  RoleDashboardShell,
  useUrlTab,
  type RoleDashboardTab,
} from "@/components/dashboard/RoleDashboardShell";
import { CoachParticipantsTab } from "@/components/dashboard/coach/CoachParticipantsTab";
import { CoachSessionsTab } from "@/components/dashboard/coach/CoachSessionsTab";
import { CoachTasksTab } from "@/components/dashboard/coach/CoachTasksTab";
import { CoachFeedbackTab } from "@/components/dashboard/coach/CoachFeedbackTab";
import { useAuth } from "@/lib/auth-context";
import {
  getCoachParticipants,
  loginHere,
  type CoachParticipantRow,
} from "@/lib/api";

// Coach / Technical Advisor dashboard. Five tabs:
//   home      -- assigned participants table
//   sessions  -- log session notes
//   tasks     -- assign / track practice tasks
//   feedback  -- submit qualitative feedback
//   profile   -- the coach's name, email and role, and Change password
//
// Backend service refuses cross-participant lookups, so a coach can't
// pull data for someone not on their assignment list.

type TabId = "home" | "sessions" | "tasks" | "feedback" | "profile";

const TABS: ReadonlyArray<RoleDashboardTab> = [
  { id: "home", label: "My Participants", Icon: Users },
  { id: "sessions", label: "Session Notes", Icon: PenLine },
  { id: "tasks", label: "Tasks", Icon: ClipboardList },
  { id: "feedback", label: "Feedback", Icon: MessageSquare },
  { id: "profile", label: "Profile", Icon: Settings },
];
const TAB_IDS = TABS.map((t) => t.id);

export default function CoachDashboardPage() {
  const router = useRouter();
  const { user, isLoading } = useAuth();
  // The open tab is in the URL (?tab=<id>): a refresh or Back keeps it.
  const [active, setActive] = useUrlTab<TabId>(TAB_IDS, "home");
  const [participants, setParticipants] = useState<CoachParticipantRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.replace(loginHere());
      return;
    }
    const role = (user.role ?? "").toUpperCase();
    if (role !== "COACH" && role !== "TECHNICAL_ADVISOR") {
      import("@/lib/api").then(({ dashboardRouteForRole }) => {
        router.replace(dashboardRouteForRole(role));
      });
      return;
    }
    let cancelled = false;
    getCoachParticipants()
      .then((p) => {
        if (!cancelled) setParticipants(p);
      })
      .catch((e) => {
        if (!cancelled)
          setError(
            e instanceof Error ? e.message : "Couldn't load participants",
          );
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isLoading, user, router]);

  // After a session, task or feedback is saved: the counts on My
  // participants (e.g. Sessions) come from this list.
  const reloadParticipants = useCallback(() => {
    getCoachParticipants()
      .then(setParticipants)
      .catch(() => {});
  }, []);

  if (isLoading || loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <RoleDashboardShell
      title="Coach Panel"
      tabs={TABS}
      active={active}
      onSelect={(id) => setActive(id as TabId)}
    >
      {error && (
        <p className="mb-4 inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      {active === "home" && (
        <CoachParticipantsTab participants={participants} />
      )}
      {active === "sessions" && (
        <CoachSessionsTab participants={participants} onSaved={reloadParticipants} />
      )}
      {active === "tasks" && (
        <CoachTasksTab participants={participants} onSaved={reloadParticipants} />
      )}
      {active === "feedback" && (
        <CoachFeedbackTab participants={participants} onSaved={reloadParticipants} />
      )}
      {active === "profile" && (
        <div className="space-y-3">
          <h1 className="text-2xl font-bold text-gray-900">Profile</h1>
          <div className="rounded-2xl border border-dashed border-gray-200 bg-gray-50/60 p-6">
            <dl className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-sm">
              <div className="min-w-0">
                <dt className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">Name</dt>
                <dd className="text-gray-900 break-words">{user?.fullName || "—"}</dd>
              </div>
              <div className="min-w-0">
                <dt className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">Email</dt>
                <dd className="text-gray-900 break-all">{user?.email || "—"}</dd>
              </div>
              <div className="min-w-0">
                <dt className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">Role</dt>
                <dd className="text-gray-900">
                  {(user?.role ?? "").toUpperCase() === "TECHNICAL_ADVISOR" ? "Technical advisor" : "Coach"}
                </dd>
              </div>
            </dl>
            <p className="mt-4 text-sm text-gray-700">
              Your coach type and technologies are set by Operations.
            </p>
            <Link
              href="/change-password"
              className="mt-3 inline-block text-sm font-semibold text-sage-navy hover:underline"
            >
              Change password →
            </Link>
          </div>
        </div>
      )}
    </RoleDashboardShell>
  );
}
