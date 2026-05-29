"use client";

import { Suspense, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { motion } from "framer-motion";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertTriangle, Eye, EyeOff, Loader2 } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import { resetPassword } from "@/lib/api";

const schema = z
  .object({
    password: z.string().min(8, "Password must be at least 8 characters"),
    confirm: z.string(),
  })
  .refine((d) => d.password === d.confirm, {
    message: "Passwords don't match",
    path: ["confirm"],
  });
type FormData = z.infer<typeof schema>;

function ResetForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const token = searchParams.get("token") ?? "";

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
    try {
      await resetPassword(token, data.password);
      router.push("/login?reset=1");
    } catch (err) {
      setApiError(err instanceof Error ? err.message : "Couldn't reset password");
    } finally {
      setLoading(false);
    }
  };

  if (!token) {
    return (
      <SplitAuthLayout
        heroTitle={"Reset link missing.\nRequest a new one."}
        heroSubtitle="The link you opened doesn't include a token."
      >
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4 }}
          className="text-center"
        >
          <AlertTriangle size={28} className="mx-auto text-amber-600 mb-2" />
          <h2 className="text-2xl font-bold text-sage-navy mb-2">
            Invalid reset link
          </h2>
          <p className="text-sm text-gray-600 mb-6">
            This link is missing the reset token. Request a fresh one and
            we&apos;ll email it to you.
          </p>
          <Link
            href="/forgot-password"
            className="inline-block py-3 px-6 rounded-lg bg-sage-navy hover:bg-sage-navy-deep text-white font-semibold text-sm transition"
          >
            Request a new link
          </Link>
        </motion.div>
      </SplitAuthLayout>
    );
  }

  return (
    <SplitAuthLayout
      heroTitle={"Almost done.\nSet your new password."}
      heroSubtitle="Choose a strong password you'll remember. We'll sign you in next."
      heroFooter="At least eight characters — mix letters, numbers, and a symbol."
    >
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
      >
        <h2 className="text-3xl font-bold text-sage-navy text-center mb-2">
          New password
        </h2>
        <p className="text-center text-gray-600 mb-8">
          Pick a password with at least eight characters.
        </p>

        {apiError && (
          <div className="mb-5 px-4 py-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
            {apiError}
          </div>
        )}

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div>
            <label className="block text-sm font-semibold text-gray-700 mb-1.5">
              New password
            </label>
            <div className="relative">
              <input
                type={showPassword ? "text" : "password"}
                autoComplete="new-password"
                {...register("password")}
                placeholder="At least 8 characters"
                className={
                  "w-full px-4 py-3 pr-11 bg-white border rounded-lg text-gray-900 placeholder-gray-400 text-sm focus:outline-none focus:ring-2 focus:ring-sage-copper focus:border-transparent transition " +
                  (errors.password ? "border-red-400" : "border-gray-200")
                }
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
            {errors.password && (
              <p className="text-xs text-red-500 mt-1">
                {errors.password.message}
              </p>
            )}
          </div>

          <div>
            <label className="block text-sm font-semibold text-gray-700 mb-1.5">
              Confirm password
            </label>
            <input
              type={showPassword ? "text" : "password"}
              autoComplete="new-password"
              {...register("confirm")}
              placeholder="Re-enter the password"
              className={
                "w-full px-4 py-3 bg-white border rounded-lg text-gray-900 placeholder-gray-400 text-sm focus:outline-none focus:ring-2 focus:ring-sage-copper focus:border-transparent transition " +
                (errors.confirm ? "border-red-400" : "border-gray-200")
              }
            />
            {errors.confirm && (
              <p className="text-xs text-red-500 mt-1">
                {errors.confirm.message}
              </p>
            )}
          </div>

          <button
            type="submit"
            disabled={loading}
            className="w-full py-3 rounded-lg bg-sage-navy hover:bg-sage-navy-deep text-white font-semibold text-sm transition flex items-center justify-center gap-2 disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {loading && <Loader2 className="w-4 h-4 animate-spin" />}
            {loading ? "Updating..." : "Set new password"}
          </button>
        </form>

        <p className="text-center text-sm text-gray-600 mt-6">
          Remembered after all?{" "}
          <Link
            href="/login"
            className="text-sage-copper font-semibold hover:underline"
          >
            Sign in
          </Link>
        </p>
      </motion.div>
    </SplitAuthLayout>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense fallback={null}>
      <ResetForm />
    </Suspense>
  );
}
