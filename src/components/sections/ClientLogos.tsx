"use client";

import { clients } from "@/lib/data";
import { fadeUp } from "@/lib/utils";
import { motion } from "framer-motion";

export default function ClientLogos() {
  const doubled = [...clients, ...clients];

  return (
    <section className="py-14 sm:py-20 px-4 sm:px-6 border-y border-zinc-200">
      <motion.div
        className="max-w-7xl mx-auto"
        variants={fadeUp}
        initial="hidden"
        whileInView="visible"
        viewport={{ once: true }}
      >
        <p className="text-center text-zinc-500 text-xs sm:text-sm tracking-widest uppercase mb-8 sm:mb-10">
          Trusted by Industry Leaders
        </p>
        <div className="overflow-hidden relative">
          {/* Fade edges */}
          <div className="absolute left-0 top-0 bottom-0 w-12 sm:w-24 lg:w-32 bg-gradient-to-r from-background to-transparent z-10 pointer-events-none" />
          <div className="absolute right-0 top-0 bottom-0 w-12 sm:w-24 lg:w-32 bg-gradient-to-l from-background to-transparent z-10 pointer-events-none" />

          <div className="flex animate-marquee">
            {doubled.map((client, i) => (
              <div
                key={`${client}-${i}`}
                className="flex-shrink-0 mx-3 sm:mx-6 lg:mx-8 px-5 sm:px-7 lg:px-8 py-3 sm:py-4 glass rounded-xl text-zinc-500 font-medium text-xs sm:text-sm whitespace-nowrap hover:text-zinc-900 hover:border-neon-blue/30 transition-all duration-300"
              >
                {client}
              </div>
            ))}
          </div>
        </div>
      </motion.div>
    </section>
  );
}
