"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { AlertCircle, Loader2, LogIn } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import { agreementErmLogin, getAgreementErmToken } from "@/lib/api";

export default function AgreementErmLoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    // Already signed in -> go straight to the dashboard.
    if (getAgreementErmToken()) router.replace("/agreement-erm");
  }, [router]);

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      await agreementErmLogin(email.trim(), password);
      router.replace("/agreement-erm");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Sign-in failed.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <SplitAuthLayout
      heroTitle={"Sage\nAgreements."}
      heroSubtitle="Operator console for sending, tracking, and signing consultant engagement agreements. Hidden internal surface — separate from the regular ERM dashboard."
      heroFooter="Hidden internal console · Sage IT Co"
    >
      <meta name="robots" content="noindex,nofollow" />
      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <h1 className="font-serif text-2xl font-bold text-gray-900">Sign in</h1>
          <p className="text-sm text-gray-500 mt-1">
            Use the credentials provisioned for the Agreement Console.
          </p>
        </div>

        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-0.5">
            Email
          </label>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
            disabled={submitting}
            placeholder="ermuser@sageitco.com"
            className="w-full px-3 py-2.5 text-sm rounded-lg border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
          />
        </div>

        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-0.5">
            Password
          </label>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            disabled={submitting}
            className="w-full px-3 py-2.5 text-sm rounded-lg border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
          />
        </div>

        {error && (
          <p className="inline-flex items-center gap-1.5 text-sm text-red-600">
            <AlertCircle size={14} /> {error}
          </p>
        )}

        <button
          type="submit"
          disabled={submitting}
          className="w-full inline-flex items-center justify-center gap-1.5 px-3 py-3 rounded-lg text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
        >
          {submitting ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <LogIn size={14} />
          )}
          {submitting ? "Signing in…" : "Sign in"}
        </button>

        <p className="text-[11px] text-gray-400 text-center">
          Session lasts 8 hours and is cleared when you close the tab.
        </p>
      </form>
    </SplitAuthLayout>
  );
}
