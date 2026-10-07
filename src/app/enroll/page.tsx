"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2, Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import OnboardingProgressBar from "@/components/OnboardingProgressBar";
import { applyToProgram, dashboardRouteForRole } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { COURSE_OPTIONS } from "@/lib/course-tracks";

const APPLY_STEPS = ["Apply", "Review", "Register"] as const;

/**
 * Roadmap step 1: the visitor applies with their basic details and the
 * course they want. No account is created here. An ERM confirms the
 * application, which emails a link to register (/register); registering
 * opens the roadmap.
 */
const applySchema = z.object({
  fullName: z
    .string()
    .min(2, "Full legal name is required")
    .refine((v) => v.trim().split(/\s+/).length >= 2, {
      message: "Enter your full legal name (first and last)",
    }),
  email: z.string().email("Please enter a valid email address"),
  phone: z
    .string()
    .trim()
    .min(1, "Phone number is required")
    .regex(/^[+\d\s().-]+$/, "Use only digits, spaces, +, -, periods or parentheses")
    .max(20, "Phone number is too long (at most 20 characters)")
    .refine((v) => v.replace(/\D/g, "").length >= 8, {
      message: "Enter a valid phone number, including the area code",
    }),
  selectedTechnology: z.string().min(1, "Choose the course you're applying for"),
});

type ApplyValues = z.infer<typeof applySchema>;

const INPUT_CLASS =
  "w-full px-4 py-3 bg-white border rounded-lg text-gray-900 placeholder-gray-400 text-sm focus:outline-none focus:ring-2 focus:ring-sage-copper focus:border-transparent transition";
const LABEL_CLASS = "block text-sm font-semibold text-gray-700 mb-1.5";

