"use client";

import { useScrollProgress } from "@/hooks/useScrollProgress";
import { motion } from "framer-motion";

export default function ScrollProgress() {
  const progress = useScrollProgress();

  return (
    <motion.div
      className="fixed top-0 left-0 right-0 h-[3px] z-[60] origin-left bg-gradient-to-r from-sage-green via-sage-gold to-sage-green bg-[length:200%_100%] animate-gradient-shift shadow-[0_0_12px_rgba(200,125,92,0.5)]"
      style={{ scaleX: progress }}
    />
  );
}
