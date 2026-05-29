"use client";

import { useState } from "react";
import Link from "next/link";
import { motion } from "framer-motion";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { Loader2, MailCheck } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import { requestPasswordReset } from "@/lib/api";

const schema = z.object({
  email: z.string().email("Enter a valid email"),
});
type FormData = z.infer<typeof schema>;

export default function ForgotPasswordPage() {
  const [submitted, setSubmitted] = useState(false);
  const [apiError, setApiError] = useState("");
  const [loading, setLoading] = useState(false);

  const {
    register,
    handleSubmit,
    getValues,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) });

  const onSubmit = async (data: FormData) => {
    setApiError("");
    setLoading(true);
    try {
      await requestPasswordReset(data.email);
      setSubmitted(true);
    } catch (err) {
      setApiError(err instanceof Error ? err.message : "Couldn't send reset link");
    } finally {
      setLoading(false);
    }
  };

  return (
    <SplitAuthLayout
      heroTitle={"Forgot your password?\nLet's get you back in."}
      heroSubtitle="Enter the email tied to your account and we'll send a reset link."
      heroFooter="Reset links expire after one hour for your security."
    >
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
      >
        <h2 className="text-3xl font-bold text-sage-navy text-center mb-2">
          Reset your password
        </h2>
        <p className="text-center text-gray-600 mb-8">
          We&apos;ll email you a link to set a new one.
        </p>

        {submitted ? (
          <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-5 py-6 text-center">
            <MailCheck size={28} className="mx-auto text-emerald-600 mb-2" />
            <p className="text-sm font-semibold text-emerald-800">
              Check your inbox
            </p>
            <p className="text-xs text-emerald-700 mt-1.5">
              If {getValues("email")} is registered, a reset link is on its
              way. The link expires in one hour.
            </p>
            <Link
              href="/login"
              className="mt-4 inline-block text-xs text-sage-copper font-semibold hover:underline"
            >
              Back to sign in
            </Link>
          </div>
        ) : (
          <>
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
                  <p className="text-xs text-red-500 mt-1">
                    {errors.email.message}
                  </p>
                )}
              </div>

              <button
                type="submit"
                disabled={loading}
                className="w-full py-3 rounded-lg bg-sage-navy hover:bg-sage-navy-deep text-white font-semibold text-sm transition flex items-center justify-center gap-2 disabled:opacity-60 disabled:cursor-not-allowed"
              >
                {loading && <Loader2 className="w-4 h-4 animate-spin" />}
                {loading ? "Sending..." : "Send reset link"}
              </button>
            </form>

            <p className="text-center text-sm text-gray-600 mt-6">
              Remembered it?{" "}
              <Link
                href="/login"
                className="text-sage-copper font-semibold hover:underline"
              >
                Sign in
              </Link>
            </p>
          </>
        )}
      </motion.div>
    </SplitAuthLayout>
  );
}
