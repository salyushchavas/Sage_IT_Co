"use client";

import { jobs, Job } from "@/lib/data";
import { fadeUp, staggerContainer } from "@/lib/utils";
import { motion, AnimatePresence } from "framer-motion";
import JobCard from "@/components/ui/JobCard";
import GlassCard from "@/components/ui/GlassCard";
import GlowButton from "@/components/ui/GlowButton";
import SectionHeading from "@/components/ui/SectionHeading";
import { useState } from "react";

export default function CareersPage() {
  const [selectedJob, setSelectedJob] = useState<Job | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [why, setWhy] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState("");

  function handleApply(job: Job) {
    setSelectedJob(job);
    setSubmitted(false);
    setName(""); setEmail(""); setWhy(""); setError("");
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (sending) return;
    setSending(true);
    setError("");
    try {
      // Route the application to the company inbox through the hardened
      // contact endpoint, so it's actually delivered rather than discarded.
      const parts = name.trim().split(/\s+/);
      const res = await fetch("/api/contact", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          firstName: parts[0] || name.trim(),
          lastName: parts.slice(1).join(" ") || "-",
          email: email.trim(),
          service: `Job application: ${selectedJob?.title ?? ""}`,
          message: `Applying for: ${selectedJob?.title ?? ""} (${selectedJob?.department ?? ""}, ${selectedJob?.location ?? ""})\n\n${why.trim()}`,
        }),
      });
      const result = await res.json().catch(() => ({} as { error?: string }));
      if (!res.ok) throw new Error(result.error || "We couldn't send your application. Please try again.");
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
            Join <span className="text-gradient">Sage IT</span>
          </motion.h1>
          <motion.p
            className="text-zinc-600 text-base sm:text-lg"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.2 }}
          >
            Build the future of technology with a team that values innovation, ownership, and impact.
          </motion.p>
        </div>
      </section>

      {/* Perks */}
      <section className="py-16 sm:py-20 px-4 sm:px-6">
        <div className="max-w-7xl mx-auto">
          <SectionHeading label="Why Sage IT" title="Perks & Culture" />
          <motion.div
            className="grid grid-cols-2 md:grid-cols-4 gap-3 sm:gap-5 lg:gap-6"
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true }}
          >
            {[
              { icon: "🌍", title: "Remote-First", desc: "Work from anywhere in the world" },
              { icon: "📚", title: "Learning Budget", desc: "$2,000/year for courses & conferences" },
              { icon: "⚡", title: "Cutting-Edge Tech", desc: "Work with AI, cloud, and modern stacks" },
              { icon: "🤝", title: "Ownership Culture", desc: "Your ideas shape our products" },
            ].map((perk) => (
              <motion.div key={perk.title} variants={fadeUp} className="glass p-4 sm:p-6 text-center">
                <div className="text-2xl sm:text-3xl mb-2 sm:mb-3">{perk.icon}</div>
                <h3 className="text-zinc-900 font-semibold mb-1 sm:mb-2 text-sm sm:text-base">{perk.title}</h3>
                <p className="text-zinc-600 text-xs sm:text-sm">{perk.desc}</p>
              </motion.div>
            ))}
          </motion.div>
        </div>
      </section>

      {/* Job listings */}
      <section className="py-16 sm:py-20 px-4 sm:px-6 bg-grid">
        <div className="max-w-5xl mx-auto">
          <SectionHeading label="Open Positions" title="Current Openings" />
          <motion.div
            className="space-y-4 sm:space-y-6"
            variants={staggerContainer}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true }}
          >
            {jobs.map((job, i) => (
              <motion.div key={job.id} variants={fadeUp} custom={i}>
                <JobCard job={job} onApply={handleApply} />
              </motion.div>
            ))}
          </motion.div>
        </div>
      </section>

      {/* Apply modal */}
      <AnimatePresence>
        {selectedJob && (
          <motion.div
            className="fixed inset-0 z-[100] flex items-center justify-center p-3 sm:p-6 bg-black/60 backdrop-blur-sm overflow-y-auto"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => setSelectedJob(null)}
          >
            <motion.div
              className="w-full max-w-lg"
              initial={{ scale: 0.9, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.9, opacity: 0 }}
              onClick={(e) => e.stopPropagation()}
            >
              <GlassCard className="p-6 sm:p-8 my-auto">
                {submitted ? (
                  <div className="text-center py-8">
                    <div className="text-4xl mb-4">✅</div>
                    <h3 className="text-xl font-bold text-zinc-900 mb-2">Application Submitted!</h3>
                    <p className="text-zinc-600 mb-6">
                      Thanks for applying for {selectedJob.title}. We&apos;ll review your application and get back to you.
                    </p>
                    <GlowButton onClick={() => setSelectedJob(null)}>Close</GlowButton>
                  </div>
                ) : (
                  <>
                    <h3 className="text-xl font-bold text-zinc-900 mb-2">
                      Apply for {selectedJob.title}
                    </h3>
                    <p className="text-zinc-600 text-sm mb-6">{selectedJob.department} &middot; {selectedJob.location}</p>
                    <form onSubmit={handleSubmit} className="space-y-4">
                      <input
                        type="text"
                        placeholder="Full Name"
                        required
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-neon-blue/50 transition-colors"
                      />
                      <input
                        type="email"
                        placeholder="Email Address"
                        required
                        value={email}
                        onChange={(e) => setEmail(e.target.value)}
                        className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-neon-blue/50 transition-colors"
                      />
                      <div>
                        <label className="block text-sm text-zinc-600 mb-2">
                          Email your resume to <a href="mailto:careers@sageitco.com" className="text-neon-blue font-semibold">careers@sageitco.com</a> after applying.
                        </label>
                      </div>
                      <textarea
                        placeholder="Why are you interested in this role?"
                        rows={3}
                        required
                        value={why}
                        onChange={(e) => setWhy(e.target.value)}
                        className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-neon-blue/50 transition-colors resize-none"
                      />
                      {error && (
                        <p className="text-red-400 text-sm bg-red-400/10 border border-red-400/20 rounded-xl px-4 py-3">{error}</p>
                      )}
                      <div className="flex gap-3 pt-2">
                        <GlowButton type="submit" disabled={sending}>
                          {sending ? "Submitting…" : "Submit Application"}
                        </GlowButton>
                        <GlowButton variant="secondary" onClick={() => setSelectedJob(null)}>
                          Cancel
                        </GlowButton>
                      </div>
                    </form>
                  </>
                )}
              </GlassCard>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
}
