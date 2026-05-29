"use client";

import { motion } from "framer-motion";
import { fadeUp } from "@/lib/utils";
import GlowButton from "../ui/GlowButton";

export default function CTA() {
  return (
    <section className="py-20 sm:py-28 md:py-32 px-4 sm:px-6 relative overflow-hidden aurora-bg">
      {/* Background glow — animated organic blobs */}
      <div className="absolute inset-0 pointer-events-none">
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[300px] h-[300px] sm:w-[450px] sm:h-[450px] md:w-[600px] md:h-[600px] bg-neon-violet/10 rounded-full blur-[80px] sm:blur-[100px] md:blur-[120px] animate-blob-1" />
        <div className="absolute top-1/4 left-1/4 w-[180px] h-[180px] sm:w-[240px] sm:h-[240px] md:w-[300px] md:h-[300px] bg-neon-blue/10 rounded-full blur-[60px] sm:blur-[80px] animate-blob-2" />
        <div className="absolute bottom-1/4 right-1/4 w-[140px] h-[140px] sm:w-[200px] sm:h-[200px] bg-sage-copper-light/10 rounded-full blur-[60px] animate-drift" />
      </div>

      <motion.div
        className="max-w-3xl mx-auto text-center relative z-10"
        variants={fadeUp}
        initial="hidden"
        whileInView="visible"
        viewport={{ once: true }}
      >
        <h2 className="text-2xl sm:text-3xl md:text-5xl font-bold text-zinc-900 mb-4 sm:mb-6 text-balance">
          Ready to <span className="text-gradient">Start</span> Your Journey?
        </h2>
        <p className="text-zinc-600 text-base sm:text-lg mb-8 sm:mb-10 leading-relaxed">
          Enroll in the Sage program and we&apos;ll pair you with mentors, structured learning tracks,
          and the placement support you need to grow.
        </p>
        <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
          <GlowButton href="/enroll">Get Started</GlowButton>
          <GlowButton href="/contact" variant="secondary">
            Talk to Us
          </GlowButton>
        </div>
      </motion.div>
    </section>
  );
}
