"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { Eye, EyeOff, Loader2 } from "lucide-react";
import { useState } from "react";
import OnboardingLayout from "@/components/layouts/OnboardingLayout";
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
  "w-full px-3.5 py-2.5 text-sm rounded-lg border border-gray-200 bg-white " +
  "text-gray-900 placeholder-gray-400 transition focus:outline-none " +
  "focus:border-[#1B2A5C] focus:ring-1 focus:ring-[#1B2A5C] " +
  "disabled:bg-gray-50 disabled:cursor-not-allowed";
const LABEL_CLASS = "block text-[13px] font-medium text-gray-700 mb-1";

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
    <OnboardingLayout currentStep={1} steps={QUICK_SIGNUP_STEPS} contentMaxWidth="xl">
      <motion.section
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35 }}
        className="bg-white rounded-2xl shadow-lg border border-gray-100 px-6 py-7 sm:px-8 sm:py-8"
      >
        <p className="text-[11px] uppercase tracking-wider font-semibold text-[#1B2A5C] text-center">
          Step 1 of 2 · Sign Up
        </p>
        <h1 className="font-serif text-2xl sm:text-3xl font-bold text-gray-900 text-center mt-1.5">
          Create your account
        </h1>
        <p className="text-gray-500 mt-1.5 mb-5 text-center text-sm">
          Just a few quick details to get started — you can complete your profile later.
        </p>

        {error && (
          <div className="mb-3 p-2.5 rounded-lg bg-red-50 border border-red-200 text-red-600 text-sm">
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
              className={INPUT_CLASS + (errors.fullName ? " !border-red-500" : "")}
              placeholder="Arjun Mehta"
            />
            {errors.fullName && <p className="text-[11px] text-red-500 mt-1">{errors.fullName.message}</p>}
          </div>

          <div>
            <label className={LABEL_CLASS}>
              Email address <span className="text-red-500">*</span>
            </label>
            <input
              type="email"
              autoComplete="email"
              {...register("email")}
              className={INPUT_CLASS + (errors.email ? " !border-red-500" : "")}
              placeholder="you@example.com"
            />
            {errors.email && <p className="text-[11px] text-red-500 mt-1">{errors.email.message}</p>}
          </div>

          <div>
            <label className={LABEL_CLASS}>
              Phone number <span className="text-red-500">*</span>
            </label>
            <input
              type="tel"
              autoComplete="tel"
              {...register("phone")}
              className={INPUT_CLASS + (errors.phone ? " !border-red-500" : "")}
              placeholder="+91 90000 00000"
            />
            {errors.phone && <p className="text-[11px] text-red-500 mt-1">{errors.phone.message}</p>}
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
                className={INPUT_CLASS + " pr-10" + (errors.password ? " !border-red-500" : "")}
                placeholder="At least 8 characters"
              />
              <button
                type="button"
                onClick={() => setShowPassword((v) => !v)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                aria-label={showPassword ? "Hide password" : "Show password"}
              >
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
            {errors.password && <p className="text-[11px] text-red-500 mt-1">{errors.password.message}</p>}
          </div>

          <button
            type="submit"
            disabled={isSubmitting}
            className="w-full py-2.5 rounded-lg bg-[#1B2A5C] text-white text-sm font-semibold hover:bg-[#2D4480] focus:outline-none focus:ring-2 focus:ring-[#C87D5C] focus:ring-offset-2 transition-colors disabled:opacity-60 disabled:cursor-not-allowed inline-flex items-center justify-center gap-2 cursor-pointer"
          >
            {isSubmitting && <Loader2 size={16} className="animate-spin" />}
            {isSubmitting ? "Creating account…" : "Create Account"}
          </button>
        </form>

        <p className="text-[11px] text-center text-gray-400 mt-4">
          By signing up, you agree to start your profile setup in the dashboard.
        </p>
        <p className="text-xs text-center text-gray-500 mt-2">
          Already have an account?{" "}
          <Link href="/login" className="text-[#1B2A5C] font-semibold hover:underline">Sign in</Link>
        </p>
      </motion.section>
    </OnboardingLayout>
  );
}
