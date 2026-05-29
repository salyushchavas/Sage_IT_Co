"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { motion } from "framer-motion";
import { CheckCircle2, Copy, Loader2, Mail } from "lucide-react";

import { useAuth } from "@/lib/auth-context";
import { getParticipantMe } from "@/lib/api";

/**
 * Phase 1C — read-only participant-ID display. The page is no longer
 * part of the main onboarding flow (verify-email now routes straight
 * to /dashboard), but stays reachable for users who want to look
 * their ID up. Linked from the profile menu + sidebar.
 *
 * No routing guard — anyone signed-in can view this page, regardless
 * of where their currentStatus says they are.
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
      <div className="min-h-screen flex items-center justify-center bg-[#F8FAFC]">
        <Loader2 size={28} className="animate-spin text-[#1B2A5C]" />
      </div>
    );
  }

  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-[#F8FAFC] px-4">
      <motion.section
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35 }}
        className="w-full max-w-md bg-white rounded-2xl shadow-lg border border-gray-100 p-6 sm:p-8 text-center"
      >
        <div className="inline-flex items-center justify-center w-12 h-12 rounded-2xl bg-emerald-50 text-emerald-700 mb-4">
          <CheckCircle2 size={22} />
        </div>
        <h1 className="font-serif text-2xl font-bold text-gray-900">
          Your Participant ID
        </h1>
        <p className="text-sm text-gray-500 mt-2">
          Use this ID in all future communications. A copy was also emailed to you.
        </p>

        {error && (
          <div className="mt-5 p-3 rounded-lg bg-red-50 border border-red-200 text-red-600 text-sm text-left">
            {error}
          </div>
        )}

        <div className="mt-6 inline-flex items-center gap-3 rounded-xl border border-gray-200 bg-[#F0F2F8] px-5 py-4 shadow-sm">
          <code className="font-mono font-bold text-xl tracking-[0.2em] text-[#1B2A5C]">
            {participantId ?? "—"}
          </code>
          <button
            type="button"
            onClick={handleCopy}
            disabled={!participantId}
            className="inline-flex items-center gap-1.5 rounded-lg border border-[#1B2A5C] bg-white px-3 py-1.5 text-xs font-bold text-[#1B2A5C] hover:bg-[#1B2A5C] hover:text-white transition disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
          >
            <Copy size={12} /> {copied ? "Copied!" : "Copy"}
          </button>
        </div>

        <p className="mt-4 inline-flex items-center gap-1.5 text-xs text-gray-500">
          <Mail size={12} /> Emailed to {user?.email ?? "your inbox"}.
        </p>

        <Link
          href="/dashboard"
          className="mt-7 inline-flex items-center justify-center gap-2 bg-[#1B2A5C] hover:bg-[#2D4480] text-white text-sm font-bold px-6 py-2.5 rounded-lg shadow-sm hover:shadow-md transition"
        >
          ← Back to Dashboard
        </Link>
      </motion.section>
    </div>
  );
}
