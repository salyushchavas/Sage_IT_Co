"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { Briefcase, Loader2, CheckCircle, AlertCircle } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { createService, getUsers } from "@/lib/api";

const SERVICE_CATEGORIES = [
  "Resume Prep",
  "Interview Training",
  "LinkedIn Optimization",
  "Placement Assistance",
  "Other",
] as const;

interface UserRow {
  id: number;
  email: string;
  fullName: string;
  role: string;
}

export default function CreateServicePage() {
  const router = useRouter();
  const { user, isAuthenticated, isLoading: authLoading } = useAuth();

  const [form, setForm] = useState({
    title: "",
    description: "",
    shortDescription: "",
    price: "",
    category: SERVICE_CATEGORIES[0] as string,
    customCategory: "",
    trainerId: "",
  });

  const [trainers, setTrainers] = useState<UserRow[]>([]);
  const [trainersLoading, setTrainersLoading] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState(false);

  const role = user?.role?.toUpperCase();
  const canCreate = role === "ADMIN" || role === "SYSTEM_ADMIN" || role === "TRAINER";

  useEffect(() => {
    if (!canCreate) return;
    setTrainersLoading(true);
    getUsers()
      .then((data) => {
        const filtered = ((data ?? []) as UserRow[]).filter(
          (u) => u.role?.toUpperCase() === "TRAINER"
        );
        setTrainers(filtered);
      })
      .catch(() => setTrainers([]))
      .finally(() => setTrainersLoading(false));
  }, [canCreate]);

  const handleChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>
  ) => {
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);

    try {
      const category =
        form.category === "Other" && form.customCategory.trim()
          ? form.customCategory.trim()
          : form.category;

      await createService({
        title: form.title,
        description: form.description || undefined,
        shortDescription: form.shortDescription || undefined,
        price: form.price ? parseFloat(form.price) : 0,
        category: category || undefined,
        trainerId: form.trainerId ? Number(form.trainerId) : undefined,
      });

      setSuccess(true);
      setTimeout(() => router.push("/services"), 1500);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create service");
    } finally {
      setLoading(false);
    }
  };

  if (authLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen pt-24">
        <Loader2 className="animate-spin text-sage-navy" size={32} />
      </div>
    );
  }

  if (!isAuthenticated) {
    router.push("/login?redirect=/services/create");
    return null;
  }

  if (!canCreate) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen pt-24 px-6">
        <AlertCircle size={48} className="text-red-400 mb-4" />
        <h2 className="text-xl font-semibold text-gray-900 mb-2">Access Denied</h2>
        <p className="text-gray-500 text-center max-w-md">
          Only admins and trainers can create services.
        </p>
      </div>
    );
  }

  if (success) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen pt-24">
        <motion.div
          initial={{ scale: 0 }}
          animate={{ scale: 1 }}
          transition={{ type: "spring", stiffness: 200 }}
        >
          <CheckCircle size={64} className="text-sage-navy mb-4" />
        </motion.div>
        <h2 className="text-2xl font-bold text-gray-900 mb-2">Service Created!</h2>
        <p className="text-gray-500">Redirecting…</p>
      </div>
    );
  }

  return (
    <section className="min-h-screen pt-32 pb-20 px-6">
      <div className="mx-auto max-w-2xl">
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }}>
          <div className="flex items-center gap-3 mb-8">
            <div className="w-12 h-12 rounded-xl bg-sage-navy text-white flex items-center justify-center">
              <Briefcase size={24} />
            </div>
            <div>
              <h1 className="text-3xl font-bold text-gray-900">Create Service</h1>
              <p className="text-gray-500 text-sm">
                Resume Prep, Interview Training, etc. — short, video-only.
              </p>
            </div>
          </div>

          {error && (
            <div className="mb-6 p-4 rounded-xl bg-red-50 border border-red-200 text-red-700 text-sm flex items-center gap-2">
              <AlertCircle size={16} />
              {error}
            </div>
          )}

          <form
            onSubmit={handleSubmit}
            className="bg-white rounded-2xl shadow-lg border border-gray-100 p-8 space-y-6"
          >
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                Title <span className="text-red-400">*</span>
              </label>
              <input
                type="text"
                name="title"
                value={form.title}
                onChange={handleChange}
                required
                placeholder="e.g., Resume Preparation Masterclass"
                className="w-full px-4 py-3 rounded-xl border border-gray-300 text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-sage-navy/30 focus:border-transparent transition"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                Short Description
              </label>
              <input
                type="text"
                name="shortDescription"
                value={form.shortDescription}
                onChange={handleChange}
                placeholder="One-line summary of the service"
                maxLength={200}
                className="w-full px-4 py-3 rounded-xl border border-gray-300 text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-sage-navy/30 focus:border-transparent transition"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                Full Description
              </label>
              <textarea
                name="description"
                value={form.description}
                onChange={handleChange}
                rows={4}
                placeholder="What the learner will get out of this service…"
                className="w-full px-4 py-3 rounded-xl border border-gray-300 text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-sage-navy/30 focus:border-transparent transition resize-none"
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Price (₹)</label>
                <input
                  type="number"
                  name="price"
                  value={form.price}
                  onChange={handleChange}
                  min="0"
                  step="1"
                  placeholder="0 = Free"
                  className="w-full px-4 py-3 rounded-xl border border-gray-300 text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-sage-navy/30 focus:border-transparent transition"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">Category</label>
                <select
                  name="category"
                  value={form.category}
                  onChange={handleChange}
                  className="w-full px-4 py-3 rounded-xl border border-gray-300 text-sm text-gray-900 bg-white focus:outline-none focus:ring-2 focus:ring-sage-navy/30 focus:border-transparent transition"
                >
                  {SERVICE_CATEGORIES.map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
                {form.category === "Other" && (
                  <input
                    type="text"
                    name="customCategory"
                    value={form.customCategory}
                    onChange={handleChange}
                    placeholder="Custom category"
                    className="mt-2 w-full px-4 py-2.5 rounded-xl border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30 transition"
                  />
                )}
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                Trainer (optional)
              </label>
              {trainersLoading ? (
                <div className="flex items-center gap-2 text-sm text-gray-500">
                  <Loader2 size={14} className="animate-spin" /> Loading trainers…
                </div>
              ) : trainers.length === 0 ? (
                <p className="text-xs text-gray-500">
                  No users with TRAINER role yet. You can create the service now and assign a
                  trainer later.
                </p>
              ) : (
                <select
                  name="trainerId"
                  value={form.trainerId}
                  onChange={handleChange}
                  className="w-full px-4 py-3 rounded-xl border border-gray-300 text-sm text-gray-900 bg-white focus:outline-none focus:ring-2 focus:ring-sage-navy/30 focus:border-transparent transition"
                >
                  <option value="">— Unassigned —</option>
                  {trainers.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.fullName} ({t.email})
                    </option>
                  ))}
                </select>
              )}
            </div>

            <button
              type="submit"
              disabled={loading || !form.title.trim()}
              className="w-full py-3.5 rounded-xl bg-sage-navy text-white text-sm font-semibold hover:bg-sage-navy-deep focus:outline-none focus:ring-2 focus:ring-sage-navy/30 focus:ring-offset-2 transition disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
            >
              {loading && <Loader2 size={16} className="animate-spin" />}
              {loading ? "Creating…" : "Create Service"}
            </button>
          </form>
        </motion.div>
      </div>
    </section>
  );
}
