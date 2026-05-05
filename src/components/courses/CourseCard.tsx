"use client";

import Link from "next/link";
import Image from "next/image";
import { motion } from "framer-motion";
import { Star, Clock, Users, BookOpen } from "lucide-react";

export interface Course {
  id: number;
  title: string;
  shortDescription: string;
  level: "BEGINNER" | "INTERMEDIATE" | "ADVANCED";
  price: number;
  isFree: boolean;
  durationHours: number;
  category: string;
  rating: number;
  ratingsCount: number;
  lessonsCount: number;
  enrolledCount: number;
  thumbnailUrl: string | null;
  instructor: { id: number; fullName: string; avatarUrl?: string | null };
}

interface CourseCardProps {
  course: Course;
  index?: number;
}

const levelConfig: Record<Course["level"], { color: string; bg: string }> = {
  BEGINNER: { color: "text-cyan-400", bg: "bg-cyan-500/20 border-cyan-500/30" },
  INTERMEDIATE: { color: "text-amber-400", bg: "bg-amber-500/20 border-amber-500/30" },
  ADVANCED: { color: "text-red-400", bg: "bg-red-500/20 border-red-500/30" },
};

export default function CourseCard({ course, index = 0 }: CourseCardProps) {
  const lvl = levelConfig[course.level];

  return (
    <motion.div
      initial={{ opacity: 0, y: 24 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, delay: index * 0.08 }}
    >
      <Link href={`/courses/${course.id}`} className="block group">
        <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden transition-all duration-300 hover:border-[#0F5132]/30 hover:shadow-[0_0_30px_rgba(15,81,50,0.08)]">
          {/* Header / Thumbnail */}
          <div className="relative h-44 bg-gradient-to-br from-[#D4A017]/30 to-[#0F5132]/20 overflow-hidden">
            {course.thumbnailUrl ? (
              <Image
                src={course.thumbnailUrl}
                alt={course.title}
                fill
                className="object-cover group-hover:scale-105 transition-transform duration-500"
              />
            ) : (
              <div className="absolute inset-0 flex items-center justify-center">
                <BookOpen className="w-12 h-12 text-zinc-900/20" />
              </div>
            )}
            {/* Level badge */}
            <span
              className={`absolute top-3 left-3 text-xs font-semibold px-2.5 py-1 rounded-full border ${lvl.bg} ${lvl.color}`}
            >
              {course.level}
            </span>
            {/* Price */}
            <span className="absolute top-3 right-3 text-sm font-bold px-3 py-1 rounded-full bg-black/60 backdrop-blur text-zinc-900">
              {course.isFree ? "Free" : `$${course.price}`}
            </span>
          </div>

          {/* Body */}
          <div className="p-5 space-y-3">
            <p className="text-xs font-medium text-[#0F5132] uppercase tracking-wider">
              {course.category}
            </p>
            <h3 className="text-zinc-900 font-semibold text-lg leading-snug line-clamp-2 group-hover:text-[#0F5132] transition-colors">
              {course.title}
            </h3>
            <p className="text-zinc-600 text-sm line-clamp-2">
              {course.shortDescription}
            </p>

            {/* Meta row */}
            <div className="flex items-center gap-4 text-xs text-zinc-500 pt-1">
              <span className="flex items-center gap-1">
                <Clock className="w-3.5 h-3.5" />
                {course.durationHours}h
              </span>
              <span className="flex items-center gap-1">
                <BookOpen className="w-3.5 h-3.5" />
                {course.lessonsCount} lessons
              </span>
              <span className="flex items-center gap-1">
                <Users className="w-3.5 h-3.5" />
                {course.enrolledCount}
              </span>
            </div>

            {/* Footer */}
            <div className="flex items-center justify-between pt-3 border-t border-zinc-200">
              <span className="text-zinc-600 text-sm">
                {course.instructor.fullName}
              </span>
              <span className="flex items-center gap-1 text-amber-400 text-sm font-medium">
                <Star className="w-3.5 h-3.5 fill-amber-400" />
                {course.rating.toFixed(1)}
                <span className="text-zinc-500 font-normal">
                  ({course.ratingsCount})
                </span>
              </span>
            </div>
          </div>
        </div>
      </Link>
    </motion.div>
  );
}
