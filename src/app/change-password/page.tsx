"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { Eye, EyeOff, Loader2 } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import { changeMyPassword } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { homeForRole } from "@/lib/roles";

const schema = z
  .object({
    current: z.string().min(1, "Enter your current password"),
    password: z.string().min(8, "Password must be at least 8 characters").max(100, "At most 100 characters"),
    confirm: z.string(),
  })
  .refine((d) => d.password === d.confirm, { message: "Passwords don't match", path: ["confirm"] })
  .refine((d) => d.password !== d.current, { message: "Choose a new password, not the current one", path: ["password"] });
type FormData = z.infer<typeof schema>;

const INPUT =
  "w-full px-4 py-3 pr-11 bg-white border rounded-lg text-gray-900 placeholder-gray-400 text-sm focus:outline-none focus:ring-2 focus:ring-sage-copper focus:border-transparent transition ";

/**
 * Staff onboarding: people whose account an admin created sign in with the
 * temporary password from their email, then choose their own here. The
 * server refuses everything else until they do. Anyone signed in can also
 * use it to change their password.
 */
export default function ChangePasswordPage() {
  const router = useRouter();
  const { user, isLoading, isAuthenticated, refreshUser } = useAuth();
  const [show, setShow] = useState(false);
  const [apiError, setApiError] = useState("");
  const [saving, setSaving] = useState(false);
  const firstTime = !!user?.mustChangePassword;

  useEffect(() => {
    if (!isLoading && !isAuthenticated) router.replace("/login?redirect=/change-password");
  }, [isLoading, isAuthenticated, router]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) });

  const onSubmit = async (data: FormData) => {
    setApiError("");
    setSaving(true);
    try {
      await changeMyPassword(data.current, data.password);
      await refreshUser();
      router.replace(homeForRole(user?.role));
    } catch (err) {
      setApiError(err instanceof Error ? err.message : "Couldn't change the password");
      setSaving(false);
    }
  };

  const field = (name: "current" | "password" | "confirm", label: string, placeholder: string, autoComplete: string) => (
    <div>
      <label className="block text-sm font-semibold text-gray-700 mb-1.5">{label}</label>
      <div className="relative">
        <input
          type={show ? "text" : "password"}
          autoComplete={autoComplete}
          {...register(name)}
          placeholder={placeholder}
          className={INPUT + (errors[name] ? "border-red-400" : "border-gray-200")}
        />
        {name === "current" && (
          <button
            type="button"
            onClick={() => setShow((v) => !v)}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
            aria-label={show ? "Hide passwords" : "Show passwords"}
          >
            {show ? <EyeOff size={16} /> : <Eye size={16} />}
          </button>
        )}
      </div>
      {errors[name] && <p className="text-xs text-red-500 mt-1">{errors[name]?.message}</p>}
    </div>
  );

  return (
    <SplitAuthLayout
      heroTitle={firstTime ? "Welcome aboard.\nChoose your password." : "Change your\npassword."}
      heroSubtitle={
        firstTime
          ? "You signed in with a temporary password. Pick your own to continue."
          : "Choose a strong password you'll remember."
      }
      heroFooter="At least eight characters — mix letters, numbers, and a symbol."
    >
      <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
        <h2 className="text-3xl font-bold text-sage-navy text-center mb-2">
          {firstTime ? "Choose your password" : "Change password"}
        </h2>
        <p className="text-center text-gray-600 mb-8">
          {firstTime
            ? "Enter the temporary password from your email, then your new one."
            : "Enter your current password, then your new one."}
        </p>

        {apiError && (
          <div className="mb-5 px-4 py-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">{apiError}</div>
        )}

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          {field("current", firstTime ? "Temporary password" : "Current password",
            firstTime ? "From your email" : "Your current password", "current-password")}
          {field("password", "New password", "At least 8 characters", "new-password")}
          {field("confirm", "Confirm new password", "Type it again", "new-password")}
          <button
            type="submit"
            disabled={saving}
            className="w-full py-3 rounded-lg bg-sage-navy hover:bg-sage-navy-deep text-white font-semibold text-sm transition disabled:opacity-60 flex items-center justify-center gap-2 cursor-pointer"
          >
            {saving && <Loader2 size={16} className="animate-spin" />} Save and continue
          </button>
        </form>
      </motion.div>
    </SplitAuthLayout>
  );
}
