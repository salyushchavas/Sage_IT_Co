"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Loader2 } from "lucide-react";

import DashboardLayout from "@/components/layout/DashboardLayout";
import LockedTabView from "@/components/dashboard/LockedTabView";
import ProfileCompletionBanner from "@/components/dashboard/ProfileCompletionBanner";
import ProfileCompletionChecklist from "@/components/dashboard/ProfileCompletionChecklist";
import { useAuth } from "@/lib/auth-context";
import {
  getOnboardingRoute,
  getProfileCompletion,
  isDashboardStatus,
  type ProfileCompletion,
} from "@/lib/api";

// Per Spire correction: nine dashboard tabs are gated behind a
// fully-complete participant profile. Each entry below carries the
// title (matches the sidebar label), a short subtitle, and the
// long-form body shown inside the lock card. Home, Complete Profile,
// Messages, and Profile tabs stay accessible at all completion %.
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

/**
 * Participant dashboard surface.
 *
 * Routing rules (in order):
 *   1. Not signed in -> /login
 *   2. ADMIN / INSTRUCTOR -> /admin
 *   3. No participantId -> /enroll
 *   4. currentStatus not a dashboard-tier value -> matching
 *      onboarding step
 *   5. Otherwise -> sidebar shell with tab content
 *
 * Phase 9B fills in the Complete Profile tab (checklist + banner +
 * sidebar % badge); the remaining tabs stay as placeholders for 9C/9D.
 */
function DashboardPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  // "home" matches Spire's default tab id.
  const activeTab = searchParams.get("tab") || "home";

  const { user, isLoading, refreshUser } = useAuth();
  const [routingDecided, setRoutingDecided] = useState(false);

  // Profile-completion snapshot fetched once at the dashboard level so
  // we can render the sidebar % badge alongside whatever tab the user
  // is on. The banner + checklist refetch their own copies so they
  // stay live after a step is completed.
  const [completion, setCompletion] = useState<ProfileCompletion | null>(null);

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

      // Participant lifecycle. Pull a fresh profile if the in-memory
      // copy is missing critical fields -- the auth context can hold
      // stale data after soft navigations.
      let status = user.currentStatus;
      let participantId = user.participantId;
      if (!status || !participantId) {
        try {
          await refreshUser();
        } catch {
          // ignore -- we'll fall through to the not-enrolled branch
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

  // Fetch profile completion once the gatekeeper is done. Refetches
  // when the user's stored profileCompletionPct changes (after a step
  // is submitted from elsewhere).
  useEffect(() => {
    if (!routingDecided) return;
    let cancelled = false;
    getProfileCompletion()
      .then((res) => { if (!cancelled) setCompletion(res); })
      .catch(() => { /* badge just stays hidden */ });
    return () => { cancelled = true; };
  }, [routingDecided, user?.profileCompletionPct]);

  if (isLoading || !routingDecided) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  // Sidebar badge: percentage chip while profile is incomplete.
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

  return (
    <DashboardLayout activeTab={activeTab} badges={badges}>
      {/* Sticky completion banner above every tab. Self-hides at 100%
          (with a one-time 5s celebration) or for 24h after dismiss. */}
      <ProfileCompletionBanner />
      {renderTab(activeTab, completion)}
    </DashboardLayout>
  );
}

function renderTab(tab: string, completion: ProfileCompletion | null) {
  // Profile-complete check. Conservative on missing data: if the
  // fetch hasn't landed yet, we treat profile as incomplete and show
  // the locked view (which fetches its own copy and degrades nicely).
  const isComplete = completion
    ? completion.completionPercentage >= 100
    : false;

  // Tab ids match Spire's NAV array exactly (home, complete-profile,
  // courses, weekly, resume, interview, employment, payments,
  // documents, agreement, team, messages, profile -- 13 tabs).
  // Nine of those are profile-gated; see GATED_TABS at the top.
  const gated = GATED_TABS[tab];
  if (gated) {
    if (!isComplete) {
      return (
        <div className="px-6 md:px-10 py-8 md:py-10 max-w-4xl">
          <LockedTabView
            title={gated.title}
            subtitle={gated.subtitle}
            headline={`${gated.title} unlocks once your profile is complete`}
            body={gated.body}
          />
        </div>
      );
    }
    // Profile complete -- real content lands in Phase 9D. Placeholder
    // keeps the page non-empty so the gate change is visually obvious.
    return (
      <PlaceholderTab
        title={gated.title}
        body="Coming in Phase 9D -- real content goes here."
      />
    );
  }

  switch (tab) {
    case "home":
      return (
        <PlaceholderTab
          title="Dashboard"
          body="Welcome back. Phase 9D will fill this with your participant snapshot -- current status, progress, next action."
        />
      );
    case "complete-profile":
      return (
        <div className="px-6 md:px-10 py-8 md:py-10 max-w-4xl">
          <ProfileCompletionChecklist />
        </div>
      );
    case "messages":
      return <PlaceholderTab title="Messages" body="Coming in Phase 9D." />;
    case "profile":
      return <PlaceholderTab title="Profile" body="Coming in Phase 9D." />;
    default:
      return (
        <PlaceholderTab
          title="Not found"
          body={`No tab matches "${tab}". Try a sidebar link.`}
        />
      );
  }
}

function PlaceholderTab({ title, body }: { title: string; body: string }) {
  return (
    <div className="px-6 md:px-10 py-8 md:py-10 max-w-4xl">
      <h1 className="text-3xl font-bold text-sage-navy mb-2">{title}</h1>
      <p className="text-gray-600 leading-relaxed">{body}</p>
    </div>
  );
}

export default function DashboardPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    }>
      <DashboardPageInner />
    </Suspense>
  );
}
