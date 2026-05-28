"use client";

import { solutions } from "@/lib/data";
import { fadeUp, staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import SectionHeading from "../ui/SectionHeading";
import GlassCard from "../ui/GlassCard";
import GlowButton from "../ui/GlowButton";

export default function SolutionsPreview() {
  const featured = solutions.slice(0, 3);

  return (
    <section className="py-20 sm:py-28 lg:py-32 px-4 sm:px-6 relative">
      <div className="max-w-7xl mx-auto">
        <SectionHeading
          label="Our Products"
          title="Intelligent Platforms"
          description="Purpose-built products that give enterprises an unfair advantage in the age of AI."
        />

        <motion.div
          className="grid sm:grid-cols-2 md:grid-cols-3 gap-5 sm:gap-6 lg:gap-8"
          variants={staggerContainer}
          initial="hidden"
          whileInView="visible"
          viewport={{ once: true, margin: "-50px" }}
        >
          {featured.map((sol) => (
            <motion.div key={sol.id} variants={fadeUp}>
              <GlassCard className="p-6 sm:p-8 h-full glow-border" hover3D glowColor="#E8A78D">
                <span className="text-xs px-3 py-1 rounded-full bg-neon-violet/10 text-neon-violet border border-neon-violet/20 mb-3 sm:mb-4 inline-block">
                  {sol.category}
                </span>
                <h3 className="text-lg sm:text-xl font-bold text-zinc-900 mb-2 sm:mb-3">{sol.title}</h3>
                <p className="text-zinc-600 text-sm leading-relaxed mb-4 sm:mb-6">{sol.description}</p>
                <ul className="space-y-2">
                  {sol.features.map((f) => (
                    <li key={f} className="flex items-center gap-2 text-sm text-zinc-500">
                      <span className="w-1.5 h-1.5 rounded-full bg-neon-cyan flex-shrink-0" />
                      {f}
                    </li>
                  ))}
                </ul>
              </GlassCard>
            </motion.div>
          ))}
        </motion.div>

        <div className="text-center mt-10 sm:mt-12">
          <GlowButton href="/solutions" variant="secondary">
            Explore All Solutions
          </GlowButton>
        </div>
      </div>
    </section>
  );
}
