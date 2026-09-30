"use client";

import { resources } from "@/lib/data";
import { cn, staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import SectionHeading from "../ui/SectionHeading";
import ResourceCard from "../ui/ResourceCard";
import GlowButton from "../ui/GlowButton";

export default function ResourcesPreview() {
  return (
    <section className="py-20 sm:py-28 lg:py-32 px-4 sm:px-6 relative bg-grid">
      <div className="max-w-7xl mx-auto">
        <SectionHeading
          label="Resources"
          title="Everything You Need to Learn and Grow"
          description="Courses, certificates, practical work, online learning and career support, all in one place."
        />

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
              // Five cards: three across, then the last two centred below.
              className={cn("lg:col-span-2", i === 3 && "lg:col-start-2")}
            />
          ))}
        </motion.div>

        <div className="mt-10 sm:mt-12 flex flex-col sm:flex-row items-center justify-center gap-3 sm:gap-4">
          <GlowButton href="/enroll">Get Started</GlowButton>
          <GlowButton href="/resources" variant="secondary">
            View All Resources
          </GlowButton>
        </div>
      </div>
    </section>
  );
}
