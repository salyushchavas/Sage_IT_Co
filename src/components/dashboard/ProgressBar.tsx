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
      ? "from-[#14653F] to-[#22C55E]"
      : clamped < 70
        ? "from-[#22C55E] to-[#E5B62E]"
        : "from-[#E5B62E] to-[#22C55E]";

  const glowColor =
    clamped < 30
      ? "rgba(6,182,212,0.4)"
      : clamped < 70
        ? "rgba(0,212,255,0.4)"
        : "rgba(124,58,237,0.4)";

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
