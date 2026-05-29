"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Loader2 } from "lucide-react";

import DashboardLayout from "@/components/layout/DashboardLayout";
import { useAuth } from "@/lib/auth-context";
import { getOnboardingRoute, isDashboardStatus } from "@/lib/api";

/**
 * Participant dashboard surface.
 *
 * Routing rules (in order):
 *   1. Not signed in → /login
 *   2. ADMIN role → /admin (preserves existing admin panel)
 *   3. INSTRUCTOR role → /admin (Sage doesn't have a separate
 *      instructor surface yet; falls back to admin)
 *   4. No participantId → /enroll
 *   5. currentStatus not a dashboard-tier value → matching
 *      onboarding step
 *   6. Otherwise → render the sidebar shell with placeholder tabs
 *
 * Tab content is intentionally stubbed in Phase 9A. Real content
 * lands in 9B (Complete Profile), 9C (Courses + Wishlist locked
 * views), and 9D (everything else).
 */
function DashboardPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const activeTab = searchParams.get("tab") || "overview";

  const { user, isLoading, refreshUser } = useAuth();
  const [routingDecided, setRoutingDecided] = useState(false);

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
      // copy is missing critical fields — the auth context can hold
      // stale data after soft navigations.
      let status = user.currentStatus;
      let participantId = user.participantId;
      if (!status || !participantId) {
        try {
          await refreshUser();
        } catch {
          // ignore — we'll fall through to the not-enrolled branch
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

  if (isLoading || !routingDecided) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <DashboardLayout activeTab={activeTab}>
      {renderTab(activeTab)}
    </DashboardLayout>
  );
}

function renderTab(tab: string) {
  switch (tab) {
    case "overview":
      return (
        <PlaceholderTab
          title="Overview"
          body="Welcome back. Phase 9B will fill this with your participant snapshot — current status, progress, next action."
        />
      );
    case "complete-profile":
      return (
        <PlaceholderTab
          title="Complete Your Profile"
          body="Phase 9B will surface the onboarding checklist here (Acknowledgment, Documents, Program Selection, Agreement, Check Upload, About You)."
        />
      );
    case "courses":
      return (
        <PlaceholderTab
          title="My Courses"
          body="Phase 9C will surface the locked-until-onboarded course view here."
        />
      );
    case "wishlist":
      return (
        <PlaceholderTab
          title="My Wishlist"
          body="Phase 9C will surface saved courses here."
        />
      );
    case "weekly-report":
      return <PlaceholderTab title="Weekly Report" body="Coming in Phase 9D." />;
    case "resume":
      return <PlaceholderTab title="Resume" body="Coming in Phase 9D." />;
    case "interviews":
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
