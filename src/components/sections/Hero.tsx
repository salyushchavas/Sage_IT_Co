"use client";

import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import GlowButton from "../ui/GlowButton";

function NeuralNetworkWrapper() {
  const [Component, setComponent] = useState<React.ComponentType | null>(null);

  useEffect(() => {
    import("../three/NeuralNetwork").then((mod) => setComponent(() => mod.default));
  }, []);

  if (!Component) return null;
  return <Component />;
}

export default function Hero() {
  return (
    <section className="relative min-h-screen flex items-center justify-center overflow-hidden bg-grid">
      {/* Three.js background */}
      <NeuralNetworkWrapper />

      {/* Radial gradient overlay */}
      <div className="absolute inset-0 bg-gradient-radial from-transparent via-background/50 to-background z-[1]" />

      {/* Content */}
      <div className="relative z-10 text-center px-6 max-w-4xl mx-auto">
        <motion.div
          initial={{ opacity: 0, y: 30 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8, delay: 1.6 }}
        >
          <span className="inline-block text-neon-blue text-sm font-semibold tracking-widest uppercase mb-6 px-4 py-2 rounded-full border border-neon-blue/20 bg-neon-blue/5">
            Next-Gen IT Solutions
          </span>
        </motion.div>

        <motion.h1
          className="text-4xl sm:text-5xl md:text-7xl font-bold text-white leading-tight mb-6"
          initial={{ opacity: 0, y: 40 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8, delay: 1.8 }}
        >
          Engineering Intelligence.
          <br />
          <span className="text-gradient">Empowering Growth.</span>
        </motion.h1>

        <motion.p
          className="text-lg md:text-xl text-zinc-400 max-w-2xl mx-auto mb-10 leading-relaxed"
          initial={{ opacity: 0, y: 30 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8, delay: 2.0 }}
        >
          We architect intelligent solutions that transform businesses — from cloud infrastructure
          and AI to cybersecurity and data analytics.
        </motion.p>

        <motion.div
          className="flex flex-col sm:flex-row items-center justify-center gap-4"
          initial={{ opacity: 0, y: 30 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8, delay: 2.2 }}
        >
          <GlowButton href="/services">Explore Services</GlowButton>
          <GlowButton href="/contact" variant="secondary">
            Contact Us
          </GlowButton>
        </motion.div>
      </div>

      {/* Scroll indicator */}
      <motion.div
        className="absolute bottom-8 left-1/2 -translate-x-1/2 z-10"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 3 }}
      >
        <motion.div
          className="w-6 h-10 rounded-full border-2 border-white/20 flex justify-center pt-2"
          animate={{ y: [0, 8, 0] }}
          transition={{ duration: 1.5, repeat: Infinity }}
        >
          <div className="w-1 h-2 rounded-full bg-neon-blue" />
        </motion.div>
      </motion.div>
    </section>
  );
}
