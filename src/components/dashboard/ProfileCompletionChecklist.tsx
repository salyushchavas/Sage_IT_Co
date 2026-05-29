"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { CheckCircle2, ChevronRight, Lock, Loader2, PartyPopper } from "lucide-react";

import { useAuth } from "@/lib/auth-context";
import {
  getProfileCompletion,
  type ProfileCompletion,
  type ProfileCompletionStep,
} from "@/lib/api";
import BasicInfoStep from "./BasicInfoStep";

/**
 * The "Complete Your Profile" tab body. Lists the six steps in order
 * with one of three rendered states:
 *
 *   - Completed: green check + completion date placeholder.
 *   - Current (first uncompleted): expanded with the active CTA.
 *   - Future: locked, shows "Complete the previous step first".
 *
 * Step 1 ("About You") embeds {@link BasicInfoStep} inline so the
 * user can finish it without leaving the dashboard. Steps 2-6 link
 * to their standalone pages (acknowledgment, documents, etc.) which
 * redirect back to /dashboard?tab=complete-profile on success.
 */
const STANDALONE_HREF: Record<ProfileCompletionStep["key"], string> = {
  BASIC_INFO: "#",
  ACKNOWLEDGMENT: "/acknowledgment?from=profile",
  DOCUMENTS: "/document-upload?from=profile",
  PROGRAM_SELECTION: "/program-selection?from=profile",
  AGREEMENT: "/agreement?from=profile",
  CHECK_UPLOAD: "/check-upload?from=profile",
};

export default function ProfileCompletionChecklist() {
  const { refreshUser } = useAuth();
  const searchParams = useSearchParams();
  // When a step page redirects back with ?step=DOCUMENTS, scroll
  // that row into view + flash a "previous step completed" toast.
  const stepParam = searchParams.get("step");
  const [data, setData] = useState<ProfileCompletion | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const handledStepRef = useRef<string | null>(null);

  const reload = async () => {
    setLoading(true);
    try {
      const res = await getProfileCompletion();
      setData(res);
      await refreshUser();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't load progress");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Auto-scroll + toast once data is loaded and we have a step hint.
  // Handled-step ref prevents repeats on re-render -- once we've
  // honoured a given ?step=XXX we ignore it until the value changes.
  useEffect(() => {
    if (!data || !stepParam || handledStepRef.current === stepParam) return;
    handledStepRef.current = stepParam;
    const target = document.getElementById(`step-${stepParam}`);
    if (target) {
      target.scrollIntoView({ behavior: "smooth", block: "center" });
    }
    setToast("Step completed! Continue with the next one below.");
    const t = setTimeout(() => setToast(null), 3500);
    return () => clearTimeout(t);
  }, [data, stepParam]);

  if (loading && !data) {
    return (
      <div className="py-12 flex justify-center">
        <Loader2 size={24} className="animate-spin text-sage-navy" />
      </div>
    );
  }
  if (error || !data) {
    return (
      <div className="p-4 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
        {error || "Couldn't load progress"}
      </div>
    );
  }

  // Find the index of the first incomplete step -- that's the active row.
  const activeIdx = data.steps.findIndex((s) => !s.completed);

  return (
    <div className="space-y-4">
      {toast && (
        <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-2.5 text-sm font-semibold text-emerald-800 inline-flex items-center gap-2 shadow-sm">
          <PartyPopper size={16} /> {toast}
        </div>
      )}
      <header>
        <h2 className="text-2xl font-bold text-gray-900">
          Complete Your Profile
        </h2>
        <p className="text-sm text-gray-500 mt-1">
          Finish these {data.totalSteps} steps to unlock course enrollment and your dedicated team.
        </p>
        <div className="mt-3 flex items-center gap-3">
          <div className="flex-1 h-2 rounded-full bg-gray-200 overflow-hidden">
            <div
              className="h-full bg-sage-navy transition-all"
              style={{ width: `${data.completionPercentage}%` }}
            />
          </div>
          <span className="text-xs font-bold text-sage-navy">
            {data.completionPercentage}% · {data.completedSteps} of {data.totalSteps}
          </span>
        </div>
      </header>

      <ol className="space-y-3">
        {data.steps.map((step, idx) => {
          const isCompleted = step.completed;
          const isActive = idx === activeIdx;
          return (
            <li
              key={step.key}
              id={`step-${step.key}`}
              className={
                "rounded-xl border bg-white p-4 transition scroll-mt-24 " +
                (isCompleted
                  ? "border-emerald-200 bg-emerald-50/40"
                  : isActive
                    ? "border-sage-navy shadow-md ring-2 ring-sage-navy/10"
                    : "border-gray-200 opacity-70")
              }
            >
              <div className="flex items-start gap-3">
                <div
                  className={
                    "shrink-0 inline-flex items-center justify-center w-8 h-8 rounded-full text-xs font-bold " +
                    (isCompleted
                      ? "bg-emerald-600 text-white"
                      : isActive
                        ? "bg-sage-navy text-white animate-pulse"
                        : "bg-gray-100 text-gray-400")
                  }
                >
                  {isCompleted ? (
                    <CheckCircle2 size={14} />
                  ) : isActive ? (
                    idx + 1
                  ) : (
                    <Lock size={12} />
                  )}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center justify-between gap-3 flex-wrap">
                    <p className="text-sm font-bold text-gray-900">
                      Step {idx + 1}: {step.title}
                    </p>
                    {!isCompleted && (
                      <span className="text-[11px] text-gray-500">
                        {step.estimatedTime}
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-gray-500 mt-0.5">{step.description}</p>

                  {isCompleted && (
                    <p className="text-[11px] text-emerald-700 mt-1.5 font-semibold">
                      Completed
                    </p>
                  )}

                  {isActive && step.key === "BASIC_INFO" && (
                    <div className="mt-4">
                      <BasicInfoStep onComplete={reload} />
                    </div>
                  )}

                  {isActive && step.key !== "BASIC_INFO" && (
                    <Link
                      href={STANDALONE_HREF[step.key]}
                      className="mt-3 inline-flex items-center gap-1 bg-sage-navy hover:bg-sage-navy-deep text-white text-xs font-bold px-4 py-2 rounded-lg shadow-sm transition cursor-pointer"
                    >
                      Start <ChevronRight size={12} />
                    </Link>
                  )}

                  {!isCompleted && !isActive && (
                    <p className="text-[11px] text-gray-400 mt-1.5 italic">
                      Locked -- complete the previous step first.
                    </p>
                  )}
                </div>
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
