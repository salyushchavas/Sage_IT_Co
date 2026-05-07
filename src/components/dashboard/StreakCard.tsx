"use client";

import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { Zap, TrendingUp } from "lucide-react";

interface StreakCardProps {
  streak: number;
  xp: number;
  level: string;
  className?: string;
}

export default function StreakCard({ streak, xp, level, className }: StreakCardProps) {
  return (
    <div
      className={cn(
        "bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-6 relative overflow-hidden",
        className
      )}
    >
      {/* Animated glow background */}
      <div className="absolute -top-12 -right-12 w-40 h-40 bg-gradient-to-br from-[#C87D5C]/20 to-[#1B2A5C]/10 rounded-full blur-3xl" />

      <div className="relative z-10 flex items-start justify-between">
        {/* Fire streak */}
        <div>
          <div className="flex items-center gap-3 mb-4">
            <motion.span
              className="text-4xl"
              animate={{ scale: [1, 1.15, 1], rotate: [0, 5, -5, 0] }}
              transition={{ duration: 2, repeat: Infinity, ease: "easeInOut" }}
            >
              🔥
            </motion.span>
            <div>
              <p className="text-3xl font-bold text-zinc-900">{streak}</p>
              <p className="text-xs text-zinc-600">Day Streak</p>
            </div>
          </div>

          <div className="space-y-3">
            {/* XP */}
            <div className="flex items-center gap-2">
              <Zap className="w-4 h-4 text-[#1B2A5C]" />
              <span className="text-sm text-zinc-500">
                <span className="font-semibold text-[#1B2A5C]">{xp.toLocaleString()}</span> XP
              </span>
            </div>

            {/* Level */}
            <div className="flex items-center gap-2">
              <TrendingUp className="w-4 h-4 text-[#C87D5C]" />
              <span className="text-sm text-zinc-500">
                Level: <span className="font-semibold text-[#C87D5C]">{level}</span>
              </span>
            </div>
          </div>
        </div>

        {/* Level badge */}
        <div className="flex flex-col items-center">
          <div
            className={cn(
              "w-14 h-14 rounded-xl flex items-center justify-center text-xl font-bold",
              "bg-gradient-to-br from-[#C87D5C]/30 to-[#1B2A5C]/20 border border-[#C87D5C]/30 text-zinc-900"
            )}
          >
            {level.charAt(0)}
          </div>
          <span className="text-[10px] text-zinc-500 mt-1.5 uppercase tracking-wider">Rank</span>
        </div>
      </div>
    </div>
  );
}
