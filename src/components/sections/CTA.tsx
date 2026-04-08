"use client";

import { motion } from "framer-motion";
import { fadeUp } from "@/lib/utils";
import GlowButton from "../ui/GlowButton";

export default function CTA() {
  return (
    <section className="py-32 px-6 relative overflow-hidden">
      {/* Background glow */}
      <div className="absolute inset-0">
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] bg-neon-violet/10 rounded-full blur-[120px]" />
        <div className="absolute top-1/4 left-1/4 w-[300px] h-[300px] bg-neon-blue/10 rounded-full blur-[80px]" />
      </div>

      <motion.div
        className="max-w-3xl mx-auto text-center relative z-10"
        variants={fadeUp}
        initial="hidden"
        whileInView="visible"
        viewport={{ once: true }}
      >
        <h2 className="text-3xl md:text-5xl font-bold text-white mb-6">
          Ready to <span className="text-gradient">Transform</span> Your Business?
        </h2>
        <p className="text-zinc-400 text-lg mb-10 leading-relaxed">
          Let&apos;s discuss how Sage IT can help you leverage cutting-edge technology to drive growth,
          efficiency, and innovation.
        </p>
        <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
          <GlowButton href="/contact">Start a Conversation</GlowButton>
          <GlowButton href="/careers" variant="secondary">
            Join Our Team
          </GlowButton>
        </div>
      </motion.div>
    </section>
  );
}
