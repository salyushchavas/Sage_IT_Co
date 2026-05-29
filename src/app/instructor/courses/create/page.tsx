"use client";

import { useEffect } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChevronLeft, BookPlus } from "lucide-react";
import { createCourse } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useToast } from "@/components/ui/Toast";
import { CourseForm, type CourseFormValues } from "@/components/instructor/CourseForm";

const INITIAL: CourseFormValues = {
  title: "",
  shortDescription: "",
  description: "",
  category: "",
  level: "BEGINNER",
  price: "",
  tags: "",
  thumbnailUrl: "",
};

export default function CreateInstructorCoursePage() {
  const router = useRouter();
  const { toast } = useToast();
  const { user, isLoading: authLoading } = useAuth();
  const role = user?.role?.toUpperCase();

  useEffect(() => {
    if (authLoading) return;
    if (!user) router.push("/login?redirect=/instructor/courses/create");
    else if (role !== "INSTRUCTOR" && role !== "ADMIN" && role !== "SYSTEM_ADMIN") router.push("/dashboard");
  }, [user, authLoading, role, router]);

  const handleSubmit = async (values: CourseFormValues) => {
    const created = await createCourse({
      title: values.title.trim(),
      shortDescription: values.shortDescription.trim() || undefined,
      description: values.description.trim(),
      category: values.category.trim(),
      level: values.level,
      price: Number(values.price),
      tags: values.tags.trim() || undefined,
    }) as { id: number };

    toast("success", "Course created — now add modules and lessons.");
    router.push(`/instructor/courses/${created.id}/edit`);
  };

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
          <BookPlus size={20} />
        </div>
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Create a New Course</h1>
          <p className="text-xs text-gray-500">Start as a draft — publish once you&apos;ve added content.</p>
        </div>
      </div>

      <CourseForm
        initial={INITIAL}
        submitLabel="Create Course (as Draft)"
        cancelHref="/instructor"
        onSubmit={handleSubmit}
      />
    </section>
  );
}
