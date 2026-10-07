"use client";

import { useEffect, useRef, useState, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { motion } from "framer-motion";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { Loader2, Eye, EyeOff } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { dashboardRouteForRole, rememberSignUpPassword, safeRedirect } from "@/lib/api";
import SplitAuthLayout from "@/components/layout/SplitAuthLayout";

const schema = z.object({
  email: z.string().email("Enter a valid email"),
  password: z.string().min(6, "Password must be at least 6 characters"),
});

type FormData = z.infer<typeof schema>;

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  // Only a page on this site (a link could otherwise send people elsewhere).
  const redirect = safeRedirect(searchParams.get("redirect"));
  const justReset = searchParams.get("reset") === "1";
  const { login, user, isAuthenticated, isLoading } = useAuth();
  // Set while this form signs in: onSubmit chooses where to go then.
  const signingIn = useRef(false);

  // Already signed in (e.g. Back after signing in): skip the form and go
  // to the page asked for, or this account's home page.
  useEffect(() => {
    if (isLoading || !isAuthenticated || !user || signingIn.current) return;
    const asked = searchParams.get("redirect");
    router.replace(
      user.mustChangePassword
        ? "/change-password"
        : safeRedirect(asked, dashboardRouteForRole(user.role)),
    );
  }, [isLoading, isAuthenticated, user, searchParams, router]);

  const [showPassword, setShowPassword] = useState(false);
  const [apiError, setApiError] = useState("");
  const [loading, setLoading] = useState(false);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) });

  const onSubmit = async (data: FormData) => {
    setApiError("");
    setLoading(true);
    signingIn.current = true;
    try {
      const me = await login(data.email, data.password);
      // Staff onboarding: a temporary password must be replaced first.
      router.push(me?.mustChangePassword ? "/change-password" : redirect);
    } catch (err) {
      const message = err instanceof Error ? err.message : "Login failed";
      if (message === "EMAIL_NOT_VERIFIED") {
        // Send them to finish verifying instead of showing the raw code.
        rememberSignUpPassword(data.email, data.password);
        router.push(`/verify-email?email=${encodeURIComponent(data.email.trim())}&from=login`);
        return;
      }
      signingIn.current = false;
      setApiError(message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <SplitAuthLayout
      heroTitle={"Welcome back.\nLet's pick up where you left off."}
      heroSubtitle="Sign in to continue your program — your mentors, courses, and assignments are waiting."
      heroFooter="New here? Use Apply for a course to get started."
    >
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
      >
        <h2 className="text-3xl font-bold text-sage-navy text-center mb-2">
          Welcome back
        </h2>
        <p className="text-center text-gray-600 mb-8">
          Sign in to continue your learning journey.
        </p>

        {justReset && (
          <div className="mb-5 px-4 py-3 rounded-lg bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm">
            Password updated. Sign in with your new password.
          </div>
        )}

        {apiError && (
          <div className="mb-5 px-4 py-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
            {apiError}
          </div>
        )}

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div>
            <label className="block text-sm font-semibold text-gray-700 mb-1.5">
              Email address
            </label>
            <input
              type="email"
              autoComplete="email"
              {...register("email")}
              placeholder="you@example.com"
              className={
                "w-full px-4 py-3 bg-white border rounded-lg text-gray-900 placeholder-gray-400 text-sm focus:outline-none focus:ring-2 focus:ring-sage-copper focus:border-transparent transition " +
                (errors.email ? "border-red-400" : "border-gray-200")
              }
            />
            {errors.email && (
              <p className="text-xs text-red-500 mt-1">{errors.email.message}</p>
            )}
          </div>

          <div>
            <div className="flex items-center justify-between mb-1.5">
              <label className="block text-sm font-semibold text-gray-700">
                Password
              </label>
              <Link
                href="/forgot-password"
                className="text-xs text-sage-copper-deep font-semibold hover:underline"
              >
                Forgot password?
              </Link>
            </div>
            <div className="relative">
              <input
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                {...register("password")}
                placeholder="Enter your password"
                className={
                  "w-full px-4 py-3 pr-11 bg-white border rounded-lg text-gray-900 placeholder-gray-400 text-sm focus:outline-none focus:ring-2 focus:ring-sage-copper focus:border-transparent transition " +
                  (errors.password ? "border-red-400" : "border-gray-200")
                }
              />
              <button
                type="button"
                onClick={() => setShowPassword((v) => !v)}
                aria-label={showPassword ? "Hide password" : "Show password"}
                className="absolute right-1 top-1/2 -translate-y-1/2 p-2 text-gray-400 hover:text-gray-600"
              >
                {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
            {errors.password && (
              <p className="text-xs text-red-500 mt-1">{errors.password.message}</p>
            )}
          </div>

          <button
            type="submit"
            disabled={loading}
            className="w-full py-3 rounded-lg bg-sage-navy hover:bg-sage-navy-deep text-white font-semibold text-sm transition flex items-center justify-center gap-2 disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {loading && <Loader2 className="w-4 h-4 animate-spin" />}
            {loading ? "Signing in..." : "Sign In"}
          </button>
        </form>

        <p className="text-center text-sm text-gray-600 mt-6">
          Don&apos;t have an account?{" "}
          <Link href="/enroll" className="text-sage-copper-deep font-semibold hover:underline">
            Apply for a course
          </Link>
        </p>
      </motion.div>
    </SplitAuthLayout>
  );
}

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginForm />
    </Suspense>
  );
}
