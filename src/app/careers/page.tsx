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

  function handleApply(job: Job) {
    setSelectedJob(job);
    setSubmitted(false);
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitted(true);
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
            Join <span className="text-gradient">Sage IT</span>
          </motion.h1>
          <motion.p
            className="text-zinc-600 text-lg"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.2 }}
          >
            Build the future of technology with a team that values innovation, ownership, and impact.
          </motion.p>
        </div>
      </section>

      {/* Perks */}
      <section className="py-20 px-6">
        <div className="max-w-7xl mx-auto">
          <SectionHeading label="Why Sage IT" title="Perks & Culture" />
          <motion.div
            className="grid md:grid-cols-4 gap-6"
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
              <motion.div key={perk.title} variants={fadeUp} className="glass p-6 text-center">
                <div className="text-3xl mb-3">{perk.icon}</div>
                <h3 className="text-zinc-900 font-semibold mb-2">{perk.title}</h3>
                <p className="text-zinc-600 text-sm">{perk.desc}</p>
              </motion.div>
            ))}
          </motion.div>
        </div>
      </section>

      {/* Job listings */}
      <section className="py-20 px-6 bg-grid">
        <div className="max-w-5xl mx-auto">
          <SectionHeading label="Open Positions" title="Current Openings" />
          <motion.div
            className="space-y-6"
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
            className="fixed inset-0 z-[100] flex items-center justify-center p-6 bg-black/60 backdrop-blur-sm"
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
              <GlassCard className="p-8">
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
                        className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-neon-blue/50 transition-colors"
                      />
                      <input
                        type="email"
                        placeholder="Email Address"
                        required
                        className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-neon-blue/50 transition-colors"
                      />
                      <div>
                        <label className="block text-sm text-zinc-600 mb-2">Resume (PDF)</label>
                        <input
                          type="file"
                          accept=".pdf,.doc,.docx"
                          className="w-full text-sm text-zinc-600 file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-semibold file:bg-neon-blue/10 file:text-neon-blue hover:file:bg-neon-blue/20 cursor-pointer"
                        />
                      </div>
                      <textarea
                        placeholder="Why are you interested in this role?"
                        rows={3}
                        className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-neon-blue/50 transition-colors resize-none"
                      />
                      <div className="flex gap-3 pt-2">
                        <GlowButton type="submit">Submit Application</GlowButton>
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
