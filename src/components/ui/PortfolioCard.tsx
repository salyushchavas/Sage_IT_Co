"use client";

import { PortfolioItem } from "@/lib/data";
import { popIn } from "@/lib/utils";
import { motion } from "framer-motion";
import GlassCard from "./GlassCard";

interface PortfolioCardProps {
  item: PortfolioItem;
  index: number;
}

export default function PortfolioCard({ item, index }: PortfolioCardProps) {
  return (
    <motion.div variants={popIn} custom={index} className="h-full">
      <GlassCard hover3D glowColor="#0F1F44" className="p-0 overflow-hidden h-full transition-transform duration-500 hover:-translate-y-1">
        {/* Gradient header — animated drift */}
        <div className="h-44 sm:h-48 relative overflow-hidden flex items-center justify-center">
          <div className="absolute inset-0 bg-gradient-to-br from-sage-green/25 via-sage-gold-light/25 to-sage-gold/25 bg-[length:200%_200%] animate-gradient-shift" />
          <motion.span
            className="relative text-5xl sm:text-6xl opacity-40"
            whileHover={{ scale: 1.18, rotate: 8 }}
            transition={{ type: "spring", stiffness: 220, damping: 14 }}
          >
            {item.category === "Cloud" && "☁️"}
            {item.category === "Data" && "📊"}
            {item.category === "Security" && "🛡️"}
            {item.category === "Web" && "🌐"}
            {item.category === "AI" && "🧠"}
          </motion.span>
          <div className="absolute top-3 sm:top-4 right-3 sm:right-4">
            <span className="text-xs px-3 py-1 rounded-full bg-white/85 backdrop-blur-sm text-zinc-900 border border-zinc-300">
              {item.category}
            </span>
          </div>
        </div>
        <div className="p-5 sm:p-6">
          <h3 className="text-base sm:text-lg font-bold text-zinc-900 mb-2">{item.title}</h3>
          <p className="text-zinc-600 text-sm leading-relaxed mb-4">{item.description}</p>
          <div className="flex flex-wrap gap-1.5 sm:gap-2 mb-4">
            {item.tags.map((tag) => (
              <span key={tag} className="text-xs px-2 py-1 rounded bg-white/60 text-zinc-600 border border-zinc-200 transition-colors hover:border-neon-blue/40 hover:text-zinc-900">
                {tag}
              </span>
            ))}
          </div>
          <div className="pt-4 border-t border-zinc-200">
            <p className="text-neon-cyan text-sm font-medium">{item.result}</p>
          </div>
        </div>
      </GlassCard>
    </motion.div>
  );
}
