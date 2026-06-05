"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { AlertCircle, CheckCircle2, Loader2, Mail } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import { requestConsultantOtp } from "@/lib/api";

function ConsultantEntryInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const appFromUrl = searchParams?.get("app") ?? "";

  const [appId, setAppId] = useState(appFromUrl);
  const [email, setEmail] = useState("");
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (appFromUrl) setAppId(appFromUrl);
  }, [appFromUrl]);

  const handleSend = async () => {
    setError("");
    if (!appId.trim()) {
      setError("Application ID is required.");
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      setError("Enter a valid email address.");
      return;
    }
    setSending(true);
    try {
      await requestConsultantOtp(appId.trim(), email.trim());
      setSent(true);
      setTimeout(() => {
        router.push(
          `/consultant/${encodeURIComponent(appId.trim())}/verify?email=${encodeURIComponent(email.trim())}`,
        );
      }, 1200);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't send code.");
    } finally {
      setSending(false);
    }
  };

  return (
    <SplitAuthLayout
      heroTitle={"Sign your\nconsulting agreement."}
      heroSubtitle="Enter the application ID from your invite and the email it was sent to. We'll text you a one-time code to confirm you're the right person."
      heroFooter="Hidden internal flow · Sage IT Co"
    >
      <meta name="robots" content="noindex,nofollow" />
      {sent ? (
        <div className="space-y-3 text-center">
          <div className="mx-auto inline-flex items-center justify-center w-12 h-12 rounded-2xl bg-emerald-100 text-emerald-700">
            <CheckCircle2 size={22} />
          </div>
          <h1 className="text-xl font-bold text-gray-900">Check your email</h1>
          <p className="text-sm text-gray-600">
            If that email is on file we just sent a six-digit code. Redirecting
            you to the verification screen…
          </p>
          <Loader2 size={16} className="animate-spin text-sage-navy inline" />
        </div>
      ) : (
        <div className="space-y-4">
          <div>
            <h1 className="font-serif text-2xl font-bold text-gray-900">
              Consultant sign-in
            </h1>
            <p className="text-sm text-gray-500 mt-1">
              Step 1 of 3 · verify your identity to view the agreement.
            </p>
          </div>

          <div>
            <label className="block text-[11px] font-semibold text-gray-600 mb-0.5">
              Application ID
            </label>
            <input
              type="text"
              value={appId}
              onChange={(e) => setAppId(e.target.value)}
              placeholder="paste the ID from your invite"
              className="w-full px-3 py-2.5 text-sm font-mono rounded-lg border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </div>

          <div>
            <label className="block text-[11px] font-semibold text-gray-600 mb-0.5">
              Email
            </label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              autoComplete="email"
              className="w-full px-3 py-2.5 text-sm rounded-lg border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </div>

          {error && (
            <p className="inline-flex items-center gap-1.5 text-sm text-red-600">
              <AlertCircle size={14} /> {error}
            </p>
          )}

          <button
            type="button"
            onClick={handleSend}
            disabled={sending}
            className="w-full inline-flex items-center justify-center gap-1.5 px-3 py-3 rounded-lg text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {sending ? (
              <Loader2 size={14} className="animate-spin" />
            ) : (
              <Mail size={14} />
            )}
            {sending ? "Sending…" : "Email me a verification code"}
          </button>
          <p className="text-[11px] text-gray-500 text-center">
            For security we always return a generic confirmation message,
            even if the application or email doesn&apos;t match.
          </p>
        </div>
      )}
    </SplitAuthLayout>
  );
}

export default function ConsultantEntryPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen flex items-center justify-center bg-gray-50">
          <Loader2 size={28} className="animate-spin text-sage-navy" />
        </div>
      }
    >
      <ConsultantEntryInner />
    </Suspense>
  );
}