export default function EnrollPage() {
  const router = useRouter();
  const { user, isAuthenticated, isLoading } = useAuth();
  const [error, setError] = useState("");
  const [sentTo, setSentTo] = useState<string | null>(null);

  // Already signed in: the application is behind them, go to their home page.
  useEffect(() => {
    if (!isLoading && isAuthenticated && user) router.replace(dashboardRouteForRole(user.role));
  }, [isLoading, isAuthenticated, user, router]);

  const {
    register,
    handleSubmit,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<ApplyValues>({
    resolver: zodResolver(applySchema),
    defaultValues: { selectedTechnology: "" },
  });

  // "Apply" on a course card arrives as /enroll?course=<name>.
  useEffect(() => {
    const course = new URLSearchParams(window.location.search).get("course");
    if (course && COURSE_OPTIONS.includes(course)) setValue("selectedTechnology", course);
  }, [setValue]);

  const onSubmit = async (data: ApplyValues) => {
    setError("");
    try {
      const res = await applyToProgram({
        fullName: data.fullName.trim(),
        email: data.email.trim(),
        phone: data.phone.trim(),
        selectedTechnology: data.selectedTechnology,
      });
      setSentTo(res.email);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "We couldn't send your application. Please try again.");
    }
  };

  return (
    <SplitAuthLayout
      heroTitle={"Start your\ncareer journey."}
      heroSubtitle="Tell us who you are and which course you want. Our team confirms your application and emails you a link to register."
      heroFooter={sentTo ? "Step 2 of 3 · Waiting for confirmation" : "Step 1 of 3 · Apply"}
    >
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
      >
        {sentTo ? (
          <>
            <p className="text-xs uppercase tracking-widest font-bold text-sage-copper-deep text-center">
              Step 2 of 3 · Waiting for confirmation
            </p>
            <div className="flex justify-center mt-4 mb-3">
              <span className="inline-flex items-center justify-center w-14 h-14 rounded-full bg-emerald-100 text-emerald-700">
                <CheckCircle2 size={28} />
              </span>
            </div>
            <h2 className="text-3xl font-bold text-sage-navy text-center mb-2">
              Application received
            </h2>
            <p className="text-center text-gray-600 mb-6">
              Thank you. We sent a confirmation to <strong className="text-gray-900">{sentTo}</strong>.
            </p>

            <div className="mb-6">
              <OnboardingProgressBar currentStep={2} steps={APPLY_STEPS} />
            </div>

            <ol className="space-y-3 text-sm text-gray-700">
              <li className="flex gap-3">
                <span className="shrink-0 w-6 h-6 rounded-full bg-sage-navy text-white text-xs font-bold flex items-center justify-center">1</span>
                Our team reviews your application.
              </li>
              <li className="flex gap-3">
                <span className="shrink-0 w-6 h-6 rounded-full bg-sage-navy text-white text-xs font-bold flex items-center justify-center">2</span>
                Once it&apos;s confirmed, we email you a link to register your account.
              </li>
              <li className="flex gap-3">
                <span className="shrink-0 w-6 h-6 rounded-full bg-sage-navy text-white text-xs font-bold flex items-center justify-center">3</span>
                After you register, your roadmap opens and guides you step by step.
              </li>
            </ol>

            <p className="text-sm text-gray-600 mt-6 text-center">
              <Link href="/" className="text-sage-copper-deep font-semibold hover:underline">
                Back to the home page
              </Link>
            </p>
          </>
        ) : (
          <>
            <p className="text-xs uppercase tracking-widest font-bold text-sage-copper-deep text-center">
              Step 1 of 3 · Apply
            </p>
            <h2 className="text-3xl font-bold text-sage-navy text-center mt-2 mb-2">
              Apply for a course
            </h2>
            <p className="text-center text-gray-600 mb-6">
              Just a few quick details. No password needed yet.
            </p>

            <div className="mb-6">
              <OnboardingProgressBar currentStep={1} steps={APPLY_STEPS} />
            </div>

            {error && (
              <div className="mb-4 px-4 py-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
                {error}
              </div>
            )}

            <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
              <div>
                <label htmlFor="apply-name" className={LABEL_CLASS}>
                  Full legal name <span className="text-red-500">*</span>
                </label>
                <input
                  id="apply-name"
                  type="text"
                  autoComplete="name"
                  {...register("fullName")}
                  className={INPUT_CLASS + (errors.fullName ? " !border-red-400" : " border-gray-200")}
                  placeholder="Jordan Rivera"
                />
                {errors.fullName && <p className="text-xs text-red-500 mt-1">{errors.fullName.message}</p>}
              </div>

              <div>
                <label htmlFor="apply-email" className={LABEL_CLASS}>
                  Email address <span className="text-red-500">*</span>
                </label>
                <input
                  id="apply-email"
                  type="email"
                  autoComplete="email"
                  {...register("email")}
                  className={INPUT_CLASS + (errors.email ? " !border-red-400" : " border-gray-200")}
                  placeholder="you@example.com"
                />
                {errors.email && <p className="text-xs text-red-500 mt-1">{errors.email.message}</p>}
              </div>

              <div>
                <label htmlFor="apply-phone" className={LABEL_CLASS}>
                  Phone number <span className="text-red-500">*</span>
                </label>
                <input
                  id="apply-phone"
                  type="tel"
                  autoComplete="tel"
                  {...register("phone")}
                  className={INPUT_CLASS + (errors.phone ? " !border-red-400" : " border-gray-200")}
                  placeholder="+1 (555) 555-5555"
                />
                {errors.phone && <p className="text-xs text-red-500 mt-1">{errors.phone.message}</p>}
              </div>

              <div>
                <label htmlFor="apply-course" className={LABEL_CLASS}>
                  Course <span className="text-red-500">*</span>
                </label>
                <select
                  id="apply-course"
                  {...register("selectedTechnology")}
                  className={INPUT_CLASS + (errors.selectedTechnology ? " !border-red-400" : " border-gray-200")}
                >
                  <option value="">Select a course</option>
                  {COURSE_OPTIONS.map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
                {errors.selectedTechnology && (
                  <p className="text-xs text-red-500 mt-1">{errors.selectedTechnology.message}</p>
                )}
              </div>

              <button
                type="submit"
                disabled={isSubmitting}
                className="w-full py-3 rounded-lg bg-sage-navy hover:bg-sage-navy-deep text-white font-semibold text-sm transition flex items-center justify-center gap-2 disabled:opacity-60 disabled:cursor-not-allowed"
              >
                {isSubmitting && <Loader2 size={16} className="animate-spin" />}
                {isSubmitting ? "Sending…" : "Send Application"}
              </button>
            </form>

            <p className="text-xs text-gray-500 mt-4 text-center">
              By applying, you agree that we may contact you about your application by email or phone.
            </p>
            <p className="text-sm text-gray-600 mt-3 text-center">
              Already have an account?{" "}
              <Link href="/login" className="text-sage-copper-deep font-semibold hover:underline">
                Sign in
              </Link>
            </p>
          </>
        )}
      </motion.div>
    </SplitAuthLayout>
  );
}
