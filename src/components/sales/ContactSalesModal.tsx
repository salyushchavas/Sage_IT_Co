"use client";

import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { useRouter } from "next/navigation";
import { Loader2, X, MessageSquare } from "lucide-react";
import { createSalesInquiry } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";

interface Props {
  isOpen: boolean;
  onClose: () => void;
  courseId: number;
  courseTitle: string;
  listedPrice: number;
}

const BUDGET_OPTIONS = [
  "Under ₹1,000",
  "₹1,000-₹2,000",
  "₹2,000-₹3,000",
  "₹3,000+",
  "Flexible",
];

export function ContactSalesModal({ isOpen, onClose, courseId, courseTitle, listedPrice }: Props) {
  const router = useRouter();
  const { toast } = useToast();
  const [message, setMessage] = useState("");
  const [budget, setBudget] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async () => {
    if (!message.trim()) {
      setError("Please enter a message.");
      return;
    }
    setSubmitting(true);
    setError("");
    try {
      const inquiry = await createSalesInquiry({
        courseId,
        subject: courseTitle,
        budgetRange: budget || undefined,
        message: message.trim(),
      });
      toast("success", "Inquiry sent! The instructor will respond within 24 hours.");
      onClose();
      // Drop them on the conversation so they can see what they sent.
      router.push(`/messages/${inquiry.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to send inquiry");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AnimatePresence>
      {isOpen && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-50 flex items-center justify-center px-4 bg-black/40 backdrop-blur-sm"
          onClick={onClose}
        >
          <motion.div
            initial={{ scale: 0.95, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            exit={{ scale: 0.95, opacity: 0 }}
            transition={{ duration: 0.18 }}
            onClick={(e) => e.stopPropagation()}
            className="bg-white rounded-2xl shadow-xl w-full max-w-md p-6"
          >
            <div className="flex items-start justify-between mb-4">
              <div className="flex items-center gap-2.5">
                <div className="w-9 h-9 rounded-full bg-sage-navy/10 flex items-center justify-center">
                  <MessageSquare size={16} className="text-sage-navy" />
                </div>
                <div>
                  <h2 className="text-lg font-bold text-gray-900">Contact Sales</h2>
                  <p className="text-xs text-gray-500">{courseTitle}</p>
                </div>
              </div>
              <button
                onClick={onClose}
                className="text-gray-400 hover:text-gray-600 cursor-pointer"
                aria-label="Close"
              >
                <X size={18} />
              </button>
            </div>

            <div className="bg-gray-50 rounded-lg px-3 py-2 text-xs text-gray-600 mb-5">
              Listed price: <span className="font-semibold text-gray-900">₹{listedPrice.toLocaleString("en-IN")}</span>
            </div>

            <div className="space-y-4">
              <div>
                <label className="text-xs font-medium text-gray-700">
                  Your message <span className="text-red-500">*</span>
                </label>
                <textarea
                  value={message}
                  onChange={(e) => { setMessage(e.target.value); setError(""); }}
                  rows={4}
                  placeholder="I'm interested in this course but looking for a better deal..."
                  className="mt-1 w-full px-3 py-2 rounded-lg border border-gray-300 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
                />
              </div>

              <div>
                <label className="text-xs font-medium text-gray-700">Your budget range (optional)</label>
                <select
                  value={budget}
                  onChange={(e) => setBudget(e.target.value)}
                  className="mt-1 w-full px-3 py-2 rounded-lg border border-gray-300 bg-white text-sm"
                >
                  <option value="">— Choose budget —</option>
                  {BUDGET_OPTIONS.map((opt) => (
                    <option key={opt} value={opt}>{opt}</option>
                  ))}
                </select>
              </div>

              {error && <p className="text-xs text-red-700">{error}</p>}

              <div className="flex items-center justify-end gap-2 pt-2">
                <button
                  onClick={onClose}
                  disabled={submitting}
                  className="px-4 py-2 rounded-lg text-sm font-medium text-gray-600 border border-gray-300 hover:bg-gray-50 disabled:opacity-50 transition cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSubmit}
                  disabled={submitting}
                  className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
                >
                  {submitting && <Loader2 size={14} className="animate-spin" />}
                  Send Inquiry
                </button>
              </div>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
