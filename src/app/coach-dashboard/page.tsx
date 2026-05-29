"use client";

import { useEffect, useState } from "react";
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
  type RoleDashboardTab,
} from "@/components/dashboard/RoleDashboardShell";
import { CoachParticipantsTab } from "@/components/dashboard/coach/CoachParticipantsTab";
import { CoachSessionsTab } from "@/components/dashboard/coach/CoachSessionsTab";
import { CoachTasksTab } from "@/components/dashboard/coach/CoachTasksTab";
import { CoachFeedbackTab } from "@/components/dashboard/coach/CoachFeedbackTab";
import { useAuth } from "@/lib/auth-context";
import {
  getCoachParticipants,
  type CoachParticipantRow,
} from "@/lib/api";

// Coach / Technical Advisor dashboard. Five tabs:
//   home      -- assigned participants table
//   sessions  -- log session notes
//   tasks     -- assign / track practice tasks
//   feedback  -- submit qualitative feedback
//   profile   -- pointer to the standard profile page
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

export default function CoachDashboardPage() {
  const router = useRouter();
  const { user, isLoading } = useAuth();
  const [active, setActive] = useState<TabId>("home");
  const [participants, setParticipants] = useState<CoachParticipantRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.replace("/login?redirect=/coach-dashboard");
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
        <CoachSessionsTab participants={participants} />
      )}
      {active === "tasks" && <CoachTasksTab participants={participants} />}
      {active === "feedback" && (
        <CoachFeedbackTab participants={participants} />
      )}
      {active === "profile" && (
        <div className="space-y-3">
          <h1 className="text-2xl font-bold text-gray-900">Profile</h1>
          <div className="rounded-2xl border border-dashed border-gray-200 bg-gray-50/60 p-6">
            <p className="text-sm text-gray-700">
              Edit your coach profile from the standard profile page.
            </p>
            <a
              href="/profile"
              className="mt-3 inline-block text-sm font-semibold text-sage-navy hover:underline"
            >
              Open profile →
            </a>
          </div>
        </div>
      )}
    </RoleDashboardShell>
  );
}
