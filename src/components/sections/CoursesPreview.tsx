"use client";

import { courseTracks, applyHref } from "@/lib/course-tracks";
import { cn, staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import SectionHeading from "../ui/SectionHeading";
import CourseTrackCard from "../ui/CourseTrackCard";
import GlowButton from "../ui/GlowButton";

/**
 * Roadmap step 1: the visitor sees the courses and applies. Applying
 * opens the application form with that course chosen.
 */
export default function CoursesPreview({ compact = false }: { compact?: boolean }) {
  return (
    <section
      id="courses"
      className={cn(
        "px-4 sm:px-6 relative scroll-mt-20",
        // compact: under a page's own heading (the /courses page)
        compact ? "py-10 sm:py-12" : "py-20 sm:py-28 lg:py-32"
      )}
    >
      <div className="max-w-7xl mx-auto">
        {!compact && (
          <SectionHeading
            label="Our Courses"
            title="Choose Your Course and Apply"
            description="Pick the technology you want to build a career in. Apply in a minute; once our team confirms your application, we email you a link to register."
          />
        )}

        <motion.div
          className="grid sm:grid-cols-2 lg:grid-cols-4 gap-5 sm:gap-6"
          variants={staggerContainer}
          initial="hidden"
          whileInView="visible"
          viewport={{ once: true, margin: "-50px" }}
        >
          {courseTracks.map((track, i) => (
            <CourseTrackCard key={track.name} track={track} index={i} />
          ))}
        </motion.div>

        <div className="mt-10 sm:mt-12 flex flex-col sm:flex-row items-center justify-center gap-3 sm:gap-4">
          <GlowButton href={applyHref()}>Apply Now</GlowButton>
          <GlowButton href={applyHref("Other")} variant="secondary">
            Looking for something else?
          </GlowButton>
        </div>
      </div>
    </section>
  );
}
