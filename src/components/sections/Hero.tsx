"use client";

import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import GlowButton from "../ui/GlowButton";

function MorphingCodeWrapper() {
  const [Component, setComponent] = useState<React.ComponentType | null>(null);

  useEffect(() => {
    import("./MorphingCode").then((mod) => setComponent(() => mod.default));
  }, []);

  if (!Component) return null;
  return <Component />;
}

export default function Hero() {
  return (
    <section className="relative min-h-screen flex items-center justify-center overflow-hidden bg-grid">
      {/* Morphing code background */}
      <MorphingCodeWrapper />

      {/* Radial gradient overlay */}
      <div className="absolute inset-0 bg-gradient-radial from-transparent via-background/30 to-background z-[1]" />

      {/* Content */}
      <div className="relative z-10 text-center px-4 sm:px-6 max-w-4xl mx-auto py-24 sm:py-20">
        <motion.div
          initial={{ opacity: 0, y: 30 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8, delay: 0.4 }}
        >
          <span className="inline-block text-neon-blue text-[10px] sm:text-xs md:text-sm font-semibold tracking-widest uppercase mb-4 sm:mb-6 px-3 sm:px-4 py-1.5 sm:py-2 rounded-full border border-neon-blue/20 bg-neon-blue/5">
            Enterprise Technology · Engineered for Tomorrow
          </span>
        </motion.div>

        <motion.h1
          className="text-3xl sm:text-5xl md:text-6xl lg:text-7xl font-bold text-zinc-900 leading-tight mb-4 sm:mb-6 text-balance"
          initial={{ opacity: 0, y: 40 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8, delay: 0.6 }}
        >
          Engineering Intelligence.
          <br />
          <span className="text-gradient">Empowering Growth.</span>
        </motion.h1>

        <motion.p
          className="text-base sm:text-lg md:text-xl text-zinc-600 max-w-2xl mx-auto mb-8 sm:mb-10 leading-relaxed px-2 sm:px-0"
          initial={{ opacity: 0, y: 30 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8, delay: 0.8 }}
        >
          We architect intelligent solutions that transform businesses — from cloud infrastructure
          and AI to cybersecurity and data analytics.
        </motion.p>

        <motion.div
          className="flex flex-col sm:flex-row items-stretch sm:items-center justify-center gap-3 sm:gap-4 max-w-md sm:max-w-none mx-auto"
          initial={{ opacity: 0, y: 30 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8, delay: 1.0 }}
        >
          <GlowButton href="/services">Explore Services</GlowButton>
          <GlowButton href="/contact" variant="secondary">
            Contact Us
          </GlowButton>
        </motion.div>
      </div>

      {/* Scroll indicator */}
      <motion.div
        className="absolute bottom-6 sm:bottom-8 left-1/2 -translate-x-1/2 z-10 hidden sm:block"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 2 }}
      >
        <motion.div
          className="w-6 h-10 rounded-full border-2 border-zinc-300 flex justify-center pt-2"
          animate={{ y: [0, 8, 0] }}
          transition={{ duration: 1.5, repeat: Infinity }}
        >
          <div className="w-1 h-2 rounded-full bg-neon-blue" />
        </motion.div>
      </motion.div>
    </section>
  );
}
