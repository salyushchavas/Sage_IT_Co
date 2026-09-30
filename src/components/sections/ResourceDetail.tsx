"use client";

import { Resource, resources } from "@/lib/data";
import { fadeUp, staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import Link from "next/link";
import GlowButton from "../ui/GlowButton";
import SectionHeading from "../ui/SectionHeading";
import CTA from "./CTA";

export default function ResourceDetail({ resource }: { resource: Resource }) {
  const others = resources.filter((r) => r.id !== resource.id);

  return (
    <>
      <section className="pt-28 sm:pt-32 pb-16 sm:pb-20 px-4 sm:px-6 bg-grid">
        <div className="max-w-4xl mx-auto text-center">
          <motion.span
            className="inline-block text-neon-blue text-xs sm:text-sm font-semibold tracking-widest uppercase mb-4 sm:mb-6"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.2 }}
          >
            <Link href="/resources" className="hover:underline">Resources</Link>
          </motion.span>
          <motion.h1
            className="text-3xl sm:text-4xl md:text-6xl font-bold text-zinc-900 mb-4 sm:mb-6 text-balance"
            initial={{ opacity: 0, y: 30 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.3, duration: 0.6 }}
          >
            <span className="text-gradient">{resource.title}</span>
          </motion.h1>
          <motion.p
            className="text-zinc-600 text-base sm:text-lg leading-relaxed mb-8 sm:mb-10"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.5, duration: 0.6 }}
          >
            {resource.description}
          </motion.p>
          <motion.div
            className="flex flex-col sm:flex-row items-center justify-center gap-3 sm:gap-4"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.65, duration: 0.6 }}
          >
            <GlowButton href={resource.cta.href}>{resource.cta.label}</GlowButton>
            <GlowButton href={resource.secondaryCta.href} variant="secondary">
              {resource.secondaryCta.label}
            </GlowButton>
          </motion.div>
        </div>
      </section>

      <section className="py-16 sm:py-20 px-4 sm:px-6">
        <div className="max-w-7xl mx-auto">
          <SectionHeading label="What You Get" title="How It Works" description={resource.details} />
          <motion.div
            className="grid md:grid-cols-2 gap-5 sm:gap-6 md:gap-8"
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true }}
          >
            {resource.highlights.map((h) => (
              <motion.div key={h.title} variants={fadeUp} className="glass p-6 sm:p-8 md:p-10">
                <h3 className="text-xl sm:text-2xl font-bold text-zinc-900 mb-3 sm:mb-4">{h.title}</h3>
                <p className="text-zinc-600 leading-relaxed text-sm sm:text-base">{h.description}</p>
              </motion.div>
            ))}
          </motion.div>
        </div>
      </section>

      <section className="py-16 sm:py-20 px-4 sm:px-6 bg-grid">
        <div className="max-w-7xl mx-auto text-center">
          <h2 className="text-zinc-500 text-xs sm:text-sm tracking-widest uppercase mb-8 sm:mb-10">
            More Resources
          </h2>
          <div className="flex flex-wrap justify-center gap-2 sm:gap-3">
            {others.map((r) => (
              <Link
                key={r.id}
                href={`/resources/${r.id}`}
                className="px-4 sm:px-5 py-2 rounded-full text-xs sm:text-sm font-medium transition-all duration-300 bg-white/60 text-zinc-600 border border-zinc-200 hover:text-zinc-900"
              >
                {r.title}
              </Link>
            ))}
          </div>
        </div>
      </section>

      <CTA />
    </>
  );
}
