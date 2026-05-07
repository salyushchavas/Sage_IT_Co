"use client";

import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import GlowButton from "../ui/GlowButton";
import { wordContainer, wordChild, bounceIn } from "@/lib/utils";

function MorphingCodeWrapper() {
  const [Component, setComponent] = useState<React.ComponentType | null>(null);

  useEffect(() => {
    import("./MorphingCode").then((mod) => setComponent(() => mod.default));
  }, []);

  if (!Component) return null;
  return <Component />;
}

const headlineLine1 = ["Engineering", "Intelligence."];
const headlineLine2 = ["Empowering", "Growth."];

export default function Hero() {
  return (
    <section className="relative min-h-screen flex items-center justify-center overflow-hidden bg-grid">
      {/* Morphing code background */}
      <MorphingCodeWrapper />

      {/* Floating gradient orbs — drift behind content */}
      <div className="absolute inset-0 z-[1] pointer-events-none">
        <div className="absolute top-[15%] left-[8%] w-56 h-56 sm:w-72 sm:h-72 rounded-full bg-sage-navy/15 blur-3xl animate-blob-1" />
        <div className="absolute bottom-[12%] right-[10%] w-64 h-64 sm:w-80 sm:h-80 rounded-full bg-sage-copper/15 blur-3xl animate-blob-2" />
        <div className="absolute top-1/3 right-[18%] w-40 h-40 rounded-full bg-sage-copper-light/15 blur-3xl animate-drift-slow" />
      </div>

      {/* Radial gradient overlay */}
      <div className="absolute inset-0 bg-gradient-radial from-transparent via-background/30 to-background z-[2]" />

      {/* Content */}
      <div className="relative z-10 text-center px-4 sm:px-6 max-w-4xl mx-auto py-24 sm:py-20">
        <motion.div
          variants={bounceIn}
          initial="hidden"
          animate="visible"
          transition={{ delay: 0.3 }}
        >
          <span className="relative inline-block text-neon-blue text-[10px] sm:text-xs md:text-sm font-semibold tracking-widest uppercase mb-4 sm:mb-6 px-3 sm:px-4 py-1.5 sm:py-2 rounded-full border border-neon-blue/20 bg-neon-blue/5 overflow-hidden">
            <span className="relative z-10">Enterprise Technology · Engineered for Tomorrow</span>
            <span
              aria-hidden
              className="absolute inset-0 opacity-60"
              style={{
                background:
                  "linear-gradient(110deg, transparent 38%, rgba(200,125,92,0.35) 50%, transparent 62%)",
                backgroundSize: "200% 100%",
                animation: "shimmer 3.5s linear infinite",
              }}
            />
          </span>
        </motion.div>

        <motion.h1
          className="text-3xl sm:text-5xl md:text-6xl lg:text-7xl font-bold text-zinc-900 leading-tight mb-4 sm:mb-6 text-balance"
          variants={wordContainer}
          initial="hidden"
          animate="visible"
        >
          <span className="inline-block">
            {headlineLine1.map((word, i) => (
              <motion.span
                key={`l1-${i}`}
                variants={wordChild}
                className="inline-block mr-[0.25em] last:mr-0"
              >
                {word}
              </motion.span>
            ))}
          </span>
          <br />
          <span className="inline-block text-gradient">
            {headlineLine2.map((word, i) => (
              <motion.span
                key={`l2-${i}`}
                variants={wordChild}
                className="inline-block mr-[0.25em] last:mr-0"
              >
                {word}
              </motion.span>
            ))}
          </span>
        </motion.h1>

        <motion.p
          className="text-base sm:text-lg md:text-xl text-zinc-600 max-w-2xl mx-auto mb-8 sm:mb-10 leading-relaxed px-2 sm:px-0"
          initial={{ opacity: 0, y: 30, filter: "blur(6px)" }}
          animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
          transition={{ duration: 0.8, delay: 1.0 }}
        >
          We architect intelligent solutions that transform businesses — from cloud infrastructure
          and AI to cybersecurity and data analytics.
        </motion.p>

        <motion.div
          className="flex flex-col sm:flex-row items-stretch sm:items-center justify-center gap-3 sm:gap-4 max-w-md sm:max-w-none mx-auto"
          initial={{ opacity: 0, y: 30 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8, delay: 1.2 }}
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
          <motion.div
            className="w-1 h-2 rounded-full bg-neon-blue"
            animate={{ opacity: [0.4, 1, 0.4] }}
            transition={{ duration: 1.5, repeat: Infinity }}
          />
        </motion.div>
      </motion.div>
    </section>
  );
}
