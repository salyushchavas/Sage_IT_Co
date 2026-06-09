"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import {
  AlertCircle,
  KeyRound,
  Loader2,
  Mail,
  ShieldCheck,
} from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import {
  fetchConsultantEmailHint,
  getConsultantToken,
  requestConsultantPortalOtp,
  verifyConsultantPortalOtp,
} from "@/lib/api";

const RESEND_SECONDS = 60;

/**
 * Build V — per-agreement consultant login. The invitation email links
 * to /consultant/{appId}/login. The consultant never enters or chooses
 * an email — the backend resolves the ERM-set address from the row
 * and sends the OTP there. A masked email hint is shown for
 * reassurance.
 */
export default function ConsultantAppLoginPage() {
  const router = useRouter();
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";
  const [step, setStep] = useState<"intro" | "otp">("intro");
  const [maskedEmail, setMaskedEmail] = useState("—");
  const [otp, setOtp] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [info, setInfo] = useState("");
  const [cooldown, setCooldown] = useState(0);

  // Already-verified consultants bounce straight to the fill flow.
  useEffect(() => {
    if (!appId) return;
    if (getConsultantToken()) {
      router.replace(`/consultant/${encodeURIComponent(appId)}/fill`);
    }
  }, [appId, router]);

  // Pull the masked email hint once on mount. Non-fatal: a failure just
  // leaves the dash placeholder, the OTP flow still works.
  useEffect(() => {
    if (!appId) return;
    let cancelled = false;
    fetchConsultantEmailHint(appId)
      .then((res) => {
        if (!cancelled) setMaskedEmail(res.maskedEmail || "—");
      })
      .catch(() => {
        /* fall back to placeholder */
      });
    return () => {
      cancelled = true;
    };
  }, [appId]);

  useEffect(() => {
    if (cooldown <= 0) return;
    const t = setTimeout(() => setCooldown((s) => s - 1), 1000);
    return () => clearTimeout(t);
  }, [cooldown]);

  const otpValid = /^\d{6}$/.test(otp.trim());

  const sendCode = async () => {
    if (!appId || submitting) return;
    setSubmitting(true);
    setError("");
    setInfo("");
    try {
      const res = await requestConsultantPortalOtp(appId);
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
      const res = await requestConsultantPortalOtp(appId);
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
      await verifyConsultantPortalOtp(appId, otp.trim());
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
      heroTitle={"Sage IT\nConsultant Portal."}
      heroSubtitle="Verify the email Sage IT used to address your agreement with a 6-digit code. Your inbox is the lockbox."
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

        {step === "intro" ? (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void sendCode();
            }}
            className="space-y-4"
          >
            <h1 className="text-2xl font-serif text-sage-navy">
              Verify your email
            </h1>
            <p className="text-sm text-gray-600">
              We&apos;ll send a 6-digit verification code to the email
              Sage IT has on file for this agreement:
            </p>

            <div className="rounded-md border border-stone-200 bg-stone-50 px-3 py-2.5 inline-flex items-center gap-2 text-sm">
              <Mail size={14} className="text-sage-navy" />
              <span className="font-mono">{maskedEmail}</span>
            </div>
            <p className="text-[11px] text-gray-500">
              The full address is hidden for your privacy. If this
              doesn&apos;t look right, contact Sage IT — only they can
              change the email on file.
            </p>

            {error && (
              <p className="text-xs text-red-600 inline-flex items-center gap-1.5">
                <AlertCircle size={12} /> {error}
              </p>
            )}

            <button
              type="submit"
              disabled={submitting}
              className="w-full inline-flex items-center justify-center gap-1.5 px-4 py-2.5 rounded-md text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {submitting ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Mail size={14} />
              )}
              Send verification code
            </button>
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
              We sent a 6-digit code to{" "}
              <span className="font-mono font-semibold">{maskedEmail}</span>.
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

            {info && <p className="text-xs text-sage-navy/80">{info}</p>}
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
              Verify and open
            </button>

            <div className="flex items-center justify-end text-xs pt-2">
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
