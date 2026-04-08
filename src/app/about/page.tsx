"use client";

import { timeline, stats } from "@/lib/data";
import { fadeUp, staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import SectionHeading from "@/components/ui/SectionHeading";
import TimelineItem from "@/components/ui/TimelineItem";
import CTA from "@/components/sections/CTA";

export default function AboutPage() {
  return (
    <>
      {/* Hero */}
      <section className="pt-32 pb-20 px-6 bg-grid">
        <div className="max-w-4xl mx-auto text-center">
          <motion.span
            className="inline-block text-neon-blue text-sm font-semibold tracking-widest uppercase mb-6"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.2 }}
          >
            About Sage IT
          </motion.span>
          <motion.h1
            className="text-4xl md:text-6xl font-bold text-white mb-6"
            initial={{ opacity: 0, y: 30 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.3, duration: 0.6 }}
          >
            Building the Future of{" "}
            <span className="text-gradient">Intelligent Technology</span>
          </motion.h1>
          <motion.p
            className="text-zinc-400 text-lg leading-relaxed"
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
      <section className="py-20 px-6">
        <div className="max-w-7xl mx-auto">
          <motion.div
            className="grid md:grid-cols-2 gap-8"
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true }}
          >
            <motion.div variants={fadeUp} className="glass p-10">
              <h3 className="text-2xl font-bold text-white mb-4">Our Vision</h3>
              <p className="text-zinc-400 leading-relaxed">
                To be the most trusted technology partner for enterprises navigating the AI revolution — enabling them
                to innovate fearlessly, scale confidently, and lead their industries.
              </p>
            </motion.div>
            <motion.div variants={fadeUp} className="glass p-10">
              <h3 className="text-2xl font-bold text-white mb-4">Our Mission</h3>
              <p className="text-zinc-400 leading-relaxed">
                To deliver intelligent, scalable, and secure technology solutions that empower businesses to achieve
                measurable outcomes. We combine deep engineering expertise with AI-first thinking to solve complex
                challenges.
              </p>
            </motion.div>
          </motion.div>
        </div>
      </section>

      {/* Stats */}
      <section className="py-20 px-6 bg-grid">
        <motion.div
          className="max-w-7xl mx-auto grid grid-cols-2 lg:grid-cols-4 gap-6"
          variants={staggerContainer}
          initial="hidden"
          whileInView="visible"
          viewport={{ once: true }}
        >
          {stats.map((stat) => (
            <motion.div
              key={stat.label}
              variants={fadeUp}
              className="glass p-8 text-center"
            >
              <div className="text-3xl md:text-4xl font-bold text-gradient mb-2">{stat.value}</div>
              <div className="text-zinc-400 text-sm">{stat.label}</div>
            </motion.div>
          ))}
        </motion.div>
      </section>

      {/* Timeline */}
      <section className="py-32 px-6">
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
