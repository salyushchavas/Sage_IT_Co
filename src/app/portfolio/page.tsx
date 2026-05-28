"use client";

import { portfolio } from "@/lib/data";
import { staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import PortfolioCard from "@/components/ui/PortfolioCard";
import CTA from "@/components/sections/CTA";
import { useState } from "react";

const categories = ["All", "Cloud", "Data", "Security", "Web", "AI"];

export default function PortfolioPage() {
  const [active, setActive] = useState("All");
  const filtered = active === "All" ? portfolio : portfolio.filter((p) => p.category === active);

  return (
    <>
      <section className="pt-28 sm:pt-32 pb-16 sm:pb-20 px-4 sm:px-6 bg-grid">
        <div className="max-w-4xl mx-auto text-center">
          <motion.h1
            className="text-3xl sm:text-4xl md:text-6xl font-bold text-zinc-900 mb-4 sm:mb-6"
            initial={{ opacity: 0, y: 30 }}
            animate={{ opacity: 1, y: 0 }}
          >
            Our <span className="text-gradient">Portfolio</span>
          </motion.h1>
          <motion.p
            className="text-zinc-600 text-base sm:text-lg"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.2 }}
          >
            Case studies showcasing real impact for real enterprises.
          </motion.p>
        </div>
      </section>

      <section className="py-16 sm:py-20 px-4 sm:px-6">
        <div className="max-w-7xl mx-auto">
          <div className="flex flex-wrap justify-center gap-2 sm:gap-3 mb-8 sm:mb-12">
            {categories.map((cat) => (
              <button
                key={cat}
                onClick={() => setActive(cat)}
                className={`px-4 sm:px-5 py-2 rounded-full text-xs sm:text-sm font-medium transition-all duration-300 ${
                  active === cat
                    ? "bg-neon-cyan/20 text-neon-cyan border border-neon-cyan/30"
                    : "bg-white/60 text-zinc-600 border border-zinc-200 hover:text-zinc-900"
                }`}
              >
                {cat}
              </button>
            ))}
          </div>

          <motion.div
            className="grid sm:grid-cols-2 lg:grid-cols-3 gap-5 sm:gap-6 lg:gap-8"
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true }}
            key={active}
          >
            {filtered.map((item, i) => (
              <PortfolioCard key={item.id} item={item} index={i} />
            ))}
          </motion.div>
        </div>
      </section>

      <CTA />
    </>
  );
}
