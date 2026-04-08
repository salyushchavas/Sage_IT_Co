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
      <GlassCard hover3D glowColor="#06b6d4" className="p-0 overflow-hidden h-full">
        {/* Gradient header */}
        <div className="h-48 relative bg-gradient-to-br from-neon-blue/20 via-neon-violet/20 to-neon-cyan/20 flex items-center justify-center">
          <span className="text-6xl opacity-30">
            {item.category === "Cloud" && "☁️"}
            {item.category === "Data" && "📊"}
            {item.category === "Security" && "🛡️"}
            {item.category === "Web" && "🌐"}
            {item.category === "AI" && "🧠"}
          </span>
          <div className="absolute top-4 right-4">
            <span className="text-xs px-3 py-1 rounded-full bg-white/10 text-white border border-white/20">
              {item.category}
            </span>
          </div>
        </div>
        <div className="p-6">
          <h3 className="text-lg font-bold text-white mb-2">{item.title}</h3>
          <p className="text-zinc-400 text-sm leading-relaxed mb-4">{item.description}</p>
          <div className="flex flex-wrap gap-2 mb-4">
            {item.tags.map((tag) => (
              <span key={tag} className="text-xs px-2 py-1 rounded bg-white/5 text-zinc-400 border border-white/10">
                {tag}
              </span>
            ))}
          </div>
          <div className="pt-4 border-t border-white/10">
            <p className="text-neon-cyan text-sm font-medium">{item.result}</p>
          </div>
        </div>
      </GlassCard>
    </motion.div>
  );
}
