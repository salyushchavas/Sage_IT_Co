"use client";

import { fadeUp } from "@/lib/utils";
import { motion, Variants } from "framer-motion";
import { ReactNode } from "react";

interface MotionDivProps {
  children: ReactNode;
  className?: string;
  variants?: Variants;
  delay?: number;
}

export default function MotionDiv({ children, className, variants = fadeUp, delay }: MotionDivProps) {
  return (
    <motion.div
      className={className}
      variants={delay ? { ...variants, visible: { ...(variants.visible as object), transition: { delay, duration: 0.6, ease: "easeOut" } } } : variants}
      initial="hidden"
      whileInView="visible"
      viewport={{ once: true, margin: "-50px" }}
    >
      {children}
    </motion.div>
  );
}
