"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { BookOpen } from "lucide-react";
import { cn, staggerContainer, fadeUp } from "@/lib/utils";

interface Enrollment {
  id: number;
  courseId: number;
  courseTitle: string;
  courseThumbnailUrl?: string | null;
  courseCategory?: string;
  progress?: number;
  enrolledAt: string;
}

interface EnrolledCoursesProps {
  enrollments: Enrollment[];
}

const colors = [
  "from-[#1B2A5C]/40 to-[#C87D5C]/30",
  "from-[#C87D5C]/40 to-[#0F1F44]/30",
  "from-[#0F1F44]/40 to-[#1B2A5C]/30",
  "from-[#C87D5C]/30 to-[#1B2A5C]/40",
];

export default function EnrolledCourses({ enrollments }: EnrolledCoursesProps) {
  if (enrollments.length === 0) {
    return (
      <div className="text-center py-16">
        <BookOpen className="w-12 h-12 text-zinc-600 mx-auto mb-4" />
        <p className="text-zinc-600 text-sm">You haven&apos;t enrolled in any courses yet.</p>
        <Link
          href="/courses"
          className="inline-block mt-4 text-sm text-[#1B2A5C] hover:underline"
        >
          Browse courses
        </Link>
      </div>
    );
  }

  return (
    <motion.div
      variants={staggerContainer}
      initial="hidden"
      animate="visible"
      className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3"
    >
      {enrollments.map((enrollment, i) => (
        <motion.div key={enrollment.id} variants={fadeUp}>
          <Link href={`/courses/${enrollment.courseId}`} className="block group">
            <div
              className={cn(
                "bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden",
                "transition-all duration-300 hover:border-[#1B2A5C]/30 hover:shadow-[0_0_30px_rgba(27,42,92,0.08)]"
              )}
            >
              {/* Mini thumbnail */}
              <div
                className={cn(
                  "h-24 bg-gradient-to-br flex items-center justify-center",
                  colors[i % colors.length]
                )}
              >
                <span className="text-3xl font-bold text-zinc-900/60">
                  {enrollment.courseTitle.charAt(0)}
                </span>
              </div>

              <div className="p-4 space-y-2">
                <h3 className="text-zinc-900 font-semibold text-sm line-clamp-1 group-hover:text-[#1B2A5C] transition-colors">
                  {enrollment.courseTitle}
                </h3>
                {enrollment.courseCategory && (
                  <p className="text-xs text-[#1B2A5C]/70 uppercase tracking-wider">
                    {enrollment.courseCategory}
                  </p>
                )}

                {/* Progress bar */}
                {typeof enrollment.progress === "number" && (
                  <div className="pt-1">
                    <div className="flex items-center justify-between text-xs text-zinc-500 mb-1">
                      <span>Progress</span>
                      <span>{enrollment.progress}%</span>
                    </div>
                    <div className="h-1.5 rounded-full bg-white/80 overflow-hidden">
                      <div
                        className="h-full rounded-full bg-gradient-to-r from-[#1B2A5C] to-[#C87D5C] transition-all duration-500"
                        style={{ width: `${enrollment.progress}%` }}
                      />
                    </div>
                  </div>
                )}

                <p className="text-xs text-zinc-600">
                  Enrolled {new Date(enrollment.enrolledAt).toLocaleDateString()}
                </p>
              </div>
            </div>
          </Link>
        </motion.div>
      ))}
    </motion.div>
  );
}
