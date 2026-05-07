"use client";

import { motion } from "framer-motion";
import { cn } from "@/lib/utils";

interface ProgressBarProps {
  value: number;
  label?: string;
  className?: string;
}

export default function ProgressBar({ value, label, className }: ProgressBarProps) {
  const clamped = Math.min(100, Math.max(0, value));

  const gradient =
    clamped < 30
      ? "from-[#0F1F44] to-[#1B2A5C]"
      : clamped < 70
        ? "from-[#1B2A5C] to-[#C87D5C]"
        : "from-[#C87D5C] to-[#1B2A5C]";

  const glowColor =
    clamped < 30
      ? "rgba(6,182,212,0.4)"
      : clamped < 70
        ? "rgba(27,42,92,0.4)"
        : "rgba(200,125,92,0.4)";

  return (
    <div className={cn("w-full", className)}>
      {label && (
        <div className="flex items-center justify-between mb-1.5">
          <span className="text-xs text-zinc-600">{label}</span>
          <span className="text-xs font-semibold text-zinc-900">{clamped}%</span>
        </div>
      )}
      <div className="h-2 rounded-full bg-white/80 overflow-hidden">
        <motion.div
          className={cn("h-full rounded-full bg-gradient-to-r", gradient)}
          initial={{ width: 0 }}
          animate={{ width: `${clamped}%` }}
          transition={{ duration: 1, ease: "easeOut" }}
          style={{ boxShadow: `0 0 12px ${glowColor}` }}
        />
      </div>
    </div>
  );
}
