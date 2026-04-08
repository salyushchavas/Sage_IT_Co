"use client";

import { testimonials } from "@/lib/data";
import { fadeUp, staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import SectionHeading from "../ui/SectionHeading";
import TestimonialCard from "../ui/TestimonialCard";

export default function Testimonials() {
  return (
    <section className="py-32 px-6 relative bg-grid">
      <div className="max-w-7xl mx-auto">
        <SectionHeading
          label="Testimonials"
          title="What Our Clients Say"
          description="Hear from the enterprises we have helped transform through technology."
        />

        <motion.div
          className="grid md:grid-cols-2 lg:grid-cols-3 gap-8"
          variants={staggerContainer}
          initial="hidden"
          whileInView="visible"
          viewport={{ once: true, margin: "-50px" }}
        >
          {testimonials.slice(0, 3).map((t, i) => (
            <motion.div key={t.name} variants={fadeUp} custom={i}>
              <TestimonialCard testimonial={t} />
            </motion.div>
          ))}
        </motion.div>
      </div>
    </section>
  );
}
