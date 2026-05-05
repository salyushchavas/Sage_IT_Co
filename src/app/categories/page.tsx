"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { Code, Database, Palette, Cloud, Smartphone, BarChart3 } from "lucide-react";
import { cn, staggerContainer, fadeUp } from "@/lib/utils";

const categories = [
  {
    name: "Web Development",
    description: "Full-stack, frontend & backend frameworks",
    icon: Code,
    color: "#22C55E",
    courses: 42,
  },
  {
    name: "Data Science",
    description: "Machine learning, AI & data analytics",
    icon: Database,
    color: "#E5B62E",
    courses: 35,
  },
  {
    name: "UI/UX Design",
    description: "User experience, Figma, prototyping",
    icon: Palette,
    color: "#14653F",
    courses: 28,
  },
  {
    name: "Cloud & DevOps",
    description: "AWS, Docker, Kubernetes, CI/CD",
    icon: Cloud,
    color: "#10b981",
    courses: 31,
  },
  {
    name: "Mobile Development",
    description: "React Native, Flutter, iOS & Android",
    icon: Smartphone,
    color: "#f59e0b",
    courses: 24,
  },
  {
    name: "Analytics",
    description: "Business intelligence, dashboards & metrics",
    icon: BarChart3,
    color: "#ef4444",
    courses: 19,
  },
];

export default function CategoriesPage() {
  return (
    <section className="min-h-screen pt-32 pb-20 px-6">
      <div className="mx-auto max-w-6xl">
        {/* Header */}
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} className="text-center mb-16">
          <h1 className="text-4xl sm:text-5xl font-bold text-zinc-900 mb-4">
            Course{" "}
            <span className="bg-gradient-to-r from-[#22C55E] to-[#E5B62E] bg-clip-text text-transparent">
              Categories
            </span>
          </h1>
          <p className="text-zinc-600 max-w-lg mx-auto">
            Explore our curated collection of courses across popular technology domains.
          </p>
        </motion.div>

        {/* Grid */}
        <motion.div variants={staggerContainer} initial="hidden" animate="visible" className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {categories.map((cat) => {
            const Icon = cat.icon;
            return (
              <motion.div key={cat.name} variants={fadeUp}>
                <Link
                  href={`/courses?category=${encodeURIComponent(cat.name)}`}
                  className="block group"
                >
                  <div
                    className={cn(
                      "bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-8",
                      "transition-all duration-300 hover:border-zinc-300",
                      "hover:shadow-[0_0_40px_rgba(0,0,0,0.2)]"
                    )}
                    style={{
                      // Per-card glow on hover via CSS variable
                    }}
                  >
                    {/* Icon */}
                    <div
                      className="w-14 h-14 rounded-xl flex items-center justify-center mb-5 transition-transform duration-300 group-hover:scale-110"
                      style={{
                        backgroundColor: `${cat.color}15`,
                        border: `1px solid ${cat.color}30`,
                      }}
                    >
                      <Icon className="w-6 h-6" style={{ color: cat.color }} />
                    </div>

                    <h3 className="text-lg font-bold text-zinc-900 mb-2 group-hover:text-[#22C55E] transition-colors">
                      {cat.name}
                    </h3>
                    <p className="text-sm text-zinc-600 mb-4">{cat.description}</p>

                    <div className="flex items-center justify-between">
                      <span className="text-xs text-zinc-500">
                        {cat.courses} courses
                      </span>
                      <span className="text-xs font-medium text-[#22C55E] opacity-0 group-hover:opacity-100 transition-opacity">
                        Browse &rarr;
                      </span>
                    </div>
                  </div>
                </Link>
              </motion.div>
            );
          })}
        </motion.div>
      </div>
    </section>
  );
}
