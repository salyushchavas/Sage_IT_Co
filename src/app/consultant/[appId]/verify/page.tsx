"use client";

import { Suspense, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { AlertCircle, KeyRound, Loader2, RefreshCw } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import { requestConsultantOtp, verifyConsultantOtp } from "@/lib/api";
import { saveConsultantSession } from "@/lib/consultant-session";

function VerifyInner() {
  const router = useRouter();
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";
  const searchParams = useSearchParams();
  const email = searchParams?.get("email") ?? "";

  const [otp, setOtp] = useState("");
  const [verifying, setVerifying] = useState(false);
  const [resending, setResending] = useState(false);
  const [resent, setResent] = useState(false);
  const [error, setError] = useState("");

  const handleVerify = async () => {
    setError("");
    if (!/^\d{4,8}$/.test(otp.trim())) {
      setError("Enter the code you received by email.");
      return;
    }
    setVerifying(true);
    try {
      const { accessToken } = await verifyConsultantOtp(appId, otp.trim());
      saveConsultantSession(appId, accessToken, email);
      router.push(`/consultant/${encodeURIComponent(appId)}/review`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Verification failed.");
    } finally {
      setVerifying(false);
    }
  };

  const handleResend = async () => {
    if (!email) {
      setError("Open the link from your invite email to resend the code.");
      return;
    }
    setResending(true);
    setError("");
    try {
      await requestConsultantOtp(appId, email);
      setResent(true);
      setTimeout(() => setResent(false), 4000);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't resend code.");
    } finally {
      setResending(false);
    }
  };

  return (
    <SplitAuthLayout
      heroTitle={"Enter your\nverification code."}
      heroSubtitle="The six-digit code is valid for 15 minutes. After 5 failed attempts the link locks for an hour."
      heroFooter="Step 2 of 3 · Verify"
    >
      <meta name="robots" content="noindex,nofollow" />
      <div className="space-y-4">
        <div>
          <h1 className="font-serif text-2xl font-bold text-gray-900">
            Verify your code
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            We sent a code to{" "}
            <span className="font-semibold text-gray-700">
              {email || "your email"}
            </span>
            .
          </p>
        </div>

        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-0.5">
            Verification code
          </label>
          <input
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            value={otp}
            onChange={(e) => setOtp(e.target.value.replace(/\D/g, "").slice(0, 8))}
            placeholder="6-digit code"
            className="w-full px-3 py-3 text-lg text-center tracking-[0.5em] font-mono rounded-lg border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
          />
        </div>

        {error && (
          <p className="inline-flex items-center gap-1.5 text-sm text-red-600">
            <AlertCircle size={14} /> {error}
          </p>
        )}
        {resent && (
          <p className="text-sm text-emerald-700">
            New code sent. Check your email.
          </p>
        )}

        <button
          type="button"
          onClick={handleVerify}
          disabled={verifying}
          className="w-full inline-flex items-center justify-center gap-1.5 px-3 py-3 rounded-lg text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
        >
          {verifying ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <KeyRound size={14} />
          )}
          {verifying ? "Verifying…" : "Continue →"}
        </button>

        <div className="flex items-center justify-between text-xs">
          <Link
            href={`/consultant?app=${encodeURIComponent(appId)}`}
            className="font-semibold text-gray-500 hover:text-sage-navy"
          >
            ← Use a different email
          </Link>
          <button
            type="button"
            onClick={handleResend}
            disabled={resending || !email}
            className="inline-flex items-center gap-1 font-semibold text-sage-navy hover:text-sage-navy-deep disabled:opacity-50 cursor-pointer"
          >
            {resending ? (
              <Loader2 size={11} className="animate-spin" />
            ) : (
              <RefreshCw size={11} />
            )}
            Resend code
          </button>
        </div>
      </div>
    </SplitAuthLayout>
  );
}

export default function ConsultantVerifyPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen flex items-center justify-center bg-gray-50">
          <Loader2 size={28} className="animate-spin text-sage-navy" />
        </div>
      }
    >
      <VerifyInner />
    </Suspense>
  );
}
