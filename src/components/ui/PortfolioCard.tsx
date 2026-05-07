"use client";

import { PortfolioItem } from "@/lib/data";
import { fadeUp } from "@/lib/utils";
import { motion } from "framer-motion";
import GlassCard from "./GlassCard";

interface PortfolioCardProps {
  item: PortfolioItem;
  index: number;
}

export default function PortfolioCard({ item, index }: PortfolioCardProps) {
  return (
    <motion.div variants={fadeUp} custom={index}>
      <GlassCard hover3D glowColor="#0F1F44" className="p-0 overflow-hidden h-full">
        {/* Gradient header */}
        <div className="h-48 relative bg-gradient-to-br from-sage-green/20 to-sage-gold/20 flex items-center justify-center">
          <span className="text-6xl opacity-30">
            {item.category === "Cloud" && "☁️"}
            {item.category === "Data" && "📊"}
            {item.category === "Security" && "🛡️"}
            {item.category === "Web" && "🌐"}
            {item.category === "AI" && "🧠"}
          </span>
          <div className="absolute top-4 right-4">
            <span className="text-xs px-3 py-1 rounded-full bg-white/80 text-zinc-900 border border-zinc-300">
              {item.category}
            </span>
          </div>
        </div>
        <div className="p-6">
          <h3 className="text-lg font-bold text-zinc-900 mb-2">{item.title}</h3>
          <p className="text-zinc-600 text-sm leading-relaxed mb-4">{item.description}</p>
          <div className="flex flex-wrap gap-2 mb-4">
            {item.tags.map((tag) => (
              <span key={tag} className="text-xs px-2 py-1 rounded bg-white/60 text-zinc-600 border border-zinc-200">
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
