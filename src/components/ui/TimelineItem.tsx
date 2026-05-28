"use client";

import { TimelineEvent } from "@/lib/data";
import { fadeUp } from "@/lib/utils";
import { motion } from "framer-motion";

interface TimelineItemProps {
  event: TimelineEvent;
  index: number;
}

export default function TimelineItem({ event, index }: TimelineItemProps) {
  const isLeft = index % 2 === 0;

  return (
    <motion.div
      className="relative flex items-center mb-8 sm:mb-12 last:mb-0"
      variants={fadeUp}
      initial="hidden"
      whileInView="visible"
      viewport={{ once: true, margin: "-50px" }}
    >
      {/* Line — left rail on mobile, centered on md+ */}
      <div className="absolute left-2 md:left-1/2 md:-translate-x-1/2 w-px h-full bg-gradient-to-b from-neon-blue/50 to-transparent" />

      {/* Dot */}
      <div className="absolute left-2 -translate-x-1/2 md:left-1/2 w-3 h-3 sm:w-4 sm:h-4 rounded-full bg-neon-blue shadow-glow-blue z-10" />

      {/* Content — full-width stacked on mobile, alternating on md+ */}
      <div
        className={`w-full pl-8 md:w-5/12 md:pl-0 ${
          isLeft ? "md:pr-12 md:text-right" : "md:pl-12 md:ml-auto md:text-left"
        }`}
      >
        <span className="text-neon-blue font-bold text-base sm:text-lg">{event.year}</span>
        <h3 className="text-zinc-900 font-bold text-lg sm:text-xl mt-1">{event.title}</h3>
        <p className="text-zinc-600 mt-2 leading-relaxed text-sm sm:text-base">{event.description}</p>
      </div>
    </motion.div>
  );
}
