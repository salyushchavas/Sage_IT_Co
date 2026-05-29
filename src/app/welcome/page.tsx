"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { motion } from "framer-motion";
import {
  AlertCircle, ArrowRight, CheckCircle2, Clock, Loader2, Mail,
  RefreshCw, Users,
} from "lucide-react";

import OnboardingLayout from "@/components/layouts/OnboardingLayout";
import { useAuth } from "@/lib/auth-context";
import {
  getOnboardingRoute, getParticipantMe, getProgramSelection,
  getWelcomeStatus, isDashboardStatus, refreshWelcomeStatus,
  type ProgramSelectionDTO, type UserDTO, type WelcomeStatus,
} from "@/lib/api";

/**
 * Step 8 — Welcome / team assembly status.
 *
 * Holding page between agreement completion (Phase 3B) and Gate 5
 * (ERM + at least one coach assigned). Polls
 * GET /participants/welcome-status every 5 seconds and updates the
 * checklist + team cards as the OnboardingService chain runs.
 *
 * Once {@code dashboardReady === true}, the page enables the
 * "Enter Dashboard" button. Auto-navigation kicks in after a
 * 2-second delay so the user actually sees the success state.
 */

const POLL_INTERVAL_MS = 5_000;

const COACH_LABELS = [
  "Career Coach",
  "Resume Specialist",
  "Technical Advisor",
  "Interview Coach",
] as const;

export default function WelcomePage() {
  const router = useRouter();
  const { isAuthenticated, isLoading: authLoading, refreshUser } = useAuth();

  const [gateChecked, setGateChecked] = useState(false);
  const [gateError, setGateError] = useState("");
  const [profile, setProfile] = useState<UserDTO | null>(null);
  const [program, setProgram] = useState<ProgramSelectionDTO | null>(null);
  const [status, setStatus] = useState<WelcomeStatus>({});
  const [refreshing, setRefreshing] = useState(false);

  // ── Gate + initial load ──────────────────────────────────────
  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const me = await getParticipantMe();
        if (cancelled) return;
        const s = me.currentStatus;
        // Past the welcome step — go to the dashboard.
        if (isDashboardStatus(s)) {
          router.replace("/dashboard");
          return;
        }
        // Earlier in the lifecycle — bounce back to that step.
        const earlierSteps = [
          "DRAFT_STARTED", "BASIC_INFO_SUBMITTED",
          "EMAIL_VERIFICATION_PENDING", "EMAIL_VERIFIED",
          "PARTICIPANT_ID_CREATED", "ID_EMAIL_SENT",
          "ACKNOWLEDGMENT_ACCEPTED", "DOCUMENTS_SUBMITTED",
          "DOC_REVIEW_PENDING", "PROGRAM_SELECTED",
          "AGREEMENT_SENT", "AGREEMENT_COMPLETED",
        ];
        if (s && earlierSteps.includes(s)) {
          router.replace(getOnboardingRoute(s));
          return;
        }
        // Eligible (CHECK_COPY_UPLOADED → COACHES_ASSIGNED) OR
        // unknown/null. Render and let the poll loop show progress.
        setProfile(me);
        const [progRes, statusRes] = await Promise.allSettled([
          getProgramSelection(),
          getWelcomeStatus(),
        ]);
        if (progRes.status === "fulfilled") setProgram(progRes.value);
        if (statusRes.status === "fulfilled") setStatus(statusRes.value);
        setGateChecked(true);
      } catch (err) {
        if (!cancelled) {
          setGateError(err instanceof Error ? err.message : "Couldn't load welcome status");
          setGateChecked(true);
        }
      }
    })();
    return () => { cancelled = true; };
  }, [authLoading, isAuthenticated, router]);

  // ── Polling ──────────────────────────────────────────────────
  useEffect(() => {
    if (!gateChecked) return;
    if (status.dashboardReady) return;
    let cancelled = false;
    const tick = async () => {
      try {
        const s = await getWelcomeStatus();
        if (cancelled) return;
        setStatus(s);
      } catch { /* poll retries */ }
    };
    const t = setInterval(tick, POLL_INTERVAL_MS);
    return () => { cancelled = true; clearInterval(t); };
  }, [gateChecked, status.dashboardReady]);

  // ── Auto-redirect once ready ─────────────────────────────────
  useEffect(() => {
    if (!status.dashboardReady) return;
    const t = setTimeout(async () => {
      // Refresh the auth context so /dashboard sees the new
      // DASHBOARD_ENABLED status; without this its routing guard
      // would still see the prior status and bounce the user back.
      await refreshUser();
      router.replace("/dashboard");
    }, 2200);
    return () => clearTimeout(t);
  }, [status.dashboardReady, router, refreshUser]);

  const handleRefresh = async () => {
    setRefreshing(true);
    try {
      const s = await refreshWelcomeStatus();
      setStatus(s);
    } catch (err) {
      console.warn("refresh failed", err);
    } finally {
      setRefreshing(false);
    }
  };

  // ── Render ───────────────────────────────────────────────────
  if (authLoading || !gateChecked) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }
  if (gateError) {
    return (
      <OnboardingLayout currentStep={8} contentMaxWidth="xl">
        <div className="bg-white rounded-2xl shadow-lg border border-gray-100 p-6 text-center">
          <AlertCircle size={20} className="text-red-600 inline-block mb-2" />
          <p className="text-sm text-red-700">{gateError}</p>
          <Link href="/dashboard" className="text-xs text-sage-navy font-semibold hover:underline mt-3 inline-block">
            Go to dashboard
          </Link>
        </div>
      </OnboardingLayout>
    );
  }

  const firstName = profile?.fullName?.split(" ")[0] ?? "there";
  const coaches = status.coaches ?? {};
  const dashboardReady = !!status.dashboardReady;

  return (
    <OnboardingLayout currentStep={8} contentMaxWidth="3xl">
      <motion.section
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
        className="bg-white rounded-2xl shadow-lg border border-gray-100 px-5 py-6 sm:px-7 sm:py-7"
      >
        <div className="text-center">
          <div className="inline-flex items-center justify-center w-12 h-12 rounded-2xl bg-sage-navy/5 text-sage-navy mb-3 text-2xl">
            🎉
          </div>
          <h1 className="font-serif text-2xl sm:text-3xl font-bold text-gray-900">
            Welcome to Sage IT Co, {firstName}!
          </h1>
          <p className="text-sm text-gray-500 mt-2 max-w-lg mx-auto">
            Your agreement is signed and your enrollment is confirmed.
            We&apos;re setting up your team now.
          </p>
        </div>

        {/* Onboarding status checklist */}
        <div className="mt-6">
          <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-2">
            Your onboarding status
          </p>
          <ul className="rounded-xl border border-gray-200 bg-gray-50/40 divide-y divide-gray-100">
            <StatusRow done label="Agreement signed and verified" />
            <StatusRow done label="Signed agreement sent to your team" />
            <StatusRow done={!!status.welcomeEmailSent} label="Welcome email sent" />
            <StatusRow done={!!status.coordinatorIntroSent} label="Program coordinator introduction sent" />
            <StatusRow done={!!status.ermAssigned}
              label={status.ermName
                ? `Relationship manager assigned: ${status.ermName}`
                : "Assigning your relationship manager..."}
              inProgress={!status.ermAssigned} />
            <StatusRow done={!!status.coachesAssigned}
              label="Assigning your coaching team..."
              inProgress={!status.coachesAssigned} />
            <StatusRow done={dashboardReady}
              label={dashboardReady ? "Dashboard ready" : "Preparing your dashboard..."}
              inProgress={!dashboardReady} />
          </ul>
        </div>

        {/* Team cards */}
        <div className="mt-6">
          <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-2 flex items-center gap-1.5">
            <Users size={11} /> Your team
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2.5">
            <TeamCard
              role="Relationship Manager"
              name={status.ermName ?? null}
              email={status.ermEmail ?? null}
            />
            {COACH_LABELS.map((label) => {
              const name = coaches[label];
              const isPending = !name || name === "Awaiting assignment";
              return (
                <TeamCard
                  key={label}
                  role={label}
                  name={isPending ? null : name}
                />
              );
            })}
          </div>
        </div>

        {/* Footer actions */}
        <div className="mt-6 flex flex-col sm:flex-row items-center justify-between gap-3">
          <div className="text-xs text-gray-500 space-y-0.5">
            {profile?.participantId && (
              <p>Participant ID: <span className="font-mono text-gray-700">{profile.participantId}</span></p>
            )}
            {program?.program && (
              <p>Program: <span className="text-gray-700">{program.program}</span></p>
            )}
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={handleRefresh}
              disabled={refreshing}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-white border border-gray-200 text-gray-600 hover:border-sage-navy hover:text-sage-navy disabled:opacity-50 cursor-pointer transition"
            >
              <RefreshCw size={12} className={refreshing ? "animate-spin" : ""} />
              {refreshing ? "Checking…" : "Check now"}
            </button>
            <button
              type="button"
              onClick={async () => {
                await refreshUser();
                router.replace("/dashboard");
              }}
              disabled={!dashboardReady}
              className={
                "inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-bold transition "
                + (dashboardReady
                    ? "bg-sage-navy text-white hover:bg-sage-navy-deep shadow-md hover:shadow-lg cursor-pointer"
                    : "bg-gray-200 text-gray-500 cursor-not-allowed")
              }
            >
              Enter dashboard <ArrowRight size={14} />
            </button>
          </div>
        </div>
      </motion.section>
    </OnboardingLayout>
  );
}

