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
        "bg-white/5 backdrop-blur-xl border border-white/10 rounded-2xl p-6 relative overflow-hidden",
        className
      )}
    >
      {/* Animated glow background */}
      <div className="absolute -top-12 -right-12 w-40 h-40 bg-gradient-to-br from-[#E5B62E]/20 to-[#22C55E]/10 rounded-full blur-3xl" />

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
              <p className="text-3xl font-bold text-white">{streak}</p>
              <p className="text-xs text-zinc-400">Day Streak</p>
            </div>
          </div>

          <div className="space-y-3">
            {/* XP */}
            <div className="flex items-center gap-2">
              <Zap className="w-4 h-4 text-[#22C55E]" />
              <span className="text-sm text-zinc-300">
                <span className="font-semibold text-[#22C55E]">{xp.toLocaleString()}</span> XP
              </span>
            </div>

            {/* Level */}
            <div className="flex items-center gap-2">
              <TrendingUp className="w-4 h-4 text-[#E5B62E]" />
              <span className="text-sm text-zinc-300">
                Level: <span className="font-semibold text-[#E5B62E]">{level}</span>
              </span>
            </div>
          </div>
        </div>

        {/* Level badge */}
        <div className="flex flex-col items-center">
          <div
            className={cn(
              "w-14 h-14 rounded-xl flex items-center justify-center text-xl font-bold",
              "bg-gradient-to-br from-[#E5B62E]/30 to-[#22C55E]/20 border border-[#E5B62E]/30 text-white"
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
