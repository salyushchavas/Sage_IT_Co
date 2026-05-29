"use client";

import type { ReactNode } from "react";
import Image from "next/image";
import Link from "next/link";
import OnboardingProgressBar from "@/components/OnboardingProgressBar";
import { BRAND } from "@/config/brand";

/**
 * Minimal full-viewport layout for every Phase 1B onboarding page
 * (/enroll, /verify-email, /participant-id, /acknowledgment,
 * /document-upload, /program-selection, /welcome).
 *
 *   ┌──── viewport ────────────────────────────────────────┐
 *   │           [Spire logo]    (small, centered)          │
 *   │           [OnboardingProgressBar]                    │
 *   │                                                      │
 *   │           {children}    (centered, max-w-2xl card)   │
 *   │                                                      │
 *   │           © 2026 Sage IT Co                     │
 *   └──────────────────────────────────────────────────────┘
 *
 * Owns no auth logic and reads no auth context — each page wraps
 * its own auth checks. Owns the visual chrome only.
 *
 * Pages MUST also live OUTSIDE the {@code (auth)} route group or
 * its two-column marketing layout will stack on top of this one.
 * The /enroll and /verify-email routes were relocated to the app
 * root for exactly that reason.
 */
export interface OnboardingLayoutProps {
  /** 1-indexed step number — drives the progress bar. */
  currentStep: number;
  /** Page-specific content (the form card, etc.). */
  children: ReactNode;
  /** Max width of the inner content area. Defaults to {@code 2xl}. */
  contentMaxWidth?: "xl" | "2xl" | "3xl";
  /** Override the default 9-step lifecycle labels (used by the quick-signup 2-step flow). */
  steps?: ReadonlyArray<string>;
}

const MAX_WIDTH_CLASS: Record<NonNullable<OnboardingLayoutProps["contentMaxWidth"]>, string> = {
  xl: "max-w-xl",
  "2xl": "max-w-2xl",
  "3xl": "max-w-3xl",
};

export default function OnboardingLayout({
  currentStep,
  children,
  contentMaxWidth = "2xl",
  steps,
}: OnboardingLayoutProps) {
  return (
    <div className="min-h-screen flex flex-col bg-[#F8FAFC]">
      {/* Header: logo only, centred, links back to public site.
          Vertical rhythm is tight so the whole layout (logo +
          progress bar + content card) stays in one viewport on a
          standard 1080-ish desktop screen. */}
      <header className="pt-4 sm:pt-5 pb-2 flex justify-center">
        <Link href="/" className="inline-flex items-center gap-2" aria-label={`${BRAND.name} home`}>
          <Image
            src={BRAND.logoUrl}
            alt={BRAND.logoAlt}
            width={32}
            height={32}
            priority
            className="h-8 w-8 object-contain"
          />
          <span className="text-sm font-bold text-sage-navy">
            {BRAND.name}
          </span>
        </Link>
      </header>

      <main className={`flex-1 w-full mx-auto px-4 sm:px-6 pb-6 ${MAX_WIDTH_CLASS[contentMaxWidth]}`}>
        <div className="mb-4">
          <OnboardingProgressBar currentStep={currentStep} steps={steps} />
        </div>
        {children}
      </main>

      <footer className="py-3 text-center text-[11px] text-gray-400">
        © {BRAND.copyrightYear} {BRAND.legalName}. All rights reserved.
      </footer>
    </div>
  );
}
