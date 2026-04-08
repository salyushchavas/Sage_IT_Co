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
      className="relative flex items-center mb-12 last:mb-0"
      variants={fadeUp}
      initial="hidden"
      whileInView="visible"
      viewport={{ once: true, margin: "-50px" }}
    >
      {/* Line */}
      <div className="absolute left-1/2 -translate-x-1/2 w-px h-full bg-gradient-to-b from-neon-blue/50 to-transparent" />

      {/* Dot */}
      <div className="absolute left-1/2 -translate-x-1/2 w-4 h-4 rounded-full bg-neon-blue shadow-glow-blue z-10" />

      {/* Content */}
      <div className={`w-5/12 ${isLeft ? "pr-12 text-right" : "pl-12 ml-auto text-left"}`}>
        <span className="text-neon-blue font-bold text-lg">{event.year}</span>
        <h3 className="text-white font-bold text-xl mt-1">{event.title}</h3>
        <p className="text-zinc-400 mt-2 leading-relaxed">{event.description}</p>
      </div>
    </motion.div>
  );
}
