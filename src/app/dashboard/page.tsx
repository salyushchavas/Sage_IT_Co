"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Loader2 } from "lucide-react";

import DashboardLayout from "@/components/layout/DashboardLayout";
import AgreementTab from "@/components/dashboard/AgreementTab";
import ComingSoonTab from "@/components/dashboard/ComingSoonTab";
import DocumentsTab from "@/components/dashboard/DocumentsTab";
import HomeTab from "@/components/dashboard/HomeTab";
import LockedTabView from "@/components/dashboard/LockedTabView";
import MessagesTab from "@/components/dashboard/MessagesTab";
import ProfileCompletionBanner from "@/components/dashboard/ProfileCompletionBanner";
import ProfileCompletionChecklist from "@/components/dashboard/ProfileCompletionChecklist";
import ProfileTab from "@/components/dashboard/ProfileTab";
import TeamTab from "@/components/dashboard/TeamTab";
import { useAuth } from "@/lib/auth-context";
import {
  getOnboardingRoute,
  getParticipantDashboard,
  getParticipantTeam,
  getProfileCompletion,
  isDashboardStatus,
  type ParticipantDashboard as DashboardData,
  type ParticipantTeam,
  type ProfileCompletion,
} from "@/lib/api";

// Profile-gated tab metadata. The nine tabs listed here render
// LockedTabView while pct < 100, and their real content (or a
// ComingSoonTab placeholder) once unlocked. Home, Complete Profile,
// Messages, and Profile stay accessible at every completion level.
interface GatedCopy {
  title: string;
  subtitle: string;
  body: string;
}
const GATED_TABS: Record<string, GatedCopy> = {
  courses: {
    title: "My Courses",
    subtitle: "Browse and enroll in your learning tracks.",
    body: "Course access opens once your profile is complete -- enroll in tracks, watch lessons, take quizzes, and earn certificates.",
  },
  weekly: {
    title: "Weekly Report",
    subtitle: "Submit your weekly job-search progress.",
    body: "Weekly reporting kicks in after onboarding so your coaching team can track your job-search activity.",
  },
  resume: {
    title: "Resume",
    subtitle: "Build and edit your resume with coach feedback.",
    body: "Resume reviews are part of the active phase that begins after your profile is complete.",
  },
  interview: {
    title: "Interviews",
    subtitle: "Mock interviews and coaching slots.",
    body: "Interview prep sessions become available once you've finished onboarding.",
  },
  employment: {
    title: "Employment",
    subtitle: "Track offers and post-placement support.",
    body: "Employment tracking opens after the rest of the profile is in place.",
  },
  payments: {
    title: "Payments",
    subtitle: "Manage your payment plan and invoices.",
    body: "Your payment plan, invoices, and ledger appear here once enrollment is finalised.",
  },
  documents: {
    title: "Documents",
    subtitle: "View and download your participant documents.",
    body: "Document access opens once the onboarding upload step is complete and reviewed.",
  },
  agreement: {
    title: "Agreement",
    subtitle: "Read and download your signed participant agreement.",
    body: "Your signed agreement and related records appear here after the agreement step is complete.",
  },
  team: {
    title: "My Team",
    subtitle: "See your assigned ERM and coaches.",
    body: "Your dedicated team is assigned during onboarding and shown here once your profile is complete.",
  },
};

function DashboardPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  // "home" matches Spire's default tab id.
  const activeTab = searchParams.get("tab") || "home";

  const { user, isLoading, refreshUser } = useAuth();
  const [routingDecided, setRoutingDecided] = useState(false);

  // Snapshots fetched once at the dashboard level so the sidebar
  // % badge + home tab + messages tab + team tab all share data.
  const [completion, setCompletion] = useState<ProfileCompletion | null>(null);
  const [dashboardData, setDashboardData] = useState<DashboardData | null>(null);
  const [team, setTeam] = useState<ParticipantTeam | null>(null);

  useEffect(() => {
    if (isLoading) return;
    let cancelled = false;

    (async () => {
      if (!user) {
        router.replace("/login?redirect=/dashboard");
        return;
      }

      const role = (user.role ?? "").toUpperCase();
      if (role === "ADMIN" || role === "INSTRUCTOR") {
        router.replace("/admin");
        return;
      }

      // Participant lifecycle. Refresh in-memory user if any critical
      // fields are missing -- auth context can hold stale data after
      // soft navigations.
      let status = user.currentStatus;
      let participantId = user.participantId;
      if (!status || !participantId) {
        try {
          await refreshUser();
        } catch {
          /* ignore -- fall through to not-enrolled branch */
        }
        if (cancelled) return;
      }

      if (!participantId && !user.participantId) {
        router.replace("/enroll");
        return;
      }

      if (status && !isDashboardStatus(status)) {
        router.replace(getOnboardingRoute(status));
        return;
      }

      if (!cancelled) setRoutingDecided(true);
    })();

    return () => {
      cancelled = true;
    };
  }, [isLoading, user, router, refreshUser]);

  // Profile-completion snapshot. Drives sidebar % badge + gate decision.
  useEffect(() => {
    if (!routingDecided) return;
    let cancelled = false;
    getProfileCompletion()
      .then((res) => {
        if (!cancelled) setCompletion(res);
      })
      .catch(() => {
        /* badge / gate just stay hidden / locked */
      });
    return () => {
      cancelled = true;
    };
  }, [routingDecided, user?.profileCompletionPct]);

  // Dashboard + team snapshots for tab content. Run in parallel so
  // both land roughly together.
  useEffect(() => {
    if (!routingDecided) return;
    let cancelled = false;
    Promise.all([getParticipantDashboard(), getParticipantTeam()])
      .then(([d, t]) => {
        if (cancelled) return;
        setDashboardData(d);
        setTeam(t);
      })
      .catch(() => {
        /* tabs that need this data degrade gracefully */
      });
    return () => {
      cancelled = true;
    };
  }, [routingDecided]);

  if (isLoading || !routingDecided) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  // Sidebar badge: amber percentage chip while profile is incomplete.
  const badges =
    completion && completion.completionPercentage < 100
      ? {
          "complete-profile": (
            <span className="inline-flex items-center px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-800 text-[10px] font-bold">
              {completion.completionPercentage}%
            </span>
          ),
        }
      : undefined;

  const handleJumpTo = (tab: string) => {
    router.push(`/dashboard?tab=${tab}`);
  };

  return (
    <DashboardLayout activeTab={activeTab} badges={badges}>
      <ProfileCompletionBanner />
      <div className="px-6 md:px-10 py-8 md:py-10 max-w-5xl">
        {renderTab(activeTab, completion, dashboardData, team, user?.email ?? null, handleJumpTo)}
      </div>
    </DashboardLayout>
  );
}

