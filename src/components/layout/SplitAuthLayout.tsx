"use client";

import Image from "next/image";
import Link from "next/link";
import { type ReactNode } from "react";

interface SplitAuthLayoutProps {
  children: ReactNode;
  /** Multi-line title — \n becomes a visual line break. */
  heroTitle?: string;
  heroSubtitle?: string;
  /** Bottom-of-hero footer line (e.g. "Step 1 of 2 · Sign Up"). */
  heroFooter?: string;
}

/**
 * Split-screen auth layout used by /login, /enroll, and
 * /verify-email. Sage-navy hero panel on the left, light gray
 * form panel on the right.
 *
 * Mobile collapses to a stacked layout — hero on top, form below.
 *
 * Does NOT render any website chrome (Navbar/Footer); pages using
 * this layout must be in ChromeWrapper.FULLSCREEN_PREFIXES so the
 * root layout skips its marketing chrome.
 */
export default function SplitAuthLayout({
  children,
  heroTitle = "Engineering intelligence.\nEmpowering growth.",
  heroSubtitle =
    "IT consulting, career development, and hands-on technology training — every program comes with a dedicated mentor who guides you 1:1.",
  heroFooter = "Built for one-on-one mentorship from day one.",
}: SplitAuthLayoutProps) {
  return (
    <div className="min-h-screen flex flex-col md:flex-row bg-gray-50">
      {/* LEFT — Sage Navy hero */}
      <aside className="md:w-1/2 bg-sage-navy text-white flex flex-col justify-between p-10 md:p-16 min-h-[280px] md:min-h-screen relative overflow-hidden">
        {/* Subtle copper ambient glow, scoped to this panel only */}
        <div
          className="pointer-events-none absolute -bottom-32 -right-32 w-96 h-96 rounded-full opacity-20 blur-3xl"
          style={{ background: "#C87D5C" }}
          aria-hidden="true"
        />

        {/* Top: logo + wordmark, links back to marketing site */}
        <Link
          href="/"
          className="relative z-10 inline-flex items-center gap-3 w-fit group"
          aria-label="Sage IT Co home"
        >
          <Image
            src="/sage_logo.png"
            alt="Sage IT Co"
            width={48}
            height={48}
            priority
            className="rounded-md object-contain transition-transform group-hover:scale-105"
          />
          <span className="text-xl md:text-2xl font-bold tracking-tight">
            Sage IT Co
          </span>
        </Link>

        {/* Middle: hero copy */}
        <div className="relative z-10 max-w-md py-10 md:py-0">
          <h1 className="text-3xl md:text-5xl font-bold leading-tight mb-5 whitespace-pre-line">
            {heroTitle}
          </h1>
          <p className="text-base md:text-lg text-white/80 leading-relaxed">
            {heroSubtitle}
          </p>
        </div>

        {/* Bottom: tagline / step indicator */}
        <p className="relative z-10 text-sm text-white/60">{heroFooter}</p>
      </aside>

      {/* RIGHT — form */}
      <section className="md:w-1/2 flex items-center justify-center p-6 sm:p-10 md:p-16">
        <div className="w-full max-w-md">{children}</div>
      </section>
    </div>
  );
}
