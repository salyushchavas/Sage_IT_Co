"use client";

import { useState, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { motion } from "framer-motion";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { Loader2, LogIn, Eye, EyeOff } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { cn } from "@/lib/utils";
import { APP_NAME } from "@/lib/constants";

const schema = z.object({
  email: z.string().email("Enter a valid email"),
  password: z.string().min(6, "Password must be at least 6 characters"),
});

type FormData = z.infer<typeof schema>;

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const redirect = searchParams.get("redirect") || "/dashboard";
  const { login } = useAuth();

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
      await login(data.email, data.password);
      router.push(redirect);
    } catch (err) {
      setApiError(err instanceof Error ? err.message : "Login failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.5 }}
      className="w-full max-w-md"
    >
      <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-8">
        {/* Header */}
        <div className="text-center mb-8">
          <Link href="/" className="inline-block mb-4">
            <span className="text-2xl font-bold bg-gradient-to-r from-[#0F5132] to-[#D4A017] bg-clip-text text-transparent">
              {APP_NAME}
            </span>
          </Link>
          <h1 className="text-xl font-semibold text-zinc-900">Welcome back</h1>
          <p className="text-sm text-zinc-600 mt-1">Sign in to your account</p>
        </div>

        {apiError && (
          <div className="mb-6 p-3 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-sm text-center">
            {apiError}
          </div>
        )}

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-5">
          {/* Email */}
          <div>
            <label className="block text-sm text-zinc-600 mb-1.5">Email</label>
            <input
              type="email"
              {...register("email")}
              placeholder="you@example.com"
              className={cn(
                "w-full px-4 py-3 rounded-xl bg-white/60 border text-zinc-900 placeholder-zinc-400 text-sm outline-none transition-all",
                errors.email
                  ? "border-red-500/50 focus:border-red-500"
                  : "border-zinc-200 focus:border-[#0F5132]/50 focus:shadow-[0_0_12px_rgba(15,81,50,0.1)]"
              )}
            />
            {errors.email && (
              <p className="text-xs text-red-400 mt-1">{errors.email.message}</p>
            )}
          </div>

          {/* Password */}
          <div>
            <label className="block text-sm text-zinc-600 mb-1.5">Password</label>
            <div className="relative">
              <input
                type={showPassword ? "text" : "password"}
                {...register("password")}
                placeholder="Enter your password"
                className={cn(
                  "w-full px-4 py-3 pr-11 rounded-xl bg-white/60 border text-zinc-900 placeholder-zinc-400 text-sm outline-none transition-all",
                  errors.password
                    ? "border-red-500/50 focus:border-red-500"
                    : "border-zinc-200 focus:border-[#0F5132]/50 focus:shadow-[0_0_12px_rgba(15,81,50,0.1)]"
                )}
              />
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-zinc-500 hover:text-zinc-500"
              >
                {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
            {errors.password && (
              <p className="text-xs text-red-400 mt-1">{errors.password.message}</p>
            )}
          </div>

          {/* Submit */}
          <button
            type="submit"
            disabled={loading}
            className={cn(
              "w-full py-3 rounded-full font-medium text-sm text-zinc-900 transition-all duration-300 flex items-center justify-center gap-2",
              "bg-gradient-to-r from-[#0F5132] to-[#D4A017]",
              "hover:shadow-[0_0_30px_rgba(15,81,50,0.3)] hover:scale-[1.02]",
              "disabled:opacity-50 disabled:cursor-not-allowed"
            )}
          >
            {loading ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <LogIn className="w-4 h-4" />
            )}
            {loading ? "Signing in..." : "Sign In"}
          </button>
        </form>

        <p className="text-center text-sm text-zinc-500 mt-6">
          Don&apos;t have an account?{" "}
          <Link href="/signup" className="text-[#0F5132] hover:underline">
            Sign up
          </Link>
        </p>
      </div>
    </motion.div>
  );
}

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginForm />
    </Suspense>
  );
}
