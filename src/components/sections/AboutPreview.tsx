"use client";

import { stats } from "@/lib/data";
import { fadeUp, staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import SectionHeading from "../ui/SectionHeading";
import GlowButton from "../ui/GlowButton";

export default function AboutPreview() {
  return (
    <section className="py-32 px-6 relative">
      <div className="max-w-7xl mx-auto">
        <div className="grid lg:grid-cols-2 gap-16 items-center">
          {/* Left: Text */}
          <motion.div
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: "-50px" }}
          >
            <motion.div variants={fadeUp}>
              <SectionHeading
                label="About Us"
                title="Pioneering the Future of IT"
                align="left"
              />
            </motion.div>
            <motion.p variants={fadeUp} className="text-zinc-400 text-lg leading-relaxed mb-6">
              Sage IT is a technology-first company that combines deep engineering expertise with
              AI-driven innovation. We partner with enterprises to modernize infrastructure, secure
              digital assets, and unlock the power of data.
            </motion.p>
            <motion.p variants={fadeUp} className="text-zinc-400 leading-relaxed mb-8">
              Founded in 2018, we have grown from a cloud consulting startup to a full-spectrum
              technology partner serving Fortune 500 companies across 3 continents.
            </motion.p>
            <motion.div variants={fadeUp}>
              <GlowButton href="/about">Learn More</GlowButton>
            </motion.div>
          </motion.div>

          {/* Right: Stats grid */}
          <motion.div
            className="grid grid-cols-2 gap-6"
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: "-50px" }}
          >
            {stats.map((stat) => (
              <motion.div
                key={stat.label}
                variants={fadeUp}
                className="glass p-8 text-center group hover:border-neon-blue/30 transition-all duration-500"
              >
                <div className="text-3xl md:text-4xl font-bold text-gradient mb-2">
                  {stat.value}
                </div>
                <div className="text-zinc-400 text-sm">{stat.label}</div>
              </motion.div>
            ))}
          </motion.div>
        </div>
      </div>
    </section>
  );
}
