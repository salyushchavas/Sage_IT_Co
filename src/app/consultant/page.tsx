"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AlertCircle,
  KeyRound,
  Loader2,
  Mail,
  ShieldCheck,
} from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import {
  getConsultantToken,
  requestConsultantPortalOtp,
  verifyConsultantPortalOtp,
} from "@/lib/api";

const RESEND_SECONDS = 60;

/**
 * Consultant portal login. The email invite lands here (no per-app
 * URL). The consultant proves control of their email with a 6-digit
 * OTP and gets an email-scoped session token that unlocks the
 * dashboard listing every agreement addressed to them, across every
 * ERM.
 */
export default function ConsultantPortalLoginPage() {
  const router = useRouter();
  const [step, setStep] = useState<"email" | "otp">("email");
  const [email, setEmail] = useState("");
  const [otp, setOtp] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [info, setInfo] = useState("");
  const [cooldown, setCooldown] = useState(0);

  // Already-verified consultants land straight on the dashboard.
  useEffect(() => {
    if (getConsultantToken()) {
      router.replace("/consultant/dashboard");
    }
  }, [router]);

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
      const res = await requestConsultantPortalOtp(email.trim());
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
      const res = await requestConsultantPortalOtp(email.trim());
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
      await verifyConsultantPortalOtp(email.trim(), otp.trim());
      router.push("/consultant/dashboard");
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
      heroTitle={"Sage IT\nConsultant Portal."}
      heroSubtitle="Sign in with the email on your agreement. We'll send a 6-digit code to confirm it's you, then you'll see every agreement waiting for your signature."
      heroFooter="Secure access · Sage IT Co"
    >
      <meta name="robots" content="noindex,nofollow" />
      <div className="max-w-md mx-auto py-12 px-6">
        <div className="mb-6 inline-flex items-center gap-2 text-sage-copper">
          <ShieldCheck size={18} />
          <span className="text-xs font-bold uppercase tracking-wider">
            Portal sign-in
          </span>
        </div>

        {step === "email" ? (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void sendCode();
            }}
            className="space-y-4"
          >
            <h1 className="text-2xl font-serif text-sage-navy">
              Welcome back
            </h1>
            <p className="text-sm text-gray-600">
              Enter the email Sage IT used to address your agreement.
              We'll text a verification code there.
            </p>

            <label className="block">
              <span className="block text-[11px] font-bold uppercase tracking-wider text-gray-600 mb-1.5">
                Email
              </span>
              <div className="relative">
                <Mail
                  size={16}
                  className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400"
                />
                <input
                  type="email"
                  autoComplete="email"
                  inputMode="email"
                  placeholder="you@example.com"
                  className="w-full pl-9 pr-3 py-2.5 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  disabled={submitting}
                  required
                />
              </div>
            </label>

            {error && (
              <p className="text-xs text-red-600 inline-flex items-center gap-1.5">
                <AlertCircle size={12} /> {error}
              </p>
            )}

            <button
              type="submit"
              disabled={!emailValid || submitting}
              className="w-full inline-flex items-center justify-center gap-1.5 px-4 py-2.5 rounded-md text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {submitting ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Mail size={14} />
              )}
              Send verification code
            </button>

            <p className="text-[11px] text-gray-500 text-center pt-2">
              We never reveal whether an email is on file. If your email
              isn't on an agreement, no code is sent.
            </p>
          </form>
        ) : (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void verify();
            }}
            className="space-y-4"
          >
            <h1 className="text-2xl font-serif text-sage-navy">
              Enter the code
            </h1>
            <p className="text-sm text-gray-600">
              If the email is on file, we sent a 6-digit code to{" "}
              <span className="font-semibold">{email.trim()}</span>.
              The code expires in 10 minutes.
            </p>

            <label className="block">
              <span className="block text-[11px] font-bold uppercase tracking-wider text-gray-600 mb-1.5">
                Verification code
              </span>
              <div className="relative">
                <KeyRound
                  size={16}
                  className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400"
                />
                <input
                  type="text"
                  inputMode="numeric"
                  pattern="\d{6}"
                  maxLength={6}
                  autoComplete="one-time-code"
                  placeholder="123456"
                  className="w-full pl-9 pr-3 py-2.5 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy font-mono tracking-widest"
                  value={otp}
                  onChange={(e) =>
                    setOtp(e.target.value.replace(/\D/g, "").slice(0, 6))
                  }
                  disabled={submitting}
                  autoFocus
                  required
                />
              </div>
            </label>

            {info && (
              <p className="text-xs text-sage-navy/80">{info}</p>
            )}
            {error && (
              <p className="text-xs text-red-600 inline-flex items-center gap-1.5">
                <AlertCircle size={12} /> {error}
              </p>
            )}

            <button
              type="submit"
              disabled={!otpValid || submitting}
              className="w-full inline-flex items-center justify-center gap-1.5 px-4 py-2.5 rounded-md text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {submitting ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <ShieldCheck size={14} />
              )}
              Verify and open portal
            </button>

            <div className="flex items-center justify-between text-xs pt-2">
              <button
                type="button"
                onClick={() => {
                  setStep("email");
                  setOtp("");
                  setError("");
                  setInfo("");
                  setCooldown(0);
                }}
                disabled={submitting}
                className="text-gray-500 hover:text-sage-navy disabled:opacity-50"
              >
                Use a different email
              </button>
              <button
                type="button"
                onClick={() => void resend()}
                disabled={cooldown > 0 || submitting}
                className="text-sage-navy hover:text-sage-navy-deep font-semibold disabled:opacity-50"
              >
                {cooldown > 0 ? `Resend in ${cooldown}s` : "Resend code"}
              </button>
            </div>
          </form>
        )}
      </div>
    </SplitAuthLayout>
  );
}
