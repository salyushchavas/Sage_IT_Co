"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { CheckCircle2, ChevronRight, Sparkles, X } from "lucide-react";

import { useAuth } from "@/lib/auth-context";
import { getProfileCompletion, type ProfileCompletion } from "@/lib/api";

/**
 * Sticky banner shown above the dashboard content when the
 * participant's profile is < 100% complete. Two modes:
 *
 *   - Active: pct + next-step hint + "Continue Setup" button +
 *     "Remind me later" link that hides the banner for 24h.
 *   - Celebration: 5-second confirmation card the first time the
 *     user crosses 100%, then disappears.
 *
 * The 24h dismissal lives in localStorage so a refresh during the
 * 24h window doesn't show the banner again. After the window
 * elapses, the next render fetches a fresh snapshot.
 */
const STORAGE_KEY = "profile_banner_dismissed_until";
const CELEBRATION_KEY = "profile_completion_celebration_shown";

interface Props {
  /** Called when the user clicks "Continue Setup" so the parent can switch tabs. */
  onContinueSetup?: () => void;
}

// Sage's ProfileCompletion uses `complete?: boolean`. We accept the
// legacy `isComplete` shape too so a future backend rename doesn't
// silently break the celebration check.
type Completable = ProfileCompletion & {
  isComplete?: boolean;
  complete?: boolean;
};

export default function ProfileCompletionBanner({ onContinueSetup }: Props) {
  const { user } = useAuth();
  const [data, setData] = useState<Completable | null>(null);
  const [dismissedUntil, setDismissedUntil] = useState<number>(0);
  const [celebrationShown, setCelebrationShown] = useState(false);
  const [showCelebration, setShowCelebration] = useState(false);

  // Hydrate dismissal + celebration flags from localStorage on mount.
  useEffect(() => {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) setDismissedUntil(parseInt(raw, 10) || 0);
    setCelebrationShown(localStorage.getItem(CELEBRATION_KEY) === "1");
  }, []);

  // Fetch profile completion on mount + whenever the user's
  // completion percentage changes (e.g. after they finish a step).
  useEffect(() => {
    let cancelled = false;
    if (!user) return;
    getProfileCompletion()
      .then((res) => { if (!cancelled) setData(res as Completable); })
      .catch(() => { /* swallow -- banner just stays hidden */ });
    return () => { cancelled = true; };
  }, [user, user?.profileCompletionPct]);

  // Detect the 0→100 transition once the data lands.
  useEffect(() => {
    if (!data) return;
    const complete = data.isComplete ?? data.complete ?? false;
    if (complete && !celebrationShown) {
      setShowCelebration(true);
      localStorage.setItem(CELEBRATION_KEY, "1");
      setCelebrationShown(true);
      const t = setTimeout(() => setShowCelebration(false), 5000);
      return () => clearTimeout(t);
    }
  }, [data, celebrationShown]);

  if (!data) return null;
  const complete = data.isComplete ?? data.complete ?? false;
  if (complete && !showCelebration) return null;
  if (!complete && Date.now() < dismissedUntil) return null;

  if (showCelebration) {
    return (
      <div className="mx-4 sm:mx-6 mt-4 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 flex items-center gap-3 shadow-sm">
        <Sparkles size={18} className="text-emerald-700 shrink-0" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-bold text-emerald-900">
            Profile complete! Welcome to Sage IT Co.
          </p>
          <p className="text-xs text-emerald-700">
            You can now enroll in courses and access every service.
          </p>
        </div>
      </div>
    );
  }

  const nextStep = data.steps.find((s) => !s.completed);
  const handleDismiss = () => {
    const until = Date.now() + 24 * 60 * 60 * 1000;
    localStorage.setItem(STORAGE_KEY, String(until));
    setDismissedUntil(until);
  };

  return (
    <div className="mx-4 sm:mx-6 mt-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 shadow-sm">
      <div className="flex items-start sm:items-center gap-3 flex-col sm:flex-row">
        <div className="flex items-center gap-2 shrink-0">
          <span className="inline-flex items-center justify-center w-8 h-8 rounded-lg bg-amber-100 text-amber-700">
            <CheckCircle2 size={16} />
          </span>
          <div>
            <p className="text-sm font-bold text-amber-900">
              Complete your profile to unlock the platform
            </p>
            <p className="text-[11px] text-amber-700">
              {data.completedSteps} of {data.totalSteps} done · {data.completionPercentage}%
            </p>
          </div>
        </div>
        <div className="flex-1 w-full">
          <div className="h-1.5 rounded-full bg-amber-100 overflow-hidden">
            <div
              className="h-full bg-amber-600 transition-all"
              style={{ width: `${data.completionPercentage}%` }}
            />
          </div>
          {nextStep && (
            <p className="text-[11px] text-amber-700 mt-1.5">
              Next: <span className="font-semibold">{nextStep.title}</span>{" "}
              ({nextStep.estimatedTime})
            </p>
          )}
        </div>
        <div className="flex items-center gap-2 shrink-0 self-end sm:self-auto">
          {onContinueSetup ? (
            <button
              type="button"
              onClick={onContinueSetup}
              className="inline-flex items-center gap-1 bg-amber-600 hover:bg-amber-700 text-white text-xs font-bold px-3 py-2 rounded-lg shadow-sm transition cursor-pointer"
            >
              Continue Setup <ChevronRight size={12} />
            </button>
          ) : (
            <Link
              href="/dashboard?tab=complete-profile"
              className="inline-flex items-center gap-1 bg-amber-600 hover:bg-amber-700 text-white text-xs font-bold px-3 py-2 rounded-lg shadow-sm transition cursor-pointer"
            >
              Continue Setup <ChevronRight size={12} />
            </Link>
          )}
          <button
            type="button"
            onClick={handleDismiss}
            title="Remind me later"
            className="text-amber-700 hover:text-amber-900 cursor-pointer p-1"
          >
            <X size={16} />
          </button>
        </div>
      </div>
    </div>
  );
}
