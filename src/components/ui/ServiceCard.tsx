"use client";

import { popIn } from "@/lib/utils";
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
    <motion.div variants={popIn} custom={index} className="h-full">
      <GlassCard hover3D glowColor={service.color} className="p-6 sm:p-8 h-full glow-border transition-all duration-500 hover:-translate-y-1 hover:shadow-glow-blue">
        <motion.div
          className="text-3xl sm:text-4xl mb-3 sm:mb-4 w-14 h-14 sm:w-16 sm:h-16 rounded-2xl flex items-center justify-center"
          style={{ background: `${service.color}15` }}
          whileHover={{ rotate: [0, -8, 8, -4, 0], scale: 1.08 }}
          transition={{ duration: 0.6, ease: "easeInOut" }}
        >
          {service.icon}
        </motion.div>
        <h3 className="text-lg sm:text-xl font-bold text-zinc-900 mb-2 sm:mb-3">{service.title}</h3>
        <p className="text-zinc-600 text-sm sm:text-base leading-relaxed mb-4">{service.description}</p>
        {detailed && (
          <ul className="space-y-2">
            {service.features.map((f) => (
              <li key={f} className="flex items-center gap-2 text-sm text-zinc-500">
                <span className="w-1.5 h-1.5 rounded-full" style={{ background: service.color }} />
                {f}
              </li>
            ))}
          </ul>
        )}
        {!detailed && (
          <div className="flex flex-wrap gap-2 mt-4">
            {service.features.slice(0, 2).map((f) => (
              <span key={f} className="text-xs px-3 py-1 rounded-full bg-white/60 text-zinc-500 border border-zinc-200">
                {f}
              </span>
            ))}
          </div>
        )}
      </GlassCard>
    </motion.div>
  );
}
