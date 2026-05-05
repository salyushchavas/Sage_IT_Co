"use client";

import { useState } from "react";
import { motion } from "framer-motion";
import {
  FileText,
  Lock,
  Loader2,
  CheckCircle2,
  Send,
  Calendar,
} from "lucide-react";
import { submitAssignment } from "@/lib/api";

interface AssignmentItemProps {
  id: number;
  title: string;
  description: string;
  assignmentType: string;
  dueDate: string | null;
  unlocked: boolean;
  index: number;
}

export default function AssignmentItem({
  id,
  title,
  description,
  assignmentType,
  dueDate,
  unlocked,
  index,
}: AssignmentItemProps) {
  const [content, setContent] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);

  const handleSubmit = async () => {
    if (!content.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      await submitAssignment(id, content);
      setSubmitted(true);
      setShowForm(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Submission failed");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3, delay: index * 0.06 }}
      className={`bg-white/60 backdrop-blur-xl border rounded-2xl p-5 space-y-3 transition-all ${
        unlocked ? "border-zinc-200" : "border-zinc-200 opacity-60"
      }`}
    >
      {/* Header */}
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <div className="flex-shrink-0 w-10 h-10 rounded-xl bg-gradient-to-br from-[#D4A017]/20 to-[#0A3D26]/20 border border-zinc-200 flex items-center justify-center">
            {unlocked ? (
              <FileText className="w-5 h-5 text-[#0A3D26]" />
            ) : (
              <Lock className="w-5 h-5 text-zinc-600" />
            )}
          </div>
          <div>
            <h4 className="text-zinc-900 font-medium">{title}</h4>
            <span className="text-xs text-zinc-500 uppercase tracking-wider">
              {assignmentType}
            </span>
          </div>
        </div>
        {submitted && <CheckCircle2 className="w-5 h-5 text-emerald-400 flex-shrink-0" />}
      </div>

      <p className="text-zinc-600 text-sm">{description}</p>

      {dueDate && (
        <p className="flex items-center gap-1.5 text-xs text-zinc-500">
          <Calendar className="w-3.5 h-3.5" />
          Due: {new Date(dueDate).toLocaleDateString()}
        </p>
      )}

      {/* Submit section */}
      {unlocked && !submitted && (
        <>
          {!showForm ? (
            <button
              onClick={() => setShowForm(true)}
              className="px-4 py-2 rounded-xl bg-[#0F5132]/10 border border-[#0F5132]/20 text-[#0F5132] text-sm font-medium hover:bg-[#0F5132]/20 transition-colors flex items-center gap-2"
            >
              <Send className="w-4 h-4" />
              Submit Assignment
            </button>
          ) : (
            <div className="space-y-3 pt-2">
              <textarea
                value={content}
                onChange={(e) => setContent(e.target.value)}
                rows={5}
                placeholder="Write your submission here..."
                className="w-full px-4 py-3 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 text-sm outline-none focus:border-[#0F5132]/40 transition-colors resize-none placeholder-zinc-400"
              />
              {error && <p className="text-red-400 text-sm">{error}</p>}
              <div className="flex items-center gap-3">
                <button
                  onClick={handleSubmit}
                  disabled={!content.trim() || submitting}
                  className="px-5 py-2.5 rounded-xl bg-gradient-to-r from-[#D4A017] to-[#0F5132] text-zinc-900 font-medium text-sm hover:opacity-90 transition-opacity disabled:opacity-40 flex items-center gap-2"
                >
                  {submitting ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin" />
                      Submitting...
                    </>
                  ) : (
                    <>
                      <Send className="w-4 h-4" />
                      Submit
                    </>
                  )}
                </button>
                <button
                  onClick={() => setShowForm(false)}
                  className="px-4 py-2.5 rounded-xl bg-white/60 border border-zinc-200 text-zinc-600 text-sm hover:text-zinc-900 transition-colors"
                >
                  Cancel
                </button>
              </div>
            </div>
          )}
        </>
      )}

      {submitted && (
        <p className="text-emerald-400 text-sm flex items-center gap-1">
          <CheckCircle2 className="w-4 h-4" />
          Assignment submitted successfully
        </p>
      )}
    </motion.div>
  );
}
