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
      <section className="pt-32 pb-20 px-6 bg-grid">
        <div className="max-w-4xl mx-auto text-center">
          <motion.h1
            className="text-4xl md:text-6xl font-bold text-zinc-900 mb-6"
            initial={{ opacity: 0, y: 30 }}
            animate={{ opacity: 1, y: 0 }}
          >
            Get in <span className="text-gradient">Touch</span>
          </motion.h1>
          <motion.p
            className="text-zinc-600 text-lg"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.2 }}
          >
            Have a project in mind? Let&apos;s build something extraordinary together.
          </motion.p>
        </div>
      </section>

      <section className="py-20 px-6">
        <div className="max-w-7xl mx-auto">
          <motion.div
            className="grid lg:grid-cols-2 gap-12"
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true }}
          >
            {/* Form */}
            <motion.div variants={slideInLeft}>
              <GlassCard className="p-10">
                {submitted ? (
                  <div className="text-center py-12">
                    <div className="text-5xl mb-4">🚀</div>
                    <h3 className="text-2xl font-bold text-zinc-900 mb-3">Message Sent!</h3>
                    <p className="text-zinc-600 mb-6">
                      Thank you for reaching out. Our team will get back to you within 24 hours.
                    </p>
                    <GlowButton onClick={() => setSubmitted(false)}>Send Another</GlowButton>
                  </div>
                ) : (
                  <form onSubmit={handleSubmit} className="space-y-5">
                    <h3 className="text-2xl font-bold text-zinc-900 mb-6">Send Us a Message</h3>
                    <div className="grid md:grid-cols-2 gap-4">
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
            <motion.div variants={slideInRight} className="space-y-8">
              <GlassCard className="p-8">
                <h3 className="text-xl font-bold text-zinc-900 mb-6">Contact Information</h3>
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
                      <p className="text-zinc-600 text-sm">
                        HITEC City, Hyderabad<br />
                        Telangana, India 500081
                      </p>
                    </div>
                  </div>
                </div>
              </GlassCard>

              {/* Map placeholder */}
              <GlassCard className="p-0 overflow-hidden h-64">
                <iframe
                  src="https://www.google.com/maps/embed?pb=!1m18!1m12!1m3!1d3806.313291823474!2d78.37148831485!3d17.44734688804!2m3!1f0!2f0!3f0!3m2!1i1024!2i768!4f13.1!3m3!1m2!1s0x3bcb93dc8c5d69df%3A0x19688beb557fa0ee!2sHITEC%20City%2C%20Hyderabad!5e0!3m2!1sen!2sin!4v1700000000000!5m2!1sen!2sin"
                  width="100%"
                  height="100%"
                  style={{ border: 0, filter: "invert(90%) hue-rotate(180deg)" }}
                  allowFullScreen
                  loading="lazy"
                  referrerPolicy="no-referrer-when-downgrade"
                  title="Sage IT Office Location"
                />
              </GlassCard>

              {/* Social links */}
              <GlassCard className="p-8">
                <h3 className="text-lg font-bold text-zinc-900 mb-4">Follow Us</h3>
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
