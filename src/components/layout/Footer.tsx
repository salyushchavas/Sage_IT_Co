"use client";

import { navLinks, socialLinks } from "@/lib/data";
import { fadeUp, staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import Image from "next/image";
import Link from "next/link";

export default function Footer() {
  return (
    <footer className="relative border-t border-zinc-200 bg-background overflow-hidden">
      {/* Subtle drifting accent */}
      <div className="absolute -top-32 -left-32 w-96 h-96 rounded-full bg-sage-navy/5 blur-3xl pointer-events-none animate-drift-slow" />
      <div className="absolute -bottom-32 -right-32 w-96 h-96 rounded-full bg-sage-copper/5 blur-3xl pointer-events-none animate-drift" />

      <motion.div
        className="relative max-w-7xl mx-auto px-4 sm:px-6 py-12 sm:py-16"
        variants={staggerContainer}
        initial="hidden"
        whileInView="visible"
        viewport={{ once: true, margin: "-80px" }}
      >
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-8 sm:gap-10 lg:gap-12">
          {/* Brand */}
          <motion.div variants={fadeUp} className="sm:col-span-2 lg:col-span-1">
            <Link href="/" className="inline-block mb-3 sm:mb-4 group">
              <div className="logo-glow relative w-24 h-24 sm:w-28 sm:h-28 lg:w-32 lg:h-32 transition-transform duration-500 group-hover:scale-105">
                <Image
                  src="/sage_logo.png"
                  alt="Sage IT Co"
                  fill
                  className="object-contain drop-shadow-[0_0_20px_rgba(27,42,92,0.25)]"
                />
              </div>
            </Link>
            <p className="text-zinc-600 text-sm leading-relaxed max-w-md">
              Engineering Intelligence. Empowering Growth. We deliver next-gen technology solutions for enterprises worldwide.
            </p>
          </motion.div>

          {/* Quick Links */}
          <motion.div variants={fadeUp}>
            <h4 className="text-zinc-900 font-semibold mb-4 relative inline-block">
              Quick Links
              <span className="absolute -bottom-1 left-0 w-8 h-0.5 bg-gradient-to-r from-neon-blue to-neon-violet rounded-full" />
            </h4>
            <ul className="space-y-2">
              {navLinks.map((link) => (
                <li key={link.href}>
                  <Link
                    href={link.href}
                    className="group inline-flex items-center gap-1.5 text-zinc-600 hover:text-neon-blue transition-colors text-sm"
                  >
                    <span className="inline-block w-0 group-hover:w-2 h-px bg-neon-blue transition-all duration-300" />
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </motion.div>

          {/* Services */}
          <motion.div variants={fadeUp}>
            <h4 className="text-zinc-900 font-semibold mb-4 relative inline-block">
              Services
              <span className="absolute -bottom-1 left-0 w-8 h-0.5 bg-gradient-to-r from-neon-blue to-neon-violet rounded-full" />
            </h4>
            <ul className="space-y-2 text-sm text-zinc-600">
              <li>Cloud Solutions</li>
              <li>Cybersecurity</li>
              <li>Web Development</li>
              <li>AI Solutions</li>
              <li>Digital Marketing</li>
              <li>Data & Analytics</li>
            </ul>
          </motion.div>

          {/* Contact */}
          <motion.div variants={fadeUp}>
            <h4 className="text-zinc-900 font-semibold mb-4 relative inline-block">
              Contact
              <span className="absolute -bottom-1 left-0 w-8 h-0.5 bg-gradient-to-r from-neon-blue to-neon-violet rounded-full" />
            </h4>
            <ul className="space-y-2 text-sm text-zinc-600">
              <li>info@sageitco.com</li>
              <li>+1 (555) 123-4567</li>
              <li>Lewisville, TX</li>
            </ul>
            <div className="flex gap-3 mt-6">
              {socialLinks.map((s) => (
                <motion.a
                  key={s.label}
                  href={s.href}
                  whileHover={{ y: -3, scale: 1.08 }}
                  whileTap={{ scale: 0.96 }}
                  transition={{ type: "spring", stiffness: 320, damping: 18 }}
                  className="w-10 h-10 rounded-lg bg-white/60 border border-zinc-200 flex items-center justify-center text-zinc-600 hover:text-neon-blue hover:border-neon-blue/30 hover:shadow-glow-blue transition-all"
                  aria-label={s.label}
                >
                  {s.icon[0].toUpperCase()}
                </motion.a>
              ))}
            </div>
          </motion.div>
        </div>

        <motion.div
          variants={fadeUp}
          className="mt-12 sm:mt-16 pt-6 sm:pt-8 border-t border-zinc-200 flex flex-col md:flex-row items-center justify-between gap-3 sm:gap-4 text-center md:text-left"
        >
          <p className="text-zinc-500 text-xs sm:text-sm">
            &copy; {new Date().getFullYear()} Sage IT. All rights reserved.
          </p>
          <div className="flex gap-4 sm:gap-6 text-xs sm:text-sm text-zinc-500">
            <span className="hover:text-zinc-700 transition-colors cursor-pointer">Privacy Policy</span>
            <span className="hover:text-zinc-700 transition-colors cursor-pointer">Terms of Service</span>
          </div>
        </motion.div>
      </motion.div>
    </footer>
  );
}
