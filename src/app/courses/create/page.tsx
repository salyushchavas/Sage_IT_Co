"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { BookPlus, Loader2, CheckCircle, AlertCircle } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { createCourse } from "@/lib/api";
import { cn } from "@/lib/utils";

const LEVELS = ["BEGINNER", "INTERMEDIATE", "ADVANCED"] as const;

export default function CreateCoursePage() {
  const router = useRouter();
  const { user, isAuthenticated, isLoading: authLoading } = useAuth();

  const [form, setForm] = useState({
    title: "", description: "", shortDescription: "",
    price: "", level: "BEGINNER", category: "", tags: "",
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState(false);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(""); setLoading(true);
    try {
      await createCourse({
        title: form.title,
        description: form.description || undefined,
        shortDescription: form.shortDescription || undefined,
        level: form.level,
        price: form.price ? parseFloat(form.price) : 0,
        category: form.category || undefined,
        tags: form.tags || undefined,
      });
      setSuccess(true);
      setTimeout(() => router.push("/courses"), 1500);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create course");
    } finally { setLoading(false); }
  };

  if (authLoading) {
    return <div className="flex items-center justify-center min-h-screen pt-24"><Loader2 className="w-8 h-8 animate-spin text-[#22C55E]" /></div>;
  }

  if (!isAuthenticated) { router.push("/login?redirect=/courses/create"); return null; }

  const role = user?.role?.toUpperCase();
  if (role !== "INSTRUCTOR" && role !== "ADMIN") {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen pt-24 px-6">
        <AlertCircle className="w-12 h-12 text-red-400 mb-4" />
        <h2 className="text-xl font-semibold text-zinc-900 mb-2">Access Denied</h2>
        <p className="text-zinc-600 text-center max-w-md">Only approved instructors and admins can create courses.</p>
      </div>
    );
  }

  if (success) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen pt-24">
        <motion.div initial={{ scale: 0 }} animate={{ scale: 1 }} transition={{ type: "spring", stiffness: 200 }}>
          <CheckCircle className="w-16 h-16 text-green-400 mb-4" />
        </motion.div>
        <h2 className="text-2xl font-bold text-zinc-900 mb-2">Course Created!</h2>
        <p className="text-zinc-600">Redirecting to courses...</p>
      </div>
    );
  }

  const inputClass = "w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 text-sm placeholder-zinc-400 outline-none focus:border-[#22C55E]/40 focus:shadow-[0_0_12px_rgba(0,212,255,0.1)] transition-all";

  return (
    <section className="min-h-screen pt-32 pb-20 px-6">
      <div className="mx-auto max-w-2xl">
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }}>
          {/* Header */}
          <div className="flex items-center gap-3 mb-8">
            <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-[#E5B62E] to-[#22C55E] flex items-center justify-center">
              <BookPlus className="w-6 h-6 text-zinc-900" />
            </div>
            <div>
              <h1 className="text-3xl font-bold text-zinc-900">Create Course</h1>
              <p className="text-zinc-600 text-sm">Fill in the details to publish a new course</p>
            </div>
          </div>

          {error && (
            <div className="mb-6 p-4 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-sm flex items-center gap-2">
              <AlertCircle className="w-4 h-4" /> {error}
            </div>
          )}

          <form onSubmit={handleSubmit} className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-8 space-y-6">
            <div>
              <label className="block text-sm text-zinc-600 mb-1.5">Course Title <span className="text-red-400">*</span></label>
              <input type="text" name="title" value={form.title} onChange={handleChange} required placeholder="e.g., Full-Stack Web Development" className={inputClass} />
            </div>

            <div>
              <label className="block text-sm text-zinc-600 mb-1.5">Short Description</label>
              <input type="text" name="shortDescription" value={form.shortDescription} onChange={handleChange} placeholder="One-line summary" maxLength={200} className={inputClass} />
            </div>

            <div>
              <label className="block text-sm text-zinc-600 mb-1.5">Full Description</label>
              <textarea name="description" value={form.description} onChange={handleChange} rows={4} placeholder="Detailed course description..."
                className={cn(inputClass, "resize-none")} />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm text-zinc-600 mb-1.5">Price (INR)</label>
                <input type="number" name="price" value={form.price} onChange={handleChange} min="0" step="1" placeholder="0 = Free" className={inputClass} />
                <p className="text-xs text-zinc-600 mt-1">Leave 0 for free courses</p>
              </div>
              <div>
                <label className="block text-sm text-zinc-600 mb-1.5">Level <span className="text-red-400">*</span></label>
                <select name="level" value={form.level} onChange={handleChange}
                  className={cn(inputClass, "bg-[#050510]")}>
                  {LEVELS.map((l) => (
                    <option key={l} value={l}>{l.charAt(0) + l.slice(1).toLowerCase()}</option>
                  ))}
                </select>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm text-zinc-600 mb-1.5">Category</label>
                <input type="text" name="category" value={form.category} onChange={handleChange} placeholder="e.g., Web Development" className={inputClass} />
              </div>
              <div>
                <label className="block text-sm text-zinc-600 mb-1.5">Tags</label>
                <input type="text" name="tags" value={form.tags} onChange={handleChange} placeholder="react, javascript, web" className={inputClass} />
              </div>
            </div>

            <button type="submit" disabled={loading || !form.title.trim()}
              className={cn(
                "w-full py-3.5 rounded-xl text-sm font-semibold transition-all flex items-center justify-center gap-2",
                "bg-gradient-to-r from-[#22C55E] to-[#E5B62E] text-zinc-900",
                "hover:shadow-[0_0_30px_rgba(0,212,255,0.3)] hover:scale-[1.01]",
                "disabled:opacity-50 disabled:cursor-not-allowed"
              )}>
              {loading && <Loader2 className="w-4 h-4 animate-spin" />}
              {loading ? "Creating..." : "Create Course"}
            </button>
          </form>
        </motion.div>
      </div>
    </section>
  );
}
