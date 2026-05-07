"use client";

import { fadeUp } from "@/lib/utils";
import { motion } from "framer-motion";

interface SectionHeadingProps {
  label?: string;
  title: string;
  description?: string;
  align?: "left" | "center";
}

export default function SectionHeading({ label, title, description, align = "center" }: SectionHeadingProps) {
  return (
    <motion.div
      className={`mb-12 sm:mb-16 ${align === "center" ? "text-center" : "text-left"}`}
      variants={fadeUp}
      initial="hidden"
      whileInView="visible"
      viewport={{ once: true, margin: "-50px" }}
    >
      {label && (
        <motion.span
          className="inline-block text-neon-blue text-xs sm:text-sm font-semibold tracking-widest uppercase mb-3 sm:mb-4 relative"
          initial={{ opacity: 0, letterSpacing: "0.1em" }}
          whileInView={{ opacity: 1, letterSpacing: "0.18em" }}
          viewport={{ once: true }}
          transition={{ duration: 0.7, ease: [0.22, 1, 0.36, 1] }}
        >
          {label}
        </motion.span>
      )}
      <h2 className="text-2xl sm:text-3xl md:text-5xl font-bold text-zinc-900 mb-3 sm:mb-4 text-balance">
        {title}
      </h2>
      {description && (
        <motion.p
          className="text-zinc-600 max-w-2xl mx-auto text-base sm:text-lg leading-relaxed"
          initial={{ opacity: 0, y: 12 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.6, delay: 0.15, ease: "easeOut" }}
        >
          {description}
        </motion.p>
      )}
      <motion.div
        className={`relative mt-5 sm:mt-6 h-1 w-20 rounded-full overflow-hidden ${
          align === "center" ? "mx-auto" : ""
        }`}
        initial={{ scaleX: 0, opacity: 0 }}
        whileInView={{ scaleX: 1, opacity: 1 }}
        viewport={{ once: true, margin: "-50px" }}
        transition={{ duration: 0.9, delay: 0.2, ease: [0.22, 1, 0.36, 1] }}
        style={{ transformOrigin: align === "center" ? "center" : "left" }}
      >
        <span className="absolute inset-0 bg-gradient-to-r from-neon-blue via-neon-violet to-neon-blue bg-[length:200%_100%] animate-gradient-shift" />
      </motion.div>
    </motion.div>
  );
}
