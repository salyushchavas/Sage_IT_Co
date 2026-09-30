"use client";

import Link from "next/link";
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { motion } from "framer-motion";
import { AlertCircle, CheckCircle2, Eye, EyeOff, Loader2 } from "lucide-react";
import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import OnboardingProgressBar from "@/components/OnboardingProgressBar";
import { useAuth } from "@/lib/auth-context";
import {
  getRegistrationDetails,
  registerFromApplication,
  type RegistrationDetails,
} from "@/lib/api";

const APPLY_STEPS = ["Apply", "Review", "Register"] as const;

const INPUT_CLASS =
  "w-full px-4 py-3 bg-white border border-gray-200 rounded-lg text-gray-900 placeholder-gray-400 text-sm focus:outline-none focus:ring-2 focus:ring-sage-copper focus:border-transparent transition";
const LABEL_CLASS = "block text-sm font-semibold text-gray-700 mb-1.5";

/**
 * Roadmap steps 1 to 3, the last part: the applicant opens the one-time
 * link from the "your application is confirmed" email and chooses a
 * password. The link proved their email, so there is no code to type:
 * the account is created verified, gets its Participant ID and they land
 * on the dashboard's roadmap.
 */
function RegisterForm() {
  const token = useSearchParams().get("token") ?? "";
  const { setSession } = useAuth();

  const [details, setDetails] = useState<RegistrationDetails | null>(null);
  const [linkError, setLinkError] = useState("");
  const [loading, setLoading] = useState(true);

  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [phone, setPhone] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    if (!token) {
      setLinkError("This page opens from the registration link in your email.");
      setLoading(false);
      return;
    }
    let cancelled = false;
    getRegistrationDetails(token)
      .then((d) => { if (!cancelled) setDetails(d); })
      .catch((e) => {
        if (!cancelled) setLinkError(e instanceof Error ? e.message : "This registration link doesn't work.");
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!details || submitting || success) return;
    setError("");
    if (password.length < 8) {
      setError("Password must be at least 8 characters.");
      return;
    }
    if (password !== confirm) {
      setError("The two passwords don't match.");
      return;
    }
    if (details.needsPhone && phone.replace(/\D/g, "").length < 8) {
      setError("Enter a valid phone number, including the area code.");
      return;
    }
    setSubmitting(true);
    try {
      const auth = await registerFromApplication(token, password, details.needsPhone ? phone.trim() : undefined);
      setSuccess(true);
      setSession(auth);
      // Same landing as every participant: the dashboard's roadmap.
      setTimeout(() => { window.location.href = "/dashboard"; }, 800);
    } catch (err) {
      setError(err instanceof Error ? err.message : "We couldn't register your account. Please try again.");
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <SplitAuthLayout heroTitle={"Almost there.\nRegister your account."} heroFooter="Step 3 of 3 · Register">
        <div className="flex justify-center py-16">
          <Loader2 size={28} className="animate-spin text-sage-navy" />
        </div>
      </SplitAuthLayout>
    );
  }

  if (linkError || !details) {
    return (
      <SplitAuthLayout
        heroTitle={"This link\ndoesn't work."}
        heroSubtitle="Registration links work once and expire after 7 days."
        heroFooter="Step 3 of 3 · Register"
      >
        <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }} className="text-center">
          <AlertCircle size={28} className="text-red-600 inline-block mb-3" />
          <h2 className="text-2xl font-bold text-sage-navy mb-2">We couldn&apos;t open this link</h2>
          <p className="text-gray-600 mb-6">{linkError || "This registration link doesn't work."}</p>
          <p className="text-sm text-gray-600">
            Already registered?{" "}
            <Link href="/login" className="text-sage-copper-deep font-semibold hover:underline">Sign in</Link>
          </p>
          <p className="text-sm text-gray-600 mt-2">
            Need a new link?{" "}
            <Link href="/contact" className="text-sage-copper-deep font-semibold hover:underline">Contact us</Link>
          </p>
        </motion.div>
      </SplitAuthLayout>
    );
  }

  return (
    <SplitAuthLayout
      heroTitle={"You're confirmed.\nRegister your account."}
      heroSubtitle="Choose a password and your roadmap opens: Participant ID, acknowledgment, documents, program and agreement."
      heroFooter="Step 3 of 3 · Register"
    >
      <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
        <p className="text-xs uppercase tracking-widest font-bold text-sage-copper-deep text-center">
          Step 3 of 3 · Register
        </p>
        <h2 className="text-3xl font-bold text-sage-navy text-center mt-2 mb-2">
          Register your account
        </h2>
        <p className="text-center text-gray-600 mb-6">
          Your application is confirmed. Choose a password to finish.
        </p>

        <div className="mb-6">
          <OnboardingProgressBar currentStep={3} steps={APPLY_STEPS} />
        </div>

        <dl className="mb-5 rounded-lg border border-gray-200 bg-white px-4 py-3 text-sm space-y-1.5">
          <div className="flex justify-between gap-3">
            <dt className="text-gray-500">Name</dt>
            <dd className="font-semibold text-gray-900 text-right">{details.fullName}</dd>
          </div>
          <div className="flex justify-between gap-3">
            <dt className="text-gray-500">Email</dt>
            <dd className="font-semibold text-gray-900 text-right break-all">{details.email}</dd>
          </div>
          {details.selectedTechnology && (
            <div className="flex justify-between gap-3">
              <dt className="text-gray-500">Course</dt>
              <dd className="font-semibold text-gray-900 text-right">{details.selectedTechnology}</dd>
            </div>
          )}
        </dl>

        {error && (
          <div className="mb-4 px-4 py-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
            {error}
          </div>
        )}

        {success ? (
          <div className="flex flex-col items-center py-4">
            <div className="w-14 h-14 rounded-full bg-sage-navy flex items-center justify-center mb-3">
              <CheckCircle2 className="w-8 h-8 text-white" />
            </div>
            <p className="text-sage-navy font-semibold">You&apos;re registered!</p>
            <p className="text-gray-600 text-sm">Taking you to your roadmap…</p>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="space-y-4">
            {details.needsPhone && (
              <div>
                <label htmlFor="register-phone" className={LABEL_CLASS}>
                  Phone number <span className="text-red-500">*</span>
                </label>
                <input
                  id="register-phone"
                  type="tel"
                  autoComplete="tel"
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  className={INPUT_CLASS}
                  placeholder="+1 (555) 555-5555"
                />
              </div>
            )}
            <div>
              <label htmlFor="register-password" className={LABEL_CLASS}>
                Password <span className="text-red-500">*</span>
              </label>
              <div className="relative">
                <input
                  id="register-password"
                  type={showPassword ? "text" : "password"}
                  autoComplete="new-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className={INPUT_CLASS + " pr-10"}
                  placeholder="At least 8 characters"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((v) => !v)}
                  aria-label={showPassword ? "Hide password" : "Show password"}
                  className="absolute right-1 top-1/2 -translate-y-1/2 p-2 text-gray-400 hover:text-gray-600"
                >
                  {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </div>
            <div>
              <label htmlFor="register-confirm" className={LABEL_CLASS}>
                Confirm password <span className="text-red-500">*</span>
              </label>
              <input
                id="register-confirm"
                type={showPassword ? "text" : "password"}
                autoComplete="new-password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                className={INPUT_CLASS}
                placeholder="Type it again"
              />
            </div>

            <button
              type="submit"
              disabled={submitting}
              className="w-full py-3 rounded-lg bg-sage-navy hover:bg-sage-navy-deep text-white font-semibold text-sm transition flex items-center justify-center gap-2 disabled:opacity-60 disabled:cursor-not-allowed"
            >
              {submitting && <Loader2 size={16} className="animate-spin" />}
              {submitting ? "Registering…" : "Register and Continue"}
            </button>
          </form>
        )}

        <p className="text-sm text-gray-600 mt-4 text-center">
          Already registered?{" "}
          <Link href="/login" className="text-sage-copper-deep font-semibold hover:underline">
            Sign in
          </Link>
        </p>
      </motion.div>
    </SplitAuthLayout>
  );
}

export default function RegisterPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    }>
      <RegisterForm />
    </Suspense>
  );
}
