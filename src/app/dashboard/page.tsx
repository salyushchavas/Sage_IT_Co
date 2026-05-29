"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Loader2 } from "lucide-react";

import DashboardLayout from "@/components/layout/DashboardLayout";
import ProfileCompletionBanner from "@/components/dashboard/ProfileCompletionBanner";
import ProfileCompletionChecklist from "@/components/dashboard/ProfileCompletionChecklist";
import { useAuth } from "@/lib/auth-context";
import {
  getOnboardingRoute,
  getProfileCompletion,
  isDashboardStatus,
  type ProfileCompletion,
} from "@/lib/api";

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
      {renderTab(activeTab)}
    </DashboardLayout>
  );
}

function renderTab(tab: string) {
  // Tab ids match Spire's NAV array exactly (home, complete-profile,
  // courses, weekly, resume, interview, employment, payments,
  // documents, agreement, team, messages, profile -- 13 tabs).
  switch (tab) {
    case "home":
      return (
        <PlaceholderTab
          title="Dashboard"
          body="Welcome back. Phase 9C/9D will fill this with your participant snapshot -- current status, progress, next action."
        />
      );
    case "complete-profile":
      return (
        <div className="px-6 md:px-10 py-8 md:py-10 max-w-4xl">
          <ProfileCompletionChecklist />
        </div>
      );
    case "courses":
      return (
        <PlaceholderTab
          title="My Courses"
          body="Phase 9C will surface the locked-until-onboarded course view here."
        />
      );
    case "weekly":
      return <PlaceholderTab title="Weekly Report" body="Coming in Phase 9D." />;
    case "resume":
      return <PlaceholderTab title="Resume" body="Coming in Phase 9D." />;
    case "interview":
      return <PlaceholderTab title="Interviews" body="Coming in Phase 9D." />;
    case "employment":
      return <PlaceholderTab title="Employment" body="Coming in Phase 9D." />;
    case "payments":
      return <PlaceholderTab title="Payments" body="Coming in Phase 9D." />;
    case "documents":
      return <PlaceholderTab title="Documents" body="Coming in Phase 9D." />;
    case "agreement":
      return <PlaceholderTab title="Agreement" body="Coming in Phase 9D." />;
    case "team":
      return <PlaceholderTab title="My Team" body="Coming in Phase 9D." />;
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
