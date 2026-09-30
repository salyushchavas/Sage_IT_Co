"use client";

import { resources } from "@/lib/data";
import { cn, staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import ResourceCard from "@/components/ui/ResourceCard";
import CTA from "@/components/sections/CTA";

export default function ResourcesPage() {
  return (
    <>
      <section className="pt-28 sm:pt-32 pb-16 sm:pb-20 px-4 sm:px-6 bg-grid">
        <div className="max-w-4xl mx-auto text-center">
          <motion.h1
            className="text-3xl sm:text-4xl md:text-6xl font-bold text-zinc-900 mb-4 sm:mb-6"
            initial={{ opacity: 0, y: 30 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6 }}
          >
            Our <span className="text-gradient">Resources</span>
          </motion.h1>
          <motion.p
            className="text-zinc-600 text-base sm:text-lg leading-relaxed"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.2, duration: 0.6 }}
          >
            Everything Sage IT offers to help you learn technology and grow your career,
            from your first course to your next role.
          </motion.p>
        </div>
      </section>

      <section className="py-16 sm:py-20 px-4 sm:px-6">
        <h2 className="sr-only">All resources</h2>
        <div className="max-w-7xl mx-auto">
          <motion.div
            className="grid sm:grid-cols-2 lg:grid-cols-6 gap-5 sm:gap-6 lg:gap-8"
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: "-50px" }}
          >
            {resources.map((resource, i) => (
              <ResourceCard
                key={resource.id}
                resource={resource}
                index={i}
                detailed
                // Five cards: three across, then the last two centred below.
                className={cn("lg:col-span-2", i === 3 && "lg:col-start-2")}
              />
            ))}
          </motion.div>
        </div>
      </section>

      <CTA />
    </>
  );
}
