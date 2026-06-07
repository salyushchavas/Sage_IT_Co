"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { AlertCircle, KeyRound, Loader2, Mail, ShieldCheck } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import {
  requestConsultantOtp,
  verifyConsultantOtp,
} from "@/lib/api";

const RESEND_SECONDS = 60;

/**
 * Consultant verification gate. The email invitation link lands here
 * (not on the form): the consultant proves control of the on-record
 * email via a 6-digit OTP, then receives a short-lived session token
 * that unlocks the fill/sign form. A leaked link alone can't proceed —
 * the code only reaches the consultant's inbox.
 */
export default function ConsultantVerifyPage() {
  const router = useRouter();
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";

  const [step, setStep] = useState<"email" | "otp">("email");
  const [email, setEmail] = useState("");
  const [otp, setOtp] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [info, setInfo] = useState("");
  const [cooldown, setCooldown] = useState(0);

  useEffect(() => {
    if (cooldown <= 0) return;
    const t = setTimeout(() => setCooldown((s) => s - 1), 1000);
    return () => clearTimeout(t);
  }, [cooldown]);

  const emailValid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());
  const otpValid = /^\d{6}$/.test(otp.trim());

  const sendCode = async () => {
    if (!emailValid || submitting) return;
    setSubmitting(true);
    setError("");
    setInfo("");
    try {
      const res = await requestConsultantOtp(appId, email.trim());
      setInfo(res.message);
      setStep("otp");
      setCooldown(RESEND_SECONDS);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't send the code.");
    } finally {
      setSubmitting(false);
    }
  };

  const resend = async () => {
    if (cooldown > 0 || submitting) return;
    setSubmitting(true);
    setError("");
    try {
      const res = await requestConsultantOtp(appId, email.trim());
      setInfo(res.message);
      setCooldown(RESEND_SECONDS);
      setOtp("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't resend the code.");
    } finally {
      setSubmitting(false);
    }
  };

  const verify = async () => {
    if (!otpValid || submitting) return;
    setSubmitting(true);
    setError("");
    try {
      await verifyConsultantOtp(appId, email.trim(), otp.trim());
      router.push(`/consultant/${encodeURIComponent(appId)}/fill`);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "That code is incorrect or expired — try again or resend.",
      );
      setSubmitting(false);
    }
    // On success the push unmounts this page; don't reset submitting.
  };

  return (
    <SplitAuthLayout
      heroTitle={"Verify it's\nyou."}
      heroSubtitle="For your security, confirm your email with a one-time code before opening your agreement. The code is sent only to the email on file for this agreement."
      heroFooter="Secure access · Sage IT Co"
    >
      <meta name="robots" content="noindex,nofollow" />

      <div className="space-y-5">
        <div className="inline-flex items-center justify-center w-12 h-12 rounded-2xl bg-sage-navy/10 text-sage-navy">
          <ShieldCheck size={22} />
        </div>

        {step === "email" ? (
          <>
            <div>
              <h1 className="font-serif text-2xl font-bold text-gray-900">
                Verify your email
              </h1>
              <p className="text-sm text-gray-500 mt-1">
                Enter the email this agreement was sent to. We&apos;ll send a
                6-digit verification code.
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
                onKeyDown={(e) => e.key === "Enter" && sendCode()}
                autoComplete="email"
                disabled={submitting}
                placeholder="you@example.com"
                className={inputClass}
              />
            </div>

            {error && <ErrorLine text={error} />}

            <button
              type="button"
              onClick={sendCode}
              disabled={!emailValid || submitting}
              className={primaryBtn}
            >
              {submitting ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Mail size={14} />
              )}
              {submitting ? "Sending…" : "Send code"}
            </button>
          </>
        ) : (
          <>
            <div>
              <h1 className="font-serif text-2xl font-bold text-gray-900">
                Enter your code
              </h1>
              <p className="text-sm text-gray-500 mt-1">
                {info || "If that email matches this agreement, a 6-digit code has been sent."}
              </p>
            </div>

            <div>
              <label className="block text-[11px] font-semibold text-gray-600 mb-0.5">
                6-digit code
              </label>
              <input
                type="text"
                inputMode="numeric"
                maxLength={6}
                value={otp}
                onChange={(e) => setOtp(e.target.value.replace(/\D/g, "").slice(0, 6))}
                onKeyDown={(e) => e.key === "Enter" && verify()}
                autoComplete="one-time-code"
                disabled={submitting}
                placeholder="123456"
                className={inputClass + " tracking-[0.5em] font-mono text-lg text-center"}
              />
            </div>

            {error && <ErrorLine text={error} />}

            <button
              type="button"
              onClick={verify}
              disabled={!otpValid || submitting}
              className={primaryBtn}
            >
              {submitting ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <KeyRound size={14} />
              )}
              {submitting ? "Verifying…" : "Verify & continue"}
            </button>

            <div className="flex items-center justify-between text-[11px] text-gray-500">
              <button
                type="button"
                onClick={() => {
                  setStep("email");
                  setOtp("");
                  setError("");
                }}
                className="hover:text-sage-navy cursor-pointer"
              >
                ← Use a different email
              </button>
              <button
                type="button"
                onClick={resend}
                disabled={cooldown > 0 || submitting}
                className="font-semibold text-sage-navy disabled:text-gray-400 disabled:cursor-not-allowed cursor-pointer"
              >
                {cooldown > 0 ? `Resend in ${cooldown}s` : "Resend code"}
              </button>
            </div>
          </>
        )}

        <p className="text-[11px] text-gray-400 text-center">
          The code expires in 10 minutes and can be used once.
        </p>
      </div>
    </SplitAuthLayout>
  );
}

const inputClass =
  "w-full px-3 py-2.5 text-sm rounded-lg border border-gray-200 focus:outline-none " +
  "focus:border-sage-navy focus:ring-1 focus:ring-sage-navy disabled:bg-gray-50";

const primaryBtn =
  "w-full inline-flex items-center justify-center gap-1.5 px-3 py-3 rounded-lg text-sm " +
  "font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 " +
  "disabled:cursor-not-allowed cursor-pointer";

function ErrorLine({ text }: { text: string }) {
  return (
    <p className="inline-flex items-center gap-1.5 text-sm text-red-600">
      <AlertCircle size={14} /> {text}
    </p>
  );
}
