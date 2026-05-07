"use client";

import { portfolio } from "@/lib/data";
import { staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import SectionHeading from "../ui/SectionHeading";
import PortfolioCard from "../ui/PortfolioCard";
import GlowButton from "../ui/GlowButton";

export default function PortfolioPreview() {
  return (
    <section className="py-20 sm:py-28 lg:py-32 px-4 sm:px-6 relative">
      <div className="max-w-7xl mx-auto">
        <SectionHeading
          label="Portfolio"
          title="Our Impact"
          description="Real projects, real results. See how we have helped enterprises achieve their technology goals."
        />

        <motion.div
          className="grid sm:grid-cols-2 lg:grid-cols-3 gap-5 sm:gap-6 lg:gap-8"
          variants={staggerContainer}
          initial="hidden"
          whileInView="visible"
          viewport={{ once: true, margin: "-50px" }}
        >
          {portfolio.slice(0, 3).map((item, i) => (
            <PortfolioCard key={item.id} item={item} index={i} />
          ))}
        </motion.div>

        <div className="text-center mt-10 sm:mt-12">
          <GlowButton href="/portfolio" variant="secondary">
            View All Projects
          </GlowButton>
        </div>
      </div>
    </section>
  );
}
