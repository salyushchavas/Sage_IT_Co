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
      ? "from-[#06b6d4] to-[#00d4ff]"
      : clamped < 70
        ? "from-[#00d4ff] to-[#7c3aed]"
        : "from-[#7c3aed] to-[#00d4ff]";

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
          <span className="text-xs text-zinc-400">{label}</span>
          <span className="text-xs font-semibold text-white">{clamped}%</span>
        </div>
      )}
      <div className="h-2 rounded-full bg-white/10 overflow-hidden">
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