function renderTab(
  tab: string,
  completion: ProfileCompletion | null,
  dashboardData: DashboardData | null,
  team: ParticipantTeam | null,
  userEmail: string | null,
  jumpTo: (id: string) => void,
) {
  const isComplete = completion ? completion.completionPercentage >= 100 : false;

  // Profile-gated tabs: lock until pct=100, then render real content
  // (or a ComingSoonTab placeholder for the tabs we haven't ported).
  const gated = GATED_TABS[tab];
  if (gated) {
    if (!isComplete) {
      return (
        <LockedTabView
          title={gated.title}
          subtitle={gated.subtitle}
          headline={`${gated.title} unlocks once your profile is complete`}
          body={gated.body}
        />
      );
    }
    return renderGatedReal(tab, dashboardData, team);
  }

  // Ungated tabs.
  switch (tab) {
    case "home":
      if (!dashboardData) return <TabLoading />;
      return (
        <HomeTab
          data={dashboardData}
          team={team}
          userEmail={userEmail}
          onJumpTo={jumpTo}
        />
      );
    case "complete-profile":
      return <ProfileCompletionChecklist />;
    case "messages":
      if (!dashboardData) return <TabLoading />;
      return <MessagesTab data={dashboardData} team={team} />;
    case "profile":
      return <ProfileTab />;
    default:
      return (
        <ComingSoonTab
          title="Not found"
          copy={`No tab matches "${tab}". Use a sidebar link.`}
        />
      );
  }
}

function renderGatedReal(
  tab: string,
  dashboardData: DashboardData | null,
  team: ParticipantTeam | null,
) {
  switch (tab) {
    case "documents":
      return <DocumentsTab />;
    case "agreement":
      return <AgreementTab participantId={dashboardData?.participantId ?? null} />;
    case "team":
      if (!dashboardData) return <TabLoading />;
      return <TeamTab team={team} data={dashboardData} />;

    // Tabs with real backend support but UI port deferred -- the
    // ComingSoonTab card lands the user softly until each tab gets a
    // dedicated build-out.
    case "courses":
      return (
        <ComingSoonTab
          title="My Courses"
          copy="Course enrollment and progress tracking ships in a follow-up phase."
          hint="In the meantime, your assigned coach can recommend resources via the My Team tab."
        />
      );
    case "weekly":
      return (
        <ComingSoonTab
          title="Weekly Report"
          copy="The weekly job-search reporting flow lands in a follow-up phase."
          hint="Your ERM will reach out by email when weekly reporting opens."
        />
      );
    case "resume":
      return (
        <ComingSoonTab
          title="Resume / Profile activity"
          copy="Resume version tracking, profile updates, and coach feedback ship in a follow-up phase."
          hint="Send draft resumes to your Resume Specialist via the My Team tab."
        />
      );
    case "interview":
      return (
        <ComingSoonTab
          title="Interview training"
          copy="Mock interview scheduling, coach feedback, and practice tasks ship in a follow-up phase."
          hint="Talk to your Interview Coach via My Team to schedule a mock."
        />
      );
    case "employment":
      return (
        <ComingSoonTab
          title="Employment"
          copy="Offer tracking and post-placement support land in a follow-up phase."
          hint="When you receive an offer, email your ERM directly so they can record it."
        />
      );
    case "payments":
      return (
        <ComingSoonTab
          title="Payments"
          copy="Payment plan acceptance, invoices, and ledger land in a follow-up phase."
          hint="Finance will reach out with payment details before any plan activates."
        />
      );
    default:
      return null;
  }
}

function TabLoading() {
  return (
    <div className="text-center py-10">
      <Loader2 size={20} className="animate-spin text-sage-navy inline" />
    </div>
  );
}

export default function DashboardPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen flex items-center justify-center bg-gray-50">
          <Loader2 size={28} className="animate-spin text-sage-navy" />
        </div>
      }
    >
      <DashboardPageInner />
    </Suspense>
  );
}
