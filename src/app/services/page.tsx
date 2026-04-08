"use client";

import { services } from "@/lib/data";
import { staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import SectionHeading from "@/components/ui/SectionHeading";
import ServiceCard from "@/components/ui/ServiceCard";
import CTA from "@/components/sections/CTA";

export default function ServicesPage() {
  return (
    <>
      <section className="pt-32 pb-20 px-6 bg-grid">
        <div className="max-w-4xl mx-auto text-center">
          <motion.h1
            className="text-4xl md:text-6xl font-bold text-white mb-6"
            initial={{ opacity: 0, y: 30 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6 }}
          >
            Our <span className="text-gradient">Services</span>
          </motion.h1>
          <motion.p
            className="text-zinc-400 text-lg leading-relaxed"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.2, duration: 0.6 }}
          >
            Comprehensive technology services designed to accelerate your digital transformation
            and give your business a competitive edge.
          </motion.p>
        </div>
      </section>

      <section className="py-20 px-6">
        <div className="max-w-7xl mx-auto">
          <motion.div
            className="grid md:grid-cols-2 lg:grid-cols-3 gap-8"
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: "-50px" }}
          >
            {services.map((service, i) => (
              <ServiceCard key={service.id} service={service} index={i} detailed />
            ))}
          </motion.div>
        </div>
      </section>

      <CTA />
    </>
  );
}
