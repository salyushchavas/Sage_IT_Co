"use client";

import { motion } from "framer-motion";
import { Search } from "lucide-react";

interface CourseFilterProps {
  selectedLevel: string;
  onLevelChange: (level: string) => void;
  search: string;
  onSearchChange: (value: string) => void;
}

const levels = ["All", "Beginner", "Intermediate", "Advanced"] as const;

export default function CourseFilter({
  selectedLevel,
  onLevelChange,
  search,
  onSearchChange,
}: CourseFilterProps) {
  return (
    <div className="flex flex-col sm:flex-row items-start sm:items-center gap-4">
      {/* Level pills */}
      <div className="flex items-center gap-2 flex-wrap">
        {levels.map((level) => {
          const isActive = selectedLevel === level;
          return (
            <motion.button
              key={level}
              whileHover={{ scale: 1.05 }}
              whileTap={{ scale: 0.95 }}
              onClick={() => onLevelChange(level)}
              className={`px-4 py-2 rounded-full text-sm font-medium transition-all duration-200 border ${
                isActive
                  ? "bg-[#22C55E]/20 border-[#22C55E]/50 text-[#22C55E] shadow-[0_0_12px_rgba(0,212,255,0.15)]"
                  : "bg-white/60 border-zinc-200 text-zinc-600 hover:text-zinc-900 hover:border-zinc-300"
              }`}
            >
              {level}
            </motion.button>
          );
        })}
      </div>

      {/* Search input */}
      <div className="relative w-full sm:w-72 ml-auto">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-zinc-500" />
        <input
          type="text"
          value={search}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Search courses..."
          className="w-full pl-10 pr-4 py-2.5 rounded-xl bg-white/60 backdrop-blur-xl border border-zinc-200 text-zinc-900 placeholder-zinc-500 text-sm outline-none focus:border-[#22C55E]/40 focus:shadow-[0_0_12px_rgba(0,212,255,0.1)] transition-all"
        />
      </div>
    </div>
  );
}
