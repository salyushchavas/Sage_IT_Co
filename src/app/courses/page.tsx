"use client";

import { useState, useEffect } from "react";
import { motion } from "framer-motion";
import { Loader2, BookOpen } from "lucide-react";
import { getCourses } from "@/lib/api";
import { cn, staggerContainer } from "@/lib/utils";
import CourseCard, { type Course } from "@/components/courses/CourseCard";
import CourseFilter from "@/components/courses/CourseFilter";
import CoursesPreview from "@/components/sections/CoursesPreview";

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

  // The lesson catalogue appears once courses are published (or while someone searches).
  const filtering = search.trim() !== "" || selectedLevel !== "All";
  const showCatalogue = filtering || courses.length > 0;

  return (
    <>
      <section className="pt-28 sm:pt-32 px-4 sm:px-6">
        <div className="mx-auto max-w-7xl">
          <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }}>
            <h1 className="text-3xl sm:text-4xl font-bold text-zinc-900 mb-2">
              Explore{" "}
              <span className="bg-gradient-to-r from-[#1B2A5C] to-[#C87D5C] bg-clip-text text-transparent">
                Courses
              </span>
            </h1>
            <p className="text-zinc-600 text-sm sm:text-base">
              Choose the course you want and apply. Once our team confirms your application,
              we email you a link to register.
            </p>
          </motion.div>
        </div>
      </section>

      <CoursesPreview compact />

      {showCatalogue && (
        <section className="pb-16 sm:pb-20 px-4 sm:px-6">
          <div className="mx-auto max-w-7xl">
            <h2 className="text-2xl sm:text-3xl font-bold text-zinc-900 mb-6">Lessons and materials</h2>

            {/* Filters */}
            <div className="mb-8 sm:mb-10">
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
                <Loader2 className="w-8 h-8 animate-spin text-[#1B2A5C]" />
              </div>
            )}

            {/* Error */}
            {error && (
              <div className="text-center py-20">
                <p className="text-red-700 mb-2">{error}</p>
                <p className="text-zinc-600 text-sm">
                  Make sure the backend is running.
                </p>
              </div>
            )}

            {/* Empty */}
            {!loading && !error && courses.length === 0 && (
              <div className="text-center py-20">
                <BookOpen className="w-12 h-12 text-zinc-500 mx-auto mb-4" />
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
                className="grid gap-5 sm:gap-6 sm:grid-cols-2 lg:grid-cols-3"
              >
                {courses.map((course, i) => (
                  <CourseCard key={course.id} course={course} index={i} />
                ))}
              </motion.div>
            )}
          </div>
        </section>
      )}
    </>
  );
}
