"use client";

import Link from "next/link";
import { motion, AnimatePresence } from "framer-motion";
import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Loader2, MailCheck, AlertCircle, CheckCircle2 } from "lucide-react";
import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import { useAuth } from "@/lib/auth-context";
import { resendVerificationCode, verifyCode } from "@/lib/api";

/**
 * /verify-email?email=… — OTP gate for new signups.
 *
 * Split-screen layout (Sage navy hero + form panel). The user
 * lands here from /enroll with their email pre-populated; on
 * success the backend hands back an AuthResponse and we promote
 * the session and route to /dashboard.
 */

const RESEND_COOLDOWN_SECONDS = 150;
const CODE_LENGTH = 6;

function maskEmail(email: string | null | undefined): string {
  if (!email) return "your email";
  const at = email.indexOf("@");
  if (at <= 0) return email;
  const local = email.slice(0, at);
  const domain = email.slice(at);
  if (local.length <= 2) return `${local[0]}***${domain}`;
  const head = local.slice(0, Math.min(4, Math.max(1, local.length - 2)));
  const tail = local.slice(-1);
  return `${head}***${tail}${domain}`;
}

function VerifyEmailInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { setSession } = useAuth();
  const email = searchParams.get("email") ?? "";

  const [digits, setDigits] = useState<string[]>(() => Array(CODE_LENGTH).fill(""));
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [success, setSuccess] = useState(false);
  const [shake, setShake] = useState(0);
  const [resendCooldown, setResendCooldown] = useState(RESEND_COOLDOWN_SECONDS);
  const [resending, setResending] = useState(false);
  const [resendInfo, setResendInfo] = useState("");

  const inputRefs = useRef<Array<HTMLInputElement | null>>([]);

  const masked = useMemo(() => maskEmail(email), [email]);
  const code = digits.join("");
  const ready = code.length === CODE_LENGTH && /^\d{6}$/.test(code);

  useEffect(() => { inputRefs.current[0]?.focus(); }, []);

  useEffect(() => {
    if (resendCooldown <= 0) return;
    const t = setInterval(() => setResendCooldown((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(t);
  }, [resendCooldown]);

  // ── Input handlers ──────────────────────────────────────────────

  const setDigitAt = (i: number, value: string) => {
    setDigits((prev) => { const next = [...prev]; next[i] = value; return next; });
  };

  const handleChange = (i: number, raw: string) => {
    const cleaned = raw.replace(/\D/g, "");
    if (!cleaned) { setDigitAt(i, ""); return; }
    if (cleaned.length === 1) {
      setDigitAt(i, cleaned);
      if (i < CODE_LENGTH - 1) inputRefs.current[i + 1]?.focus();
    } else {
      const chars = cleaned.slice(0, CODE_LENGTH - i).split("");
      setDigits((prev) => {
        const next = [...prev];
        chars.forEach((c, k) => { next[i + k] = c; });
        return next;
      });
      const target = Math.min(CODE_LENGTH - 1, i + chars.length);
      inputRefs.current[target]?.focus();
    }
  };

  const handleKeyDown = (i: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Backspace") {
      if (digits[i]) setDigitAt(i, "");
      else if (i > 0) {
        e.preventDefault();
        setDigitAt(i - 1, "");
        inputRefs.current[i - 1]?.focus();
      }
    } else if (e.key === "ArrowLeft" && i > 0) {
      e.preventDefault();
      inputRefs.current[i - 1]?.focus();
    } else if (e.key === "ArrowRight" && i < CODE_LENGTH - 1) {
      e.preventDefault();
      inputRefs.current[i + 1]?.focus();
    } else if (e.key === "Enter" && ready) {
      e.preventDefault();
      void handleSubmit();
    }
  };

  const handlePaste = (i: number, e: React.ClipboardEvent<HTMLInputElement>) => {
    const pasted = e.clipboardData.getData("text").replace(/\D/g, "");
    if (!pasted) return;
    e.preventDefault();
    const chars = pasted.slice(0, CODE_LENGTH - i).split("");
    setDigits((prev) => {
      const next = [...prev];
      chars.forEach((c, k) => { next[i + k] = c; });
      return next;
    });
    const target = Math.min(CODE_LENGTH - 1, i + chars.length);
    inputRefs.current[target]?.focus();
  };

  // ── Submit / resend ─────────────────────────────────────────────

  const handleSubmit = async () => {
    if (!email || !ready || submitting) return;
    setSubmitting(true);
    setError("");
    try {
      const auth = await verifyCode(email, code);
      setSuccess(true);
      setSession(auth);
      // Phase 1C: verify-code mints the participant ID AND walks
      // the workflow straight to DASHBOARD_ENABLED. The participant
      // lands on /dashboard immediately; the remaining lifecycle
      // steps live inside the "Complete Your Profile" tab. No more
      // bouncing through /participant-id, /acknowledgment, etc.
      setTimeout(() => { window.location.href = "/dashboard"; }, 800);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Verification failed");
      setShake((n) => n + 1);
      setDigits(Array(CODE_LENGTH).fill(""));
      inputRefs.current[0]?.focus();
    } finally {
      setSubmitting(false);
    }
  };

  const handleResend = async () => {
    if (!email || resendCooldown > 0 || resending) return;
    setResending(true);
    setResendInfo("");
    setError("");
    try {
      await resendVerificationCode(email);
      setResendInfo("New code sent. Check your inbox.");
      setResendCooldown(RESEND_COOLDOWN_SECONDS);
      setDigits(Array(CODE_LENGTH).fill(""));
      inputRefs.current[0]?.focus();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't send a new code");
    } finally {
      setResending(false);
    }
  };

  useEffect(() => {
    if (!email) router.replace("/enroll");
  }, [email, router]);

  // ── Render ──────────────────────────────────────────────────────

  return (
    <SplitAuthLayout
      heroTitle={"Almost in.\nCheck your inbox."}
      heroSubtitle="We just emailed you a 6-digit code. Type it in to verify your address and unlock the dashboard."
      heroFooter="Step 2 of 2 · Verify Email"
    >
      <motion.section
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35 }}
        className="text-center"
      >
        <h2 className="text-3xl font-bold text-sage-navy inline-flex items-center justify-center gap-2 w-full">
          <MailCheck className="w-7 h-7" />
          Verify your email
        </h2>
        <p className="text-sm text-gray-600 mt-2">
          We sent a 6-digit code to
        </p>
        <p className="text-sage-navy font-semibold font-mono break-all mb-7">
          {masked}
        </p>

        <motion.div
          key={shake}
          animate={shake > 0 ? { x: [0, -10, 10, -8, 8, -4, 4, 0] } : { x: 0 }}
          transition={{ duration: 0.5 }}
          className="flex justify-center gap-2 sm:gap-3"
        >
          {digits.map((d, i) => (
            <input
              key={i}
              ref={(el) => { inputRefs.current[i] = el; }}
              type="text"
              inputMode="numeric"
              autoComplete={i === 0 ? "one-time-code" : "off"}
              maxLength={CODE_LENGTH}
              value={d}
              onChange={(e) => handleChange(i, e.target.value)}
              onKeyDown={(e) => handleKeyDown(i, e)}
              onPaste={(e) => handlePaste(i, e)}
              disabled={submitting || success}
              aria-label={`Digit ${i + 1}`}
              className={
                "w-11 h-14 sm:w-12 text-center text-2xl font-bold rounded-lg border-2 " +
                "transition-colors focus:outline-none disabled:opacity-60 " +
                (d
                  ? "border-sage-navy bg-sage-navy/5 text-sage-navy"
                  : "border-gray-200 bg-white text-gray-700 focus:border-sage-navy")
              }
            />
          ))}
        </motion.div>

        <AnimatePresence>
          {error && (
            <motion.div
              key={shake || "error"}
              initial={{ opacity: 0, y: -4 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              className="mt-4 inline-flex items-center gap-1.5 text-sm text-red-600"
            >
              <AlertCircle size={14} /> {error}
            </motion.div>
          )}
        </AnimatePresence>

        {success && (
          <motion.div
            initial={{ scale: 0.9, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            className="mt-6 flex flex-col items-center"
          >
            <div className="w-14 h-14 rounded-full bg-sage-navy flex items-center justify-center mb-3">
              <CheckCircle2 className="w-8 h-8 text-white" />
            </div>
            <p className="text-sage-navy font-semibold">Email verified!</p>
            <p className="text-gray-600 text-sm">Taking you to your dashboard…</p>
          </motion.div>
        )}

        <button
          type="button"
          onClick={handleSubmit}
          disabled={!ready || submitting || success}
          className="mt-6 w-full inline-flex items-center justify-center gap-2 bg-sage-navy hover:bg-sage-navy-deep text-white font-semibold py-3 rounded-lg transition disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {submitting && <Loader2 className="w-4 h-4 animate-spin" />}
          {submitting ? "Verifying…" : success ? "Verified" : "Verify and Continue"}
        </button>

        <div className="mt-5 text-sm border-t border-gray-100 pt-5">
          <p className="text-gray-600">Didn&apos;t receive the code?</p>
          {resendCooldown > 0 ? (
            <p className="mt-1 text-gray-400">
              Resend code in{" "}
              <span className="font-mono">
                {Math.floor(resendCooldown / 60)}:
                {String(resendCooldown % 60).padStart(2, "0")}
              </span>
            </p>
          ) : (
            <button
              type="button"
              onClick={handleResend}
              disabled={resending}
              className="mt-1 text-sage-copper hover:text-sage-copper-deep font-semibold disabled:text-gray-400 disabled:cursor-not-allowed"
            >
              {resending ? "Sending…" : "Resend code"}
            </button>
          )}
          {resendInfo && <p className="mt-1 text-emerald-700 text-xs">{resendInfo}</p>}
        </div>

        <p className="mt-6 text-xs text-gray-500">
          Wrong email?{" "}
          <Link href="/enroll" className="text-sage-copper font-semibold hover:underline">
            Go back to enrollment
          </Link>
        </p>
      </motion.section>
    </SplitAuthLayout>
  );
}

export default function VerifyEmailPage() {
  return (
    <Suspense fallback={<div className="min-h-screen flex items-center justify-center bg-gray-50"><Loader2 className="animate-spin text-sage-navy" /></div>}>
      <VerifyEmailInner />
    </Suspense>
  );
}
