"use client";

import { Job } from "@/lib/data";
import GlassCard from "./GlassCard";
import GlowButton from "./GlowButton";

interface JobCardProps {
  job: Job;
  onApply: (job: Job) => void;
}

export default function JobCard({ job, onApply }: JobCardProps) {
  return (
    <GlassCard className="p-8" glowColor="#0F5132">
      <div className="flex flex-col md:flex-row md:items-start md:justify-between gap-4">
        <div className="flex-1">
          <h3 className="text-xl font-bold text-zinc-900">{job.title}</h3>
          <div className="flex flex-wrap gap-3 mt-2">
            <span className="text-xs px-3 py-1 rounded-full bg-neon-blue/10 text-neon-blue border border-neon-blue/20">
              {job.department}
            </span>
            <span className="text-xs px-3 py-1 rounded-full bg-neon-violet/10 text-neon-violet border border-neon-violet/20">
              {job.location}
            </span>
            <span className="text-xs px-3 py-1 rounded-full bg-white/60 text-zinc-500 border border-zinc-200">
              {job.type}
            </span>
          </div>
          <p className="text-zinc-600 mt-4 leading-relaxed">{job.description}</p>
          <div className="flex flex-wrap gap-2 mt-4">
            {job.requirements.map((r) => (
              <span key={r} className="text-xs px-2 py-1 rounded bg-white/60 text-zinc-600">
                {r}
              </span>
            ))}
          </div>
        </div>
        <GlowButton onClick={() => onApply(job)} className="shrink-0 mt-4 md:mt-0">
          Apply Now
        </GlowButton>
      </div>
    </GlassCard>
  );
}
