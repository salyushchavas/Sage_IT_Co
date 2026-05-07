"use client";

import { Testimonial } from "@/lib/data";
import GlassCard from "./GlassCard";

export default function TestimonialCard({ testimonial }: { testimonial: Testimonial }) {
  return (
    <GlassCard className="p-6 sm:p-8 h-full transition-transform duration-500 hover:-translate-y-1" glowColor="#C87D5C">
      {/* Decorative quote mark — animated gradient on hover */}
      <div
        aria-hidden
        className="absolute top-3 right-4 text-5xl sm:text-6xl font-serif leading-none opacity-10 select-none transition-opacity duration-500 group-hover:opacity-25"
        style={{
          backgroundImage: "linear-gradient(135deg, #1B2A5C, #C87D5C)",
          WebkitBackgroundClip: "text",
          backgroundClip: "text",
          color: "transparent",
        }}
      >
        &ldquo;
      </div>
      <div className="flex items-center gap-3 sm:gap-4 mb-5 sm:mb-6 relative">
        <div className="w-11 h-11 sm:w-12 sm:h-12 rounded-full bg-gradient-to-br from-neon-blue to-neon-violet flex items-center justify-center text-white font-bold text-sm shadow-glow-blue">
          {testimonial.avatar}
        </div>
        <div>
          <p className="text-zinc-900 font-semibold text-sm sm:text-base">{testimonial.name}</p>
          <p className="text-zinc-600 text-xs sm:text-sm">
            {testimonial.role}, {testimonial.company}
          </p>
        </div>
      </div>
      <p className="text-zinc-500 leading-relaxed italic text-sm sm:text-base relative">
        &ldquo;{testimonial.content}&rdquo;
      </p>
    </GlassCard>
  );
}
