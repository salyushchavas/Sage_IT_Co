"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import {
  Loader2, Plus, BookOpen, Users, Edit3, Settings, Globe, GlobeLock,
  Trash2, AlertCircle, AlertTriangle, ShieldCheck,
} from "lucide-react";
import {
  getMyCourses, publishCourse, unpublishCourse, deleteCourse,
  getPublishReadiness,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { cn } from "@/lib/utils";

interface InstructorCourse {
  id: number;
  title: string;
  shortDescription: string | null;
  description: string | null;
  level: string;
  price: number;
  isFree: boolean;
  isPublished: boolean;
  category: string | null;
  enrolledCount: number;
  lessonsCount: number;
  modulesCount: number | null;
  thumbnailUrl: string | null;
}

export default function InstructorHubPage() {
  const router = useRouter();
  const { user, isLoading: authLoading } = useAuth();
  const role = user?.role?.toUpperCase();
  const isInstructor = role === "INSTRUCTOR";
  const isAdmin = role === "ADMIN" || role === "SYSTEM_ADMIN";

  const [courses, setCourses] = useState<InstructorCourse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [busyId, setBusyId] = useState<number | null>(null);
  const [toast, setToast] = useState<{ kind: "success" | "error"; message: string } | null>(null);

  const [readiness, setReadiness] = useState<Record<number, string[]>>({});

  const showToast = (kind: "success" | "error", message: string) => {
    setToast({ kind, message });
    setTimeout(() => setToast(null), 3000);
  };

  const fetchCourses = async () => {
    setLoading(true);
    try {
      const data = await getMyCourses() as InstructorCourse[];
      setCourses(data ?? []);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load courses");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!user) return;
    if (!isInstructor && !isAdmin) return;
    fetchCourses();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user]);

  useEffect(() => {
    const drafts = courses.filter((c) => !c.isPublished);
    if (drafts.length === 0) return;
    let cancelled = false;
    Promise.all(drafts.map(async (c) => {
      try {
        const r = await getPublishReadiness(c.id);
        return [c.id, r.missing] as [number, string[]];
      } catch {
        return [c.id, []] as [number, string[]];
      }
    })).then((entries) => {
      if (!cancelled) {
        setReadiness((prev) => ({ ...prev, ...Object.fromEntries(entries) }));
      }
    });
    return () => { cancelled = true; };
  }, [courses]);

  const handlePublish = async (id: number) => {
    setBusyId(id);
    try {
      await publishCourse(id);
      showToast("success", "Course published.");
      fetchCourses();
    } catch (err) {
      showToast("error", err instanceof Error ? err.message : "Publish failed");
    } finally {
      setBusyId(null);
    }
  };

  const handleUnpublish = async (id: number) => {
    setBusyId(id);
    try {
      await unpublishCourse(id);
      showToast("success", "Course unpublished.");
      fetchCourses();
    } catch (err) {
      showToast("error", err instanceof Error ? err.message : "Unpublish failed");
    } finally {
      setBusyId(null);
    }
  };

  const handleDelete = async (id: number, enrollments: number) => {
    if (enrollments > 0) {
      showToast("error", "Can't delete a course with active enrollments. Unpublish it instead.");
      return;
    }
    if (!confirm("Delete this course? This cannot be undone.")) return;
    setBusyId(id);
    try {
      await deleteCourse(id);
      showToast("success", "Course deleted.");
      setCourses((prev) => prev.filter((c) => c.id !== id));
    } catch (err) {
      showToast("error", err instanceof Error ? err.message : "Delete failed");
    } finally {
      setBusyId(null);
    }
  };

  const sortedCourses = useMemo(
    () => [...courses].sort((a, b) => Number(a.isPublished) - Number(b.isPublished)),
    [courses]
  );

  if (authLoading) {
    return (
      <section className="mx-auto max-w-5xl px-6 pt-32 pb-20 flex items-center justify-center min-h-[60vh]">
        <Loader2 size={32} className="animate-spin text-sage-navy" />
      </section>
    );
  }

  if (!user) {
    router.push("/login?redirect=/instructor");
    return null;
  }

  if (!isInstructor && !isAdmin) {
    return (
      <section className="mx-auto max-w-md px-6 pt-32 pb-20 text-center">
        <ShieldCheck size={48} className="text-gray-300 mx-auto mb-4" />
        <h1 className="text-2xl font-bold text-gray-900 mb-2">Instructor only</h1>
        <p className="text-gray-500 mb-6">
          This panel is for approved instructors. Apply via your profile if you&apos;d like to teach.
        </p>
        <Link href="/dashboard" className="text-sage-navy underline text-sm">
          Go to dashboard
        </Link>
      </section>
    );
  }

  return (
    <section className="mx-auto max-w-5xl px-6 pt-28 pb-20 min-h-screen">
      {toast && (
        <div
          className={
            "fixed top-24 right-6 z-50 px-4 py-2.5 rounded-lg text-sm font-semibold shadow-lg " +
            (toast.kind === "success" ? "bg-emerald-600 text-white" : "bg-red-700 text-white")
          }
        >
          {toast.message}
        </div>
      )}

      <div className="flex flex-col sm:flex-row sm:items-end sm:justify-between gap-4 mb-8">
        <div>
          <h1 className="text-3xl font-bold text-sage-navy">My Courses</h1>
          <p className="text-sm text-gray-500 mt-1">
            Build, edit, and publish your courses.
          </p>
        </div>
        <Link
          href="/instructor/courses/create"
          className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition cursor-pointer"
        >
          <Plus size={14} /> Create Course
        </Link>
      </div>

      {error && (
        <div className="mb-6 bg-red-50 text-red-700 px-4 py-3 rounded-xl text-sm flex items-center gap-2">
          <AlertCircle size={16} /> {error}
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-20">
          <Loader2 size={28} className="animate-spin text-sage-navy" />
        </div>
      ) : sortedCourses.length === 0 ? (
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          className="bg-white rounded-2xl border border-gray-100 p-10 text-center"
        >
          <BookOpen size={40} className="mx-auto text-gray-300 mb-3" />
          <p className="text-gray-500 mb-1 font-medium">No courses yet.</p>
          <p className="text-xs text-gray-400 mb-5">Start building your first course.</p>
          <Link
            href="/instructor/courses/create"
            className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition"
          >
            <Plus size={14} /> Create your first course
          </Link>
        </motion.div>
      ) : (
        <div className="space-y-4">
          {sortedCourses.map((c, idx) => {
            const missing = readiness[c.id];
            const moduleCount = c.modulesCount ?? 0;
            return (
              <motion.div
                key={c.id}
                initial={{ opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: idx * 0.04 }}
                className="bg-white rounded-2xl border border-gray-100 shadow-sm hover:shadow-md transition p-5"
              >
                <div className="flex flex-col sm:flex-row gap-4">
                  <div className="w-full sm:w-40 h-24 rounded-lg overflow-hidden bg-gradient-to-br from-sage-navy/10 to-sage-navy-deep/20 flex-shrink-0 flex items-center justify-center relative">
                    {c.thumbnailUrl ? (
                      <Image
                        src={c.thumbnailUrl}
                        alt={c.title}
                        fill
                        className="object-cover"
                        unoptimized
                      />
                    ) : (
                      <BookOpen size={28} className="text-sage-navy/40" />
                    )}
                  </div>

                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap mb-1">
                      <h3 className="font-semibold text-gray-900 truncate">{c.title}</h3>
                      <span className={cn(
                        "text-[10px] font-semibold uppercase tracking-wide px-2 py-0.5 rounded-full",
                        c.isPublished ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"
                      )}>
                        {c.isPublished ? "Published" : "Draft"}
                      </span>
                      <span className="text-[10px] font-semibold uppercase tracking-wide px-2 py-0.5 rounded-full bg-gray-100 text-gray-600">
                        {c.level}
                      </span>
                    </div>
                    {c.shortDescription && (
                      <p className="text-sm text-gray-500 line-clamp-1">{c.shortDescription}</p>
                    )}
                    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 mt-2 text-xs text-gray-500">
                      <span className="inline-flex items-center gap-1">
                        <Users size={12} /> {c.enrolledCount} student{c.enrolledCount === 1 ? "" : "s"}
                      </span>
                      <span>· {moduleCount} module{moduleCount === 1 ? "" : "s"}</span>
                      <span>· {c.lessonsCount} lesson{c.lessonsCount === 1 ? "" : "s"}</span>
                      <span className="font-semibold text-gray-700">
                        {c.isFree ? "Free" : `₹${Number(c.price).toLocaleString("en-IN")}`}
                      </span>
                      {c.category && <span>· {c.category}</span>}
                    </div>

                    {!c.isPublished && missing && missing.length > 0 && (
                      <div className="mt-2 inline-flex items-start gap-1.5 text-[11px] text-amber-700 bg-amber-50 border border-amber-200 px-2 py-1 rounded-md">
                        <AlertTriangle size={12} className="mt-0.5 shrink-0" />
                        <span>Missing: {missing.join("; ")}</span>
                      </div>
                    )}
                  </div>

                  <div className="flex flex-row sm:flex-col items-stretch sm:items-end gap-2 shrink-0">
                    <Link
                      href={`/instructor/courses/${c.id}/edit`}
                      className="inline-flex items-center justify-center gap-1 px-3 py-2 rounded-lg text-xs font-medium text-gray-700 bg-gray-100 hover:bg-gray-200 transition"
                    >
                      <Edit3 size={12} /> Edit
                    </Link>
                    <Link
                      href={`/instructor/courses/${c.id}/content`}
                      className="inline-flex items-center justify-center gap-1 px-3 py-2 rounded-lg text-xs font-medium text-gray-700 bg-gray-100 hover:bg-gray-200 transition"
                    >
                      <Settings size={12} /> Manage Content
                    </Link>
                    {c.isPublished ? (
                      <button
                        onClick={() => handleUnpublish(c.id)}
                        disabled={busyId === c.id}
                        className="inline-flex items-center justify-center gap-1 px-3 py-2 rounded-lg text-xs font-medium text-amber-700 bg-amber-50 hover:bg-amber-100 disabled:opacity-50 transition cursor-pointer"
                      >
                        {busyId === c.id ? <Loader2 size={12} className="animate-spin" /> : <GlobeLock size={12} />}
                        Unpublish
                      </button>
                    ) : (
                      <button
                        onClick={() => handlePublish(c.id)}
                        disabled={busyId === c.id || (missing !== undefined && missing.length > 0)}
                        title={missing && missing.length > 0 ? "Resolve the missing items first" : undefined}
                        className="inline-flex items-center justify-center gap-1 px-3 py-2 rounded-lg text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
                      >
                        {busyId === c.id ? <Loader2 size={12} className="animate-spin" /> : <Globe size={12} />}
                        Publish
                      </button>
                    )}
                    <button
                      onClick={() => handleDelete(c.id, c.enrolledCount)}
                      disabled={busyId === c.id}
                      title={c.enrolledCount > 0 ? "Course has active enrollments — unpublish instead." : "Delete course"}
                      className="inline-flex items-center justify-center gap-1 px-3 py-2 rounded-lg text-xs font-medium text-red-700 bg-red-50 hover:bg-red-100 disabled:opacity-40 disabled:cursor-not-allowed transition cursor-pointer"
                    >
                      <Trash2 size={12} />
                    </button>
                  </div>
                </div>
              </motion.div>
            );
          })}

          <Link
            href="/instructor/courses/create"
            className="block bg-white border-2 border-dashed border-gray-300 hover:border-sage-navy/50 rounded-2xl p-6 text-center hover:bg-sage-navy/5 transition cursor-pointer"
          >
            <Plus size={20} className="mx-auto text-gray-400 mb-1.5" />
            <p className="text-sm font-semibold text-gray-700">Create a new course</p>
            <p className="text-xs text-gray-400 mt-0.5">Start building your next course</p>
          </Link>
        </div>
      )}
    </section>
  );
}
