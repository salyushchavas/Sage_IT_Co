"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { Eye, EyeOff, Loader2 } from "lucide-react";
import { useState } from "react";
import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import OnboardingProgressBar from "@/components/OnboardingProgressBar";
import { enrollParticipant } from "@/lib/api";

const QUICK_SIGNUP_STEPS = ["Sign Up", "Verify"] as const;

/**
 * Phase 1C — quick signup. Only four fields here; location,
 * availability, technology preference, and target experience level
 * moved to the dashboard "Complete Your Profile" → "About You"
 * step so the public form is short and friction-free. The user
 * lands on /dashboard immediately after verifying email and can
 * fill the rest in at their own pace.
 */
const enrollSchema = z.object({
  fullName: z
    .string()
    .min(2, "Full legal name is required")
    .refine((v) => v.trim().split(/\s+/).length >= 2, {
      message: "Enter your full legal name (first and last)",
    }),
  email: z.string().email("Please enter a valid email address"),
  phone: z
    .string()
    .min(7, "Phone number is required")
    .regex(/^[+\d\s()-]{7,20}$/, "Use only digits, spaces, +, -, or parentheses"),
  password: z.string().min(8, "Password must be at least 8 characters"),
});

type EnrollValues = z.infer<typeof enrollSchema>;

const INPUT_CLASS =
  "w-full px-4 py-3 bg-white border rounded-lg text-gray-900 placeholder-gray-400 text-sm focus:outline-none focus:ring-2 focus:ring-sage-copper focus:border-transparent transition";
const LABEL_CLASS = "block text-sm font-semibold text-gray-700 mb-1.5";

export default function EnrollPage() {
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const router = useRouter();

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<EnrollValues>({
    resolver: zodResolver(enrollSchema),
  });

  const onSubmit = async (data: EnrollValues) => {
    setError("");
    try {
      await enrollParticipant({
        fullName: data.fullName.trim(),
        email: data.email.trim(),
        phone: data.phone.trim(),
        password: data.password,
      });
      router.push(`/verify-email?email=${encodeURIComponent(data.email.trim())}`);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Enrollment failed. Please try again.");
    }
  };

  return (
    <SplitAuthLayout
      heroTitle={"Start your\ncareer journey."}
      heroSubtitle="Just a few quick details to get started. Complete your full profile in the dashboard at your own pace."
      heroFooter="Step 1 of 2 · Sign Up"
    >
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
      >
        <p className="text-xs uppercase tracking-widest font-bold text-sage-copper text-center">
          Step 1 of 2 · Sign Up
        </p>
        <h2 className="text-3xl font-bold text-sage-navy text-center mt-2 mb-2">
          Create your account
        </h2>
        <p className="text-center text-gray-600 mb-6">
          Just a few quick details to get started.
        </p>

        <div className="mb-6">
          <OnboardingProgressBar currentStep={1} steps={QUICK_SIGNUP_STEPS} />
        </div>

        {error && (
          <div className="mb-4 px-4 py-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div>
            <label className={LABEL_CLASS}>
              Full legal name <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              autoComplete="name"
              {...register("fullName")}
              className={INPUT_CLASS + (errors.fullName ? " !border-red-400" : " border-gray-200")}
              placeholder="Arjun Mehta"
            />
            {errors.fullName && <p className="text-xs text-red-500 mt-1">{errors.fullName.message}</p>}
          </div>

          <div>
            <label className={LABEL_CLASS}>
              Email address <span className="text-red-500">*</span>
            </label>
            <input
              type="email"
              autoComplete="email"
              {...register("email")}
              className={INPUT_CLASS + (errors.email ? " !border-red-400" : " border-gray-200")}
              placeholder="you@example.com"
            />
            {errors.email && <p className="text-xs text-red-500 mt-1">{errors.email.message}</p>}
          </div>

          <div>
            <label className={LABEL_CLASS}>
              Phone number <span className="text-red-500">*</span>
            </label>
            <input
              type="tel"
              autoComplete="tel"
              {...register("phone")}
              className={INPUT_CLASS + (errors.phone ? " !border-red-400" : " border-gray-200")}
              placeholder="+1 (555) 555-5555"
            />
            {errors.phone && <p className="text-xs text-red-500 mt-1">{errors.phone.message}</p>}
          </div>

          <div>
            <label className={LABEL_CLASS}>
              Password <span className="text-red-500">*</span>
            </label>
            <div className="relative">
              <input
                type={showPassword ? "text" : "password"}
                autoComplete="new-password"
                {...register("password")}
                className={INPUT_CLASS + " pr-10" + (errors.password ? " !border-red-400" : " border-gray-200")}
                placeholder="At least 8 characters"
              />
              <button
                type="button"
                onClick={() => setShowPassword((v) => !v)}
                aria-label={showPassword ? "Hide password" : "Show password"}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
              >
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
            {errors.password && <p className="text-xs text-red-500 mt-1">{errors.password.message}</p>}
          </div>

          <button
            type="submit"
            disabled={isSubmitting}
            className="w-full py-3 rounded-lg bg-sage-navy hover:bg-sage-navy-deep text-white font-semibold text-sm transition flex items-center justify-center gap-2 disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {isSubmitting && <Loader2 size={16} className="animate-spin" />}
            {isSubmitting ? "Creating account…" : "Create Account"}
          </button>
        </form>

        <p className="text-xs text-gray-500 mt-4 text-center">
          By signing up, you agree to start your profile setup in the dashboard.
        </p>
        <p className="text-sm text-gray-600 mt-3 text-center">
          Already have an account?{" "}
          <Link href="/login" className="text-sage-copper font-semibold hover:underline">
            Sign in
          </Link>
        </p>
      </motion.div>
    </SplitAuthLayout>
  );
}
