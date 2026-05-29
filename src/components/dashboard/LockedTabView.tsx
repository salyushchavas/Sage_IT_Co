"use client";

import Link from "next/link";
import { useEffect, useState, type ReactNode } from "react";
import { ChevronRight, Loader2, Lock } from "lucide-react";

import { getProfileCompletion, type ProfileCompletion } from "@/lib/api";

/**
 * Reused "locked" empty state for dashboard tabs that need a complete
 * profile before they show real data. Shows a friendly lock card with
 * the live completion %, remaining steps, and a single CTA to
 * Continue Setup. No backend error codes ever leak to the UI through
 * this surface.
 *
 * There's no "Browse Catalog" fallback by design -- the strict gate
 * blocks all course / service access until profile is 100% complete,
 * so the only useful CTA is finishing the profile.
 */
interface Props {
  title: string;
  subtitle: string;
  headline: string;
  body: ReactNode;
  /** Tab-switch callback. Provided by the dashboard page so the
   *  click stays inside the SPA instead of a full navigation.
   *  Falls back to a /dashboard?tab=complete-profile deep link. */
  onContinueSetup?: () => void;
}

export default function LockedTabView({
  title,
  subtitle,
  headline,
  body,
  onContinueSetup,
}: Props) {
  const [data, setData] = useState<ProfileCompletion | null>(null);

  useEffect(() => {
    let cancelled = false;
    getProfileCompletion()
      .then((res) => { if (!cancelled) setData(res); })
      .catch(() => { /* leave null -- card stays usable without the % */ });
    return () => { cancelled = true; };
  }, []);

  const remaining = data?.steps.filter((s) => !s.completed) ?? [];

  return (
    <div className="space-y-4">
      <header>
        <h2 className="text-2xl font-bold text-gray-900">{title}</h2>
        <p className="text-sm text-gray-500 mt-1">{subtitle}</p>
      </header>

      <div className="rounded-2xl border border-gray-200 bg-gradient-to-br from-sage-navy/5 via-white to-amber-50/40 p-6 sm:p-8 text-center">
        <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-white border border-gray-200 shadow-sm text-gray-400 mb-4">
          <Lock size={22} />
        </div>
        <h3 className="text-xl font-bold text-gray-900">{headline}</h3>
        <div className="text-sm text-gray-600 mt-1.5 max-w-md mx-auto">{body}</div>

        {data ? (
          <div className="mt-5 max-w-md mx-auto">
            <div className="flex items-center justify-between text-[11px] text-gray-500 mb-1">
              <span>Profile completion</span>
              <span className="font-bold text-sage-navy">
                {data.completionPercentage}% · {data.completedSteps} of {data.totalSteps}
              </span>
            </div>
            <div className="h-2 rounded-full bg-gray-200 overflow-hidden">
              <div
                className="h-full bg-sage-navy transition-all"
                style={{ width: `${data.completionPercentage}%` }}
              />
            </div>
          </div>
        ) : (
          <div className="mt-5 inline-flex items-center gap-1.5 text-xs text-gray-400">
            <Loader2 size={12} className="animate-spin" /> Loading progress…
          </div>
        )}

        {remaining.length > 0 && (
          <div className="mt-5 max-w-sm mx-auto text-left bg-white rounded-xl border border-gray-100 p-4">
            <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-2">
              Remaining
            </p>
            <ul className="space-y-1.5">
              {remaining.map((s) => (
                <li
                  key={s.key}
                  className="flex items-center justify-between text-xs text-gray-700"
                >
                  <span className="inline-flex items-center gap-2">
                    <span className="inline-block w-1.5 h-1.5 rounded-full bg-sage-copper" />
                    {s.title}
                  </span>
                  <span className="text-[11px] text-gray-400">{s.estimatedTime}</span>
                </li>
              ))}
            </ul>
          </div>
        )}

        <div className="mt-6 flex items-center justify-center">
          {onContinueSetup ? (
            <button
              type="button"
              onClick={onContinueSetup}
              className="inline-flex items-center gap-1 bg-sage-navy hover:bg-sage-navy-deep text-white text-sm font-bold px-5 py-2.5 rounded-lg shadow-sm transition cursor-pointer"
            >
              Continue Setup <ChevronRight size={14} />
            </button>
          ) : (
            <Link
              href="/dashboard?tab=complete-profile"
              className="inline-flex items-center gap-1 bg-sage-navy hover:bg-sage-navy-deep text-white text-sm font-bold px-5 py-2.5 rounded-lg shadow-sm transition cursor-pointer"
            >
              Continue Setup <ChevronRight size={14} />
            </Link>
          )}
        </div>
      </div>
    </div>
  );
}
