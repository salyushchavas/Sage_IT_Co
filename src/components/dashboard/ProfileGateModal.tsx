"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { X, ChevronRight, Loader2 } from "lucide-react";

import { getProfileCompletion, ProfileCompletion } from "@/lib/api";

/**
 * Modal shown when an incomplete-profile user clicks Enroll / Add
 * to Cart / Checkout. Fetches the live snapshot so the list of
 * remaining steps is always current.
 */
interface Props {
  open: boolean;
  onClose: () => void;
}

export default function ProfileGateModal({ open, onClose }: Props) {
  const [data, setData] = useState<ProfileCompletion | null>(null);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    getProfileCompletion()
      .then((res) => { if (!cancelled) setData(res); })
      .catch(() => { /* leave data null; modal shows a generic message */ });
    return () => { cancelled = true; };
  }, [open]);

  if (!open) return null;

  const remaining = data?.steps.filter((s) => !s.completed) ?? [];

  return (
    <div className="fixed inset-0 z-[60] bg-black/40 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-white rounded-2xl shadow-2xl border border-gray-100 w-full max-w-md p-6 relative">
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="absolute top-3 right-3 text-gray-400 hover:text-gray-700 cursor-pointer"
        >
          <X size={18} />
        </button>

        <h2 className="text-xl font-bold text-gray-900">
          Complete your profile to enroll
        </h2>
        {data ? (
          <p className="text-sm text-gray-600 mt-1">
            You&apos;re{" "}
            <span className="font-bold text-sage-navy">
              {data.completionPercentage}%
            </span>{" "}
            there! Finish these steps:
          </p>
        ) : (
          <p className="text-sm text-gray-500 mt-1 inline-flex items-center gap-1.5">
            <Loader2 size={12} className="animate-spin" /> Loading your progress…
          </p>
        )}

        {remaining.length > 0 && (
          <ul className="mt-4 space-y-2">
            {remaining.map((step) => (
              <li
                key={step.key}
                className="flex items-center justify-between gap-2 text-sm text-gray-700"
              >
                <span className="inline-flex items-center gap-2">
                  <span className="inline-block w-1.5 h-1.5 rounded-full bg-amber-500" />
                  {step.title}
                </span>
                <span className="text-[11px] text-gray-400">{step.estimatedTime}</span>
              </li>
            ))}
          </ul>
        )}

        <div className="mt-6 flex items-center gap-2">
          <Link
            href="/dashboard?tab=complete-profile"
            className="flex-1 inline-flex items-center justify-center gap-1 bg-sage-navy hover:bg-sage-navy-deep text-white text-sm font-bold py-2.5 rounded-lg shadow-sm transition cursor-pointer"
            onClick={onClose}
          >
            Continue Setup <ChevronRight size={14} />
          </Link>
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2.5 text-sm font-semibold text-gray-600 hover:text-gray-900 cursor-pointer"
          >
            Maybe Later
          </button>
        </div>
      </div>
    </div>
  );
}
