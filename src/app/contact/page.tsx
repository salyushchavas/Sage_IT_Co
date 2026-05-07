"use client";

import { socialLinks } from "@/lib/data";
import { fadeUp, staggerContainer, slideInLeft, slideInRight } from "@/lib/utils";
import { motion } from "framer-motion";
import GlassCard from "@/components/ui/GlassCard";
import GlowButton from "@/components/ui/GlowButton";
import { useState } from "react";

export default function ContactPage() {
  const [submitted, setSubmitted] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState("");

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setSending(true);
    setError("");

    const form = e.currentTarget;
    const formData = new FormData(form);
    const data = {
      firstName: formData.get("firstName") as string,
      lastName: formData.get("lastName") as string,
      email: formData.get("email") as string,
      company: formData.get("company") as string,
      service: formData.get("service") as string,
      message: formData.get("message") as string,
    };

    try {
      const res = await fetch("/api/contact", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
      });

      const result = await res.json();
      if (!res.ok) throw new Error(result.error || "Failed to send");
      setSubmitted(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong. Please try again.");
    } finally {
      setSending(false);
    }
  }

  return (
    <>
      <section className="pt-28 sm:pt-32 pb-16 sm:pb-20 px-4 sm:px-6 bg-grid">
        <div className="max-w-4xl mx-auto text-center">
          <motion.h1
            className="text-3xl sm:text-4xl md:text-6xl font-bold text-zinc-900 mb-4 sm:mb-6"
            initial={{ opacity: 0, y: 30 }}
            animate={{ opacity: 1, y: 0 }}
          >
            Get in <span className="text-gradient">Touch</span>
          </motion.h1>
          <motion.p
            className="text-zinc-600 text-base sm:text-lg"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.2 }}
          >
            Have a project in mind? Let&apos;s build something extraordinary together.
          </motion.p>
        </div>
      </section>

      <section className="py-16 sm:py-20 px-4 sm:px-6">
        <div className="max-w-7xl mx-auto">
          <motion.div
            className="grid lg:grid-cols-2 gap-6 sm:gap-8 lg:gap-12"
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true }}
          >
            {/* Form */}
            <motion.div variants={slideInLeft}>
              <GlassCard className="p-6 sm:p-8 md:p-10">
                {submitted ? (
                  <div className="text-center py-10 sm:py-12">
                    <div className="text-4xl sm:text-5xl mb-3 sm:mb-4">🚀</div>
                    <h3 className="text-xl sm:text-2xl font-bold text-zinc-900 mb-2 sm:mb-3">Message Sent!</h3>
                    <p className="text-zinc-600 mb-5 sm:mb-6 text-sm sm:text-base">
                      Thank you for reaching out. Our team will get back to you within 24 hours.
                    </p>
                    <GlowButton onClick={() => setSubmitted(false)}>Send Another</GlowButton>
                  </div>
                ) : (
                  <form onSubmit={handleSubmit} className="space-y-4 sm:space-y-5">
                    <h3 className="text-xl sm:text-2xl font-bold text-zinc-900 mb-4 sm:mb-6">Send Us a Message</h3>
                    <div className="grid sm:grid-cols-2 gap-3 sm:gap-4">
                      <input
                        name="firstName"
                        type="text"
                        placeholder="First Name"
                        required
                        className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-neon-blue/50 transition-colors"
                      />
                      <input
                        name="lastName"
                        type="text"
                        placeholder="Last Name"
                        required
                        className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-neon-blue/50 transition-colors"
                      />
                    </div>
                    <input
                      name="email"
                      type="email"
                      placeholder="Email Address"
                      required
                      className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-neon-blue/50 transition-colors"
                    />
                    <input
                      name="company"
                      type="text"
                      placeholder="Company (optional)"
                      className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-neon-blue/50 transition-colors"
                    />
                    <select name="service" className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-600 focus:outline-none focus:border-neon-blue/50 transition-colors">
                      <option value="">Select a Service</option>
                      <option value="Cloud Solutions">Cloud Solutions</option>
                      <option value="Cybersecurity">Cybersecurity</option>
                      <option value="Web Development">Web Development</option>
                      <option value="AI Solutions">AI Solutions</option>
                      <option value="Digital Marketing">Digital Marketing</option>
                      <option value="Data & Analytics">Data & Analytics</option>
                    </select>
                    <textarea
                      name="message"
                      placeholder="Tell us about your project..."
                      rows={5}
                      required
                      className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-neon-blue/50 transition-colors resize-none"
                    />
                    {error && (
                      <p className="text-red-400 text-sm bg-red-400/10 border border-red-400/20 rounded-xl px-4 py-3">{error}</p>
                    )}
                    <GlowButton type="submit" className="w-full">
                      {sending ? "Sending..." : "Send Message"}
                    </GlowButton>
                  </form>
                )}
              </GlassCard>
            </motion.div>

            {/* Contact info */}
            <motion.div variants={slideInRight} className="space-y-6 sm:space-y-8">
              <GlassCard className="p-6 sm:p-8">
                <h3 className="text-lg sm:text-xl font-bold text-zinc-900 mb-4 sm:mb-6">Contact Information</h3>
                <div className="space-y-6">
                  <div className="flex items-start gap-4">
                    <div className="w-10 h-10 rounded-xl bg-neon-blue/10 flex items-center justify-center text-neon-blue shrink-0">
                      📧
                    </div>
                    <div>
                      <p className="text-zinc-900 font-medium">Email</p>
                      <p className="text-zinc-600 text-sm">info@sageitco.com</p>
                    </div>
                  </div>
                  <div className="flex items-start gap-4">
                    <div className="w-10 h-10 rounded-xl bg-neon-violet/10 flex items-center justify-center text-neon-violet shrink-0">
                      📱
                    </div>
                    <div>
                      <p className="text-zinc-900 font-medium">Phone</p>
                      <p className="text-zinc-600 text-sm">+1 (555) 123-4567</p>
                    </div>
                  </div>
                  <div className="flex items-start gap-4">
                    <div className="w-10 h-10 rounded-xl bg-neon-cyan/10 flex items-center justify-center text-neon-cyan shrink-0">
                      📍
                    </div>
                    <div>
                      <p className="text-zinc-900 font-medium">Office</p>
                      <p className="text-zinc-900 text-sm font-semibold mt-1">SAGEITCO LLC</p>
                      <p className="text-zinc-600 text-sm">
                        4400 State Hwy 121, Suite #324<br />
                        Lewisville, TX 75056
                      </p>
                    </div>
                  </div>
                </div>
              </GlassCard>

              {/* Map */}
              <GlassCard className="p-0 overflow-hidden h-56 sm:h-64">
                <iframe
                  src="https://www.google.com/maps?q=4400+State+Hwy+121+Suite+324+Lewisville+TX+75056&hl=en&z=15&output=embed"
                  width="100%"
                  height="100%"
                  style={{ border: 0 }}
                  allowFullScreen
                  loading="lazy"
                  referrerPolicy="no-referrer-when-downgrade"
                  title="SAGEITCO LLC office at 4400 State Hwy 121 Suite #324, Lewisville, TX 75056"
                />
              </GlassCard>

              {/* Social links */}
              <GlassCard className="p-6 sm:p-8">
                <h3 className="text-base sm:text-lg font-bold text-zinc-900 mb-3 sm:mb-4">Follow Us</h3>
                <div className="flex gap-3">
                  {socialLinks.map((s) => (
                    <a
                      key={s.label}
                      href={s.href}
                      className="w-12 h-12 rounded-xl bg-white/60 border border-zinc-200 flex items-center justify-center text-zinc-600 hover:text-neon-blue hover:border-neon-blue/30 transition-all duration-300 hover:shadow-glow-blue"
                      aria-label={s.label}
                    >
                      {s.icon[0].toUpperCase()}
                    </a>
                  ))}
                </div>
              </GlassCard>
            </motion.div>
          </motion.div>
        </div>
      </section>
    </>
  );
}
