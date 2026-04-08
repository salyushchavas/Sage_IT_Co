"use client";

import { services } from "@/lib/data";
import { staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import SectionHeading from "../ui/SectionHeading";
import ServiceCard from "../ui/ServiceCard";
import GlowButton from "../ui/GlowButton";

export default function ServicesPreview() {
  return (
    <section className="py-32 px-6 relative bg-grid">
      <div className="max-w-7xl mx-auto">
        <SectionHeading
          label="Our Services"
          title="Solutions That Scale"
          description="From cloud architecture to AI integration, we provide end-to-end technology services designed for the modern enterprise."
        />

        <motion.div
          className="grid md:grid-cols-2 lg:grid-cols-3 gap-8"
          variants={staggerContainer}
          initial="hidden"
          whileInView="visible"
          viewport={{ once: true, margin: "-50px" }}
        >
          {services.map((service, i) => (
            <ServiceCard key={service.id} service={service} index={i} />
          ))}
        </motion.div>

        <div className="text-center mt-12">
          <GlowButton href="/services" variant="secondary">
            View All Services
          </GlowButton>
        </div>
      </div>
    </section>
  );
}
