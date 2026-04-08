"use client";

import { motion } from "framer-motion";
import { BookOpen } from "lucide-react";
import CourseCard, { type Course } from "./CourseCard";

interface CourseGridProps {
  courses: Course[];
  progressMap?: Record<number, number>;
}

export default function CourseGrid({ courses, progressMap }: CourseGridProps) {
  if (courses.length === 0) {
    return (
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="flex flex-col items-center justify-center py-24 text-center"
      >
        <BookOpen className="w-16 h-16 text-zinc-700 mb-4" />
        <h3 className="text-white text-xl font-semibold mb-2">No courses found</h3>
        <p className="text-zinc-400 max-w-md">
          Try adjusting your filters or search terms to find what you&apos;re looking for.
        </p>
      </motion.div>
    );
  }

  return (
    <motion.div
      initial="hidden"
      animate="visible"
      variants={{
        hidden: {},
        visible: { transition: { staggerChildren: 0.08 } },
      }}
      className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6"
    >
      {courses.map((course, i) => (
        <CourseCard
          key={course.id}
          course={course}
          index={i}
        />
      ))}
      {/* progressMap is available for parent components that need enrollment progress */}
      {progressMap && <span className="hidden" />}
    </motion.div>
  );
}
