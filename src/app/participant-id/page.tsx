"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { motion } from "framer-motion";
import { CheckCircle2, Copy, Loader2, Mail } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import { useAuth } from "@/lib/auth-context";
import { getParticipantMe } from "@/lib/api";

/**
 * Read-only participant-ID display. Not part of the active
 * onboarding chain (verify-email routes straight to /dashboard now),
 * but stays reachable from the profile menu + sidebar so users can
 * look their ID up at any time.
 *
 * No completion-state guard — any signed-in user can view this.
 */
export default function ParticipantIdPage() {
  const router = useRouter();
  const { user, isAuthenticated, isLoading: authLoading } = useAuth();

  const [participantId, setParticipantId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    let cancelled = false;
    setLoading(true);
    getParticipantMe()
      .then((profile) => {
        if (cancelled) return;
        setParticipantId(profile.participantId ?? null);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : "Couldn't load participant ID");
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [authLoading, isAuthenticated, router]);

  const handleCopy = async () => {
    if (!participantId) return;
    try {
      await navigator.clipboard.writeText(participantId);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      /* clipboard blocked — silently ignore */
    }
  };

  if (authLoading || loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <SplitAuthLayout
      heroTitle={"Welcome to\nSage IT Co."}
      heroSubtitle="Every participant gets a unique ID that follows them through the program — use it on tickets, emails, and any correspondence with the team."
      heroFooter="Your participant ID"
    >
      <motion.section
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35 }}
        className="text-center"
      >
        <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-emerald-50 text-emerald-700 mb-4">
          <CheckCircle2 size={26} />
        </div>
        <h2 className="text-3xl font-bold text-sage-navy">
          Your Participant ID
        </h2>
        <p className="text-sm text-gray-600 mt-2">
          Use this ID in all future communications. A copy was also emailed to you.
        </p>

        {error && (
          <div className="mt-5 px-4 py-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm text-left">
            {error}
          </div>
        )}

        <div className="mt-6 inline-flex items-center gap-3 rounded-xl border border-sage-navy/20 bg-sage-navy/5 px-5 py-4 shadow-sm">
          <code className="font-mono font-bold text-xl tracking-[0.2em] text-sage-navy">
            {participantId ?? "—"}
          </code>
          <button
            type="button"
            onClick={handleCopy}
            disabled={!participantId}
            className="inline-flex items-center gap-1.5 rounded-lg border border-sage-navy bg-white px-3 py-1.5 text-xs font-bold text-sage-navy hover:bg-sage-navy hover:text-white transition disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
          >
            <Copy size={12} /> {copied ? "Copied!" : "Copy"}
          </button>
        </div>

        <p className="mt-4 inline-flex items-center gap-1.5 text-xs text-gray-500">
          <Mail size={12} /> Emailed to {user?.email ?? "your inbox"}.
        </p>

        <Link
          href="/dashboard"
          className="mt-7 inline-flex w-full items-center justify-center gap-2 bg-sage-navy hover:bg-sage-navy-deep text-white text-sm font-semibold px-6 py-3 rounded-lg transition"
        >
          Continue to Dashboard →
        </Link>
      </motion.section>
    </SplitAuthLayout>
  );
}
