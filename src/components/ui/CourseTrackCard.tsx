"use client";

import { type CourseTrack, applyHref } from "@/lib/course-tracks";
import { popIn } from "@/lib/utils";
import { motion } from "framer-motion";
import Link from "next/link";
import GlassCard from "./GlassCard";

/** Same palette rotation the resource cards use. */
const COLORS = ["#1B2A5C", "#C87D5C", "#0F1F44"];

export default function CourseTrackCard({ track, index }: { track: CourseTrack; index: number }) {
  const color = COLORS[index % COLORS.length];
  const Icon = track.icon;
  return (
    <motion.div variants={popIn} custom={index} className="h-full">
      <Link href={applyHref(track.name)} className="block h-full rounded-2xl">
        <GlassCard hover3D glowColor={color} className="p-6 h-full glow-border transition-all duration-500 hover:-translate-y-1 hover:shadow-glow-blue">
          <div
            className="mb-4 w-12 h-12 rounded-xl flex items-center justify-center"
            style={{ backgroundColor: `${color}15`, border: `1px solid ${color}30` }}
          >
            <Icon className="w-6 h-6" style={{ color }} />
          </div>
          <h3 className="text-lg font-bold text-zinc-900 mb-2">{track.name}</h3>
          <p className="text-zinc-600 text-sm leading-relaxed">{track.description}</p>
          <span className="inline-block mt-4 text-sm font-medium text-sage-navy">
            Apply <span aria-hidden>&rarr;</span>
          </span>
        </GlassCard>
      </Link>
    </motion.div>
  );
}
