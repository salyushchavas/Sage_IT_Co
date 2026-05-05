"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { motion } from "framer-motion";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { Loader2, UserPlus, Eye, EyeOff } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { cn } from "@/lib/utils";
import { APP_NAME } from "@/lib/constants";

const schema = z
  .object({
    fullName: z.string().min(2, "Name must be at least 2 characters"),
    email: z.string().email("Enter a valid email"),
    password: z.string().min(6, "Password must be at least 6 characters"),
    confirmPassword: z.string(),
  })
  .refine((d) => d.password === d.confirmPassword, {
    message: "Passwords don't match",
    path: ["confirmPassword"],
  });

type FormData = z.infer<typeof schema>;

export default function SignupPage() {
  const router = useRouter();
  const { register: authRegister } = useAuth();

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
      await authRegister(data.fullName, data.email, data.password);
      router.push("/dashboard");
    } catch (err) {
      setApiError(err instanceof Error ? err.message : "Registration failed");
    } finally {
      setLoading(false);
    }
  };

  const inputClass = (hasError: boolean) =>
    cn(
      "w-full px-4 py-3 rounded-xl bg-white/60 border text-zinc-900 placeholder-zinc-400 text-sm outline-none transition-all",
      hasError
        ? "border-red-500/50 focus:border-red-500"
        : "border-zinc-200 focus:border-[#0F5132]/50 focus:shadow-[0_0_12px_rgba(15,81,50,0.1)]"
    );

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
          <h1 className="text-xl font-semibold text-zinc-900">Create an account</h1>
          <p className="text-sm text-zinc-600 mt-1">Start your learning journey</p>
        </div>

        {apiError && (
          <div className="mb-6 p-3 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-sm text-center">
            {apiError}
          </div>
        )}

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-5">
          {/* Full Name */}
          <div>
            <label className="block text-sm text-zinc-600 mb-1.5">Full Name</label>
            <input
              type="text"
              {...register("fullName")}
              placeholder="John Doe"
              className={inputClass(!!errors.fullName)}
            />
            {errors.fullName && (
              <p className="text-xs text-red-400 mt-1">{errors.fullName.message}</p>
            )}
          </div>

          {/* Email */}
          <div>
            <label className="block text-sm text-zinc-600 mb-1.5">Email</label>
            <input
              type="email"
              {...register("email")}
              placeholder="you@example.com"
              className={inputClass(!!errors.email)}
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
                placeholder="Min 6 characters"
                className={cn(inputClass(!!errors.password), "pr-11")}
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

          {/* Confirm Password */}
          <div>
            <label className="block text-sm text-zinc-600 mb-1.5">Confirm Password</label>
            <input
              type="password"
              {...register("confirmPassword")}
              placeholder="Re-enter your password"
              className={inputClass(!!errors.confirmPassword)}
            />
            {errors.confirmPassword && (
              <p className="text-xs text-red-400 mt-1">{errors.confirmPassword.message}</p>
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
              <UserPlus className="w-4 h-4" />
            )}
            {loading ? "Creating account..." : "Create Account"}
          </button>
        </form>

        <p className="text-center text-sm text-zinc-500 mt-6">
          Already have an account?{" "}
          <Link href="/login" className="text-[#0F5132] hover:underline">
            Sign in
          </Link>
        </p>
      </div>
    </motion.div>
  );
}
