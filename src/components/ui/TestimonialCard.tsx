"use client";

import { Testimonial } from "@/lib/data";
import GlassCard from "./GlassCard";

export default function TestimonialCard({ testimonial }: { testimonial: Testimonial }) {
  return (
    <GlassCard className="p-8 h-full" glowColor="#C87D5C">
      <div className="flex items-center gap-4 mb-6">
        <div className="w-12 h-12 rounded-full bg-gradient-to-br from-neon-blue to-neon-violet flex items-center justify-center text-zinc-900 font-bold text-sm">
          {testimonial.avatar}
        </div>
        <div>
          <p className="text-zinc-900 font-semibold">{testimonial.name}</p>
          <p className="text-zinc-600 text-sm">
            {testimonial.role}, {testimonial.company}
          </p>
        </div>
      </div>
      <p className="text-zinc-500 leading-relaxed italic">&ldquo;{testimonial.content}&rdquo;</p>
    </GlassCard>
  );
}
