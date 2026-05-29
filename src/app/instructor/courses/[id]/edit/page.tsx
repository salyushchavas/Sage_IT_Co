"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  ChevronLeft, Edit3, Loader2, Globe, GlobeLock, Trash2, Settings, AlertCircle,
} from "lucide-react";
import {
  getCourse, updateCourse, deleteCourse, publishCourse, unpublishCourse,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useToast } from "@/components/ui/Toast";
import { CourseForm, type CourseFormValues } from "@/components/instructor/CourseForm";
import { cn } from "@/lib/utils";

interface CourseDetail {
  id: number;
  title: string;
  description: string | null;
  shortDescription: string | null;
  level: string;
  price: number;
  isFree: boolean;
  isPublished: boolean;
  category: string | null;
  tags: string | null;
  thumbnailUrl: string | null;
  enrolledCount: number;
  instructor?: { id: number };
}

export default function EditInstructorCoursePage({ params }: { params: { id: string } }) {
  const { id } = params;
  const router = useRouter();
  const { toast } = useToast();
  const { user, isLoading: authLoading } = useAuth();
  const role = user?.role?.toUpperCase();

  const [course, setCourse] = useState<CourseDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (authLoading) return;
    if (!user) {
      router.push(`/login?redirect=/instructor/courses/${id}/edit`);
      return;
    }
    if (role !== "INSTRUCTOR" && role !== "ADMIN" && role !== "SYSTEM_ADMIN") {
      router.push("/dashboard");
      return;
    }

    const load = async () => {
      setLoading(true);
      try {
        const data = await getCourse(id) as CourseDetail;
        setCourse(data);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to load course");
      } finally {
        setLoading(false);
      }
    };
    load();
  }, [user, authLoading, role, id, router]);

  const handleSubmit = async (values: CourseFormValues) => {
    if (!course) return;
    const updated = await updateCourse(course.id, {
      title: values.title.trim(),
      shortDescription: values.shortDescription.trim(),
      description: values.description.trim(),
      category: values.category.trim(),
      level: values.level,
      price: Number(values.price),
      tags: values.tags.trim(),
      thumbnailUrl: values.thumbnailUrl.trim() || null,
    }) as CourseDetail;
    setCourse(updated);
    toast("success", "Changes saved.");
  };

  const handlePublishToggle = async () => {
    if (!course) return;
    setBusy(true);
    try {
      const next = course.isPublished
        ? await unpublishCourse(course.id) as CourseDetail
        : await publishCourse(course.id) as CourseDetail;
      setCourse(next);
      toast("success", next.isPublished ? "Course published." : "Course unpublished.");
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Action failed");
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async () => {
    if (!course) return;
    if (course.enrolledCount > 0) {
      toast("error", "Cannot delete a course with active enrollments. Unpublish it instead.");
      return;
    }
    if (!confirm("Delete this course? This cannot be undone.")) return;
    setBusy(true);
    try {
      await deleteCourse(course.id);
      toast("success", "Course deleted.");
      router.push("/instructor");
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Delete failed");
      setBusy(false);
    }
  };

  if (loading || authLoading) {
    return (
      <section className="mx-auto max-w-2xl px-6 pt-32 pb-20 flex items-center justify-center">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </section>
    );
  }

  if (error || !course) {
    return (
      <section className="mx-auto max-w-md px-6 pt-32 pb-20 text-center">
        <AlertCircle size={36} className="text-red-400 mx-auto mb-3" />
        <p className="text-gray-700 mb-4">{error || "Course not found"}</p>
        <Link href="/instructor" className="text-sage-navy underline text-sm">
          Back to my courses
        </Link>
      </section>
    );
  }

  return (
    <section className="mx-auto max-w-2xl px-6 pt-28 pb-20 min-h-screen">
      <Link
        href="/instructor"
        className="inline-flex items-center gap-1 text-sm text-gray-500 hover:text-sage-navy mb-4"
      >
        <ChevronLeft size={16} /> Back to my courses
      </Link>

      <div className="flex items-center gap-3 mb-6">
        <div className="w-11 h-11 rounded-xl bg-sage-navy text-white flex items-center justify-center">
          <Edit3 size={20} />
        </div>
        <div className="flex-1 min-w-0">
          <h1 className="text-2xl font-bold text-gray-900 truncate">{course.title}</h1>
          <div className="flex items-center gap-2 mt-1">
            <span className={cn(
              "text-[10px] font-semibold uppercase tracking-wide px-2 py-0.5 rounded-full",
              course.isPublished ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"
            )}>
              {course.isPublished ? "Published" : "Draft"}
            </span>
            <span className="text-xs text-gray-500">{course.enrolledCount} enrolled</span>
          </div>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2 mb-6">
        <button
          onClick={handlePublishToggle}
          disabled={busy}
          className={cn(
            "inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-semibold transition cursor-pointer disabled:opacity-50",
            course.isPublished
              ? "text-amber-700 bg-amber-50 hover:bg-amber-100"
              : "bg-sage-navy text-white hover:bg-sage-navy-deep"
          )}
        >
          {busy ? <Loader2 size={12} className="animate-spin" /> :
            course.isPublished ? <GlobeLock size={12} /> : <Globe size={12} />}
          {course.isPublished ? "Unpublish" : "Publish"}
        </button>
        <Link
          href={`/instructor/courses/${course.id}/content`}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-medium text-gray-700 bg-gray-100 hover:bg-gray-200 transition"
        >
          <Settings size={12} /> Manage Content
        </Link>
        <button
          onClick={handleDelete}
          disabled={busy || course.enrolledCount > 0}
          title={course.enrolledCount > 0 ? "Course has active enrollments — unpublish instead." : "Delete course"}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-medium text-red-700 bg-red-50 hover:bg-red-100 disabled:opacity-40 disabled:cursor-not-allowed transition cursor-pointer ml-auto"
        >
          <Trash2 size={12} /> Delete Course
        </button>
      </div>

      <CourseForm
        initial={{
          title: course.title,
          shortDescription: course.shortDescription ?? "",
          description: course.description ?? "",
          category: course.category ?? "",
          level: (course.level as CourseFormValues["level"]) ?? "BEGINNER",
          price: course.price != null ? String(course.price) : "",
          tags: course.tags ?? "",
          thumbnailUrl: course.thumbnailUrl ?? "",
        }}
        courseId={course.id}
        submitLabel="Save Changes"
        cancelHref="/instructor"
        onSubmit={handleSubmit}
      />
    </section>
  );
}