function StatusRow({ done, inProgress, label }: {
  done: boolean;
  inProgress?: boolean;
  label: string;
}) {
  return (
    <li className="flex items-center gap-3 px-4 py-2.5 text-sm">
      {done ? (
        <CheckCircle2 size={16} className="text-emerald-600 shrink-0" />
      ) : inProgress ? (
        <Loader2 size={16} className="animate-spin text-sage-navy shrink-0" />
      ) : (
        <Clock size={16} className="text-gray-300 shrink-0" />
      )}
      <span className={done ? "text-gray-800" : inProgress ? "text-sage-navy font-medium" : "text-gray-400"}>
        {label}
      </span>
    </li>
  );
}

function TeamCard({ role, name, email }: {
  role: string;
  name: string | null;
  email?: string | null;
}) {
  return (
    <div className={
      "rounded-xl border p-3 transition "
      + (name
          ? "border-emerald-200 bg-emerald-50/40"
          : "border-dashed border-gray-200 bg-gray-50/60")
    }>
      <p className="text-[10px] uppercase tracking-wider font-bold text-gray-500">
        {role}
      </p>
      <p className={"mt-1 text-sm font-semibold " + (name ? "text-gray-900" : "text-gray-400 italic")}>
        {name ?? "Awaiting…"}
      </p>
      {email && (
        <p className="mt-1 text-[11px] text-gray-500 inline-flex items-center gap-1">
          <Mail size={10} /> {email}
        </p>
      )}
    </div>
  );
}
