"use client";

import { timeline, stats } from "@/lib/data";
import { fadeUp, popIn, staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import SectionHeading from "@/components/ui/SectionHeading";
import TimelineItem from "@/components/ui/TimelineItem";
import CountUp from "@/components/ui/CountUp";
import CTA from "@/components/sections/CTA";

export default function AboutPage() {
  return (
    <>
      {/* Hero */}
      <section className="pt-28 sm:pt-32 pb-16 sm:pb-20 px-4 sm:px-6 bg-grid">
        <div className="max-w-4xl mx-auto text-center">
          <motion.span
            className="inline-block text-neon-blue text-xs sm:text-sm font-semibold tracking-widest uppercase mb-4 sm:mb-6"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.2 }}
          >
            About Sage IT
          </motion.span>
          <motion.h1
            className="text-3xl sm:text-4xl md:text-6xl font-bold text-zinc-900 mb-4 sm:mb-6 text-balance"
            initial={{ opacity: 0, y: 30 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.3, duration: 0.6 }}
          >
            Building the Future of{" "}
            <span className="text-gradient">Intelligent Technology</span>
          </motion.h1>
          <motion.p
            className="text-zinc-600 text-base sm:text-lg leading-relaxed"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.5, duration: 0.6 }}
          >
            We are a team of engineers, architects, and innovators committed to transforming how
            enterprises leverage technology. Our mission is to make AI-powered IT accessible,
            reliable, and impactful.
          </motion.p>
        </div>
      </section>

      {/* Vision & Mission */}
      <section className="py-16 sm:py-20 px-4 sm:px-6">
        <div className="max-w-7xl mx-auto">
          <motion.div
            className="grid md:grid-cols-2 gap-5 sm:gap-6 md:gap-8"
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true }}
          >
            <motion.div variants={fadeUp} className="glass p-6 sm:p-8 md:p-10">
              <h3 className="text-xl sm:text-2xl font-bold text-zinc-900 mb-3 sm:mb-4">Our Vision</h3>
              <p className="text-zinc-600 leading-relaxed text-sm sm:text-base">
                To be the most trusted technology partner for enterprises navigating the AI revolution — enabling them
                to innovate fearlessly, scale confidently, and lead their industries.
              </p>
            </motion.div>
            <motion.div variants={fadeUp} className="glass p-6 sm:p-8 md:p-10">
              <h3 className="text-xl sm:text-2xl font-bold text-zinc-900 mb-3 sm:mb-4">Our Mission</h3>
              <p className="text-zinc-600 leading-relaxed text-sm sm:text-base">
                To deliver intelligent, scalable, and secure technology solutions that empower businesses to achieve
                measurable outcomes. We combine deep engineering expertise with AI-first thinking to solve complex
                challenges.
              </p>
            </motion.div>
          </motion.div>
        </div>
      </section>

      {/* Stats */}
      <section className="py-16 sm:py-20 px-4 sm:px-6 bg-grid">
        <motion.div
          className="max-w-7xl mx-auto grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-5 lg:gap-6"
          variants={staggerContainer}
          initial="hidden"
          whileInView="visible"
          viewport={{ once: true }}
        >
          {stats.map((stat) => (
            <motion.div
              key={stat.label}
              variants={popIn}
              className="glass p-4 sm:p-6 lg:p-8 text-center hover:-translate-y-1 hover:border-neon-blue/30 transition-all duration-500"
            >
              <div className="text-2xl sm:text-3xl md:text-4xl font-bold text-gradient mb-1 sm:mb-2 tabular-nums">
                <CountUp value={stat.value} />
              </div>
              <div className="text-zinc-600 text-xs sm:text-sm">{stat.label}</div>
            </motion.div>
          ))}
        </motion.div>
      </section>

      {/* Timeline */}
      <section className="py-20 sm:py-28 lg:py-32 px-4 sm:px-6">
        <div className="max-w-5xl mx-auto">
          <SectionHeading label="Our Journey" title="Timeline of Growth" />
          <div className="relative">
            {timeline.map((event, i) => (
              <TimelineItem key={event.year} event={event} index={i} />
            ))}
          </div>
        </div>
      </section>

      <CTA />
    </>
  );
}
