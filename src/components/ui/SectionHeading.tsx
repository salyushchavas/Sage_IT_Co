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
      className={`mb-16 ${align === "center" ? "text-center" : "text-left"}`}
      variants={fadeUp}
      initial="hidden"
      whileInView="visible"
      viewport={{ once: true, margin: "-50px" }}
    >
      {label && (
        <span className="inline-block text-neon-blue text-sm font-semibold tracking-widest uppercase mb-4">
          {label}
        </span>
      )}
      <h2 className="text-3xl md:text-5xl font-bold text-zinc-900 mb-4">
        {title}
      </h2>
      {description && (
        <p className="text-zinc-600 max-w-2xl mx-auto text-lg leading-relaxed">
          {description}
        </p>
      )}
      <div className={`mt-6 h-1 w-20 bg-gradient-to-r from-neon-blue to-neon-violet rounded-full ${align === "center" ? "mx-auto" : ""}`} />
    </motion.div>
  );
}
