"use client";

import { fadeUp } from "@/lib/utils";
import { Service } from "@/lib/data";
import { motion } from "framer-motion";
import GlassCard from "./GlassCard";

interface ServiceCardProps {
  service: Service;
  index: number;
  detailed?: boolean;
}

export default function ServiceCard({ service, index, detailed = false }: ServiceCardProps) {
  return (
    <motion.div variants={fadeUp} custom={index}>
      <GlassCard hover3D glowColor={service.color} className="p-8 h-full glow-border">
        <div
          className="text-4xl mb-4 w-16 h-16 rounded-2xl flex items-center justify-center"
          style={{ background: `${service.color}15` }}
        >
          {service.icon}
        </div>
        <h3 className="text-xl font-bold text-white mb-3">{service.title}</h3>
        <p className="text-zinc-400 leading-relaxed mb-4">{service.description}</p>
        {detailed && (
          <ul className="space-y-2">
            {service.features.map((f) => (
              <li key={f} className="flex items-center gap-2 text-sm text-zinc-300">
                <span className="w-1.5 h-1.5 rounded-full" style={{ background: service.color }} />
                {f}
              </li>
            ))}
          </ul>
        )}
        {!detailed && (
          <div className="flex flex-wrap gap-2 mt-4">
            {service.features.slice(0, 2).map((f) => (
              <span key={f} className="text-xs px-3 py-1 rounded-full bg-white/5 text-zinc-300 border border-white/10">
                {f}
              </span>
            ))}
          </div>
        )}
      </GlassCard>
    </motion.div>
  );
}
