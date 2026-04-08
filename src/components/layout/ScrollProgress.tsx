"use client";

import { useScrollProgress } from "@/hooks/useScrollProgress";
import { motion } from "framer-motion";

export default function ScrollProgress() {
  const progress = useScrollProgress();

  return (
    <motion.div
      className="fixed top-0 left-0 right-0 h-[3px] z-[60] bg-gradient-to-r from-neon-blue via-neon-violet to-neon-cyan origin-left"
      style={{ scaleX: progress }}
    />
  );
}
