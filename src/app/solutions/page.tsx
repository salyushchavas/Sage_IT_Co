"use client";

import { solutions } from "@/lib/data";
import { fadeUp, staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import GlassCard from "@/components/ui/GlassCard";
import SectionHeading from "@/components/ui/SectionHeading";
import CTA from "@/components/sections/CTA";
import { useState } from "react";

const categories = ["All", "Enterprise Solutions", "AI Platforms", "SaaS Tools"];

export default function SolutionsPage() {
  const [active, setActive] = useState("All");
  const filtered = active === "All" ? solutions : solutions.filter((s) => s.category === active);

  return (
    <>
      <section className="pt-32 pb-20 px-6 bg-grid">
        <div className="max-w-4xl mx-auto text-center">
          <motion.h1
            className="text-4xl md:text-6xl font-bold text-zinc-900 mb-6"
            initial={{ opacity: 0, y: 30 }}
            animate={{ opacity: 1, y: 0 }}
          >
            Our <span className="text-gradient">Solutions</span>
          </motion.h1>
          <motion.p
            className="text-zinc-600 text-lg"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.2 }}
          >
            Enterprise-grade platforms and tools built to solve real business problems at scale.
          </motion.p>
        </div>
      </section>

      <section className="py-20 px-6">
        <div className="max-w-7xl mx-auto">
          {/* Filter tabs */}
          <div className="flex flex-wrap justify-center gap-3 mb-12">
            {categories.map((cat) => (
              <button
                key={cat}
                onClick={() => setActive(cat)}
                className={`px-5 py-2 rounded-full text-sm font-medium transition-all duration-300 ${
                  active === cat
                    ? "bg-neon-blue/20 text-neon-blue border border-neon-blue/30"
                    : "bg-white/60 text-zinc-600 border border-zinc-200 hover:text-zinc-900"
                }`}
              >
                {cat}
              </button>
            ))}
          </div>

          <motion.div
            className="grid md:grid-cols-2 lg:grid-cols-3 gap-8"
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true }}
            key={active}
          >
            {filtered.map((sol) => (
              <motion.div key={sol.id} variants={fadeUp}>
                <GlassCard className="p-8 h-full glow-border" hover3D glowColor="#FBBF24">
                  <span className="text-xs px-3 py-1 rounded-full bg-neon-violet/10 text-neon-violet border border-neon-violet/20 mb-4 inline-block">
                    {sol.category}
                  </span>
                  <h3 className="text-xl font-bold text-zinc-900 mb-3">{sol.title}</h3>
                  <p className="text-zinc-600 text-sm leading-relaxed mb-6">{sol.description}</p>
                  <ul className="space-y-2">
                    {sol.features.map((f) => (
                      <li key={f} className="flex items-center gap-2 text-sm text-zinc-500">
                        <span className="w-1.5 h-1.5 rounded-full bg-neon-cyan" />
                        {f}
                      </li>
                    ))}
                  </ul>
                </GlassCard>
              </motion.div>
            ))}
          </motion.div>
        </div>
      </section>

      <CTA />
    </>
  );
}
