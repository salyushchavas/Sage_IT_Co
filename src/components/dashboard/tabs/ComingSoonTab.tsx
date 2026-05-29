"use client";

import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { type ReactNode } from "react";

/**
 * Friendly "this surface ships in a later phase" card. Used for
 * the dashboard tabs that have real backend support but the full
 * UI port is deferred (Weekly Report, Resume, Interviews,
 * Employment, Payments). Matches Spire's PlaceholderCard shape so
 * the visual treatment is consistent across the dashboard.
 */
interface Props {
  title: string;
  copy: string;
  hint?: string;
  link?: { label: string; href: string };
}

export default function ComingSoonTab({ title, copy, hint, link }: Props) {
  return (
    <div className="space-y-3">
      <h1 className="text-2xl font-bold text-gray-900">{title}</h1>
      <div className="rounded-2xl border border-dashed border-gray-200 bg-gray-50/60 p-6">
        <p className="text-sm text-gray-700">{copy}</p>
        {hint && <p className="text-xs text-gray-500 mt-2">{hint}</p>}
        {link && (
          <Link
            href={link.href}
            className="mt-3 inline-flex items-center gap-1 text-sm font-semibold text-sage-navy hover:underline"
          >
            {link.label} <ArrowRight size={13} />
          </Link>
        )}
      </div>
    </div>
  );
}

/** Re-exported for tabs that want to render their own custom node. */
export function ComingSoonShell({ children }: { children: ReactNode }) {
  return (
    <div className="rounded-2xl border border-dashed border-gray-200 bg-gray-50/60 p-6">
      {children}
    </div>
  );
}
