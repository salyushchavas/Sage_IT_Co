"use client";

import { useState, useEffect } from "react";
import { motion } from "framer-motion";
import { Loader2, BookOpen } from "lucide-react";
import { getCourses } from "@/lib/api";
import { cn, staggerContainer } from "@/lib/utils";
import CourseCard, { type Course } from "@/components/courses/CourseCard";
import CourseFilter from "@/components/courses/CourseFilter";

export default function CoursesPage() {
  const [selectedLevel, setSelectedLevel] = useState("All");
  const [search, setSearch] = useState("");
  const [courses, setCourses] = useState<Course[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    const fetchCourses = async () => {
      setLoading(true);
      setError("");
      try {
        const params: Record<string, string> = {};
        if (selectedLevel !== "All") params.level = selectedLevel.toUpperCase();
        if (search.trim()) params.search = search.trim();
        const data = await getCourses(params);
        setCourses(data as Course[]);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to load courses");
        setCourses([]);
      } finally {
        setLoading(false);
      }
    };
    fetchCourses();
  }, [selectedLevel, search]);

  return (
    <section className="min-h-screen pt-32 pb-20 px-6">
      <div className="mx-auto max-w-7xl">
        {/* Header */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          className="mb-10"
        >
          <h1 className="text-4xl font-bold text-white mb-2">
            Explore{" "}
            <span className="bg-gradient-to-r from-[#00d4ff] to-[#7c3aed] bg-clip-text text-transparent">
              Courses
            </span>
          </h1>
          <p className="text-zinc-400">
            Find the perfect course to advance your skills.
          </p>
        </motion.div>

        {/* Filters */}
        <div className="mb-10">
          <CourseFilter
            selectedLevel={selectedLevel}
            onLevelChange={setSelectedLevel}
            search={search}
            onSearchChange={setSearch}
          />
        </div>

        {/* Loading */}
        {loading && (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-8 h-8 animate-spin text-[#00d4ff]" />
          </div>
        )}

        {/* Error */}
        {error && (
          <div className="text-center py-20">
            <p className="text-red-400 mb-2">{error}</p>
            <p className="text-zinc-600 text-sm">
              Make sure the backend is running.
            </p>
          </div>
        )}

        {/* Empty */}
        {!loading && !error && courses.length === 0 && (
          <div className="text-center py-20">
            <BookOpen className="w-12 h-12 text-zinc-700 mx-auto mb-4" />
            <p className="text-zinc-500">
              No courses found. Try adjusting your filters.
            </p>
          </div>
        )}

        {/* Grid */}
        {!loading && !error && courses.length > 0 && (
          <motion.div
            variants={staggerContainer}
            initial="hidden"
            animate="visible"
            className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3"
          >
            {courses.map((course, i) => (
              <CourseCard key={course.id} course={course} index={i} />
            ))}
          </motion.div>
        )}
      </div>
    </section>
  );
}
