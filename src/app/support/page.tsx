"use client";

import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { ChevronDown, Mail, MessageCircle, FileText, HelpCircle } from "lucide-react";
import { cn, fadeUp, staggerContainer } from "@/lib/utils";

const faqs = [
  {
    question: "How do I enroll in a course?",
    answer:
      "Simply browse our course catalog, select a course you are interested in, and click the 'Enroll Now' button. If you are not logged in, you will be prompted to sign in or create an account first.",
  },
  {
    question: "Are certificates provided upon completion?",
    answer:
      "Yes! Once you complete all lessons, pass all quizzes, and submit all required assignments for a course, you can generate a certificate of completion from the course page.",
  },
  {
    question: "Can I get a refund for a paid course?",
    answer:
      "We offer a 7-day refund policy for paid courses and subscriptions. Please contact our support team with your payment details and we will process your refund within 3-5 business days.",
  },
  {
    question: "How do I become an instructor?",
    answer:
      "Go to your Dashboard and click 'Request Instructor Access'. An admin will review your request and you will be notified once approved. You can then start creating and publishing courses.",
  },
  {
    question: "What payment methods are supported?",
    answer:
      "We use Razorpay for payment processing, which supports UPI, credit/debit cards, net banking, and popular wallets like Paytm and PhonePe.",
  },
];

const supportOptions = [
  {
    icon: Mail,
    title: "Email Support",
    description: "Get help via email within 24 hours",
    action: "support@sageit.com",
    href: "mailto:support@sageit.com",
    color: "#1B2A5C",
  },
  {
    icon: MessageCircle,
    title: "Live Chat",
    description: "Chat with us in real-time during business hours",
    action: "Start Chat",
    href: "#",
    color: "#C87D5C",
  },
  {
    icon: FileText,
    title: "Documentation",
    description: "Browse our comprehensive knowledge base",
    action: "View Docs",
    href: "#",
    color: "#0F1F44",
  },
];

export default function SupportPage() {
  const [openFaq, setOpenFaq] = useState<number | null>(null);

  return (
    <section className="min-h-screen pt-32 pb-20 px-6">
      <div className="mx-auto max-w-4xl">
        {/* Header */}
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} className="text-center mb-16">
          <span className="inline-flex items-center gap-1.5 text-sm text-[#1B2A5C] font-medium mb-4">
            <HelpCircle className="w-4 h-4" /> Support
          </span>
          <h1 className="text-4xl sm:text-5xl font-bold text-zinc-900 mb-4">
            How can we{" "}
            <span className="bg-gradient-to-r from-[#1B2A5C] to-[#C87D5C] bg-clip-text text-transparent">
              help?
            </span>
          </h1>
          <p className="text-zinc-600 max-w-lg mx-auto">
            Find answers to common questions or reach out to our support team.
          </p>
        </motion.div>

        {/* Support options */}
        <motion.div variants={staggerContainer} initial="hidden" animate="visible" className="grid gap-4 sm:grid-cols-3 mb-16">
          {supportOptions.map((opt) => {
            const Icon = opt.icon;
            return (
              <motion.a
                key={opt.title}
                href={opt.href}
                variants={fadeUp}
                className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-6 text-center hover:border-zinc-300 transition-all group"
              >
                <div
                  className="w-12 h-12 rounded-xl flex items-center justify-center mx-auto mb-4"
                  style={{ backgroundColor: `${opt.color}15`, border: `1px solid ${opt.color}30` }}
                >
                  <Icon className="w-5 h-5" style={{ color: opt.color }} />
                </div>
                <h3 className="text-zinc-900 font-semibold mb-1">{opt.title}</h3>
                <p className="text-xs text-zinc-600 mb-3">{opt.description}</p>
                <span className="text-sm font-medium text-[#1B2A5C] group-hover:underline">
                  {opt.action}
                </span>
              </motion.a>
            );
          })}
        </motion.div>

        {/* FAQ */}
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.2 }}>
          <h2 className="text-2xl font-bold text-zinc-900 text-center mb-8">
            Frequently Asked Questions
          </h2>
          <div className="space-y-3">
            {faqs.map((faq, i) => {
              const isOpen = openFaq === i;
              return (
                <div
                  key={i}
                  className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden"
                >
                  <button
                    onClick={() => setOpenFaq(isOpen ? null : i)}
                    className="w-full flex items-center justify-between p-5 text-left"
                  >
                    <span className="text-sm font-semibold text-zinc-900 pr-4">
                      {faq.question}
                    </span>
                    <ChevronDown
                      className={cn(
                        "w-4 h-4 text-zinc-600 shrink-0 transition-transform duration-200",
                        isOpen && "rotate-180"
                      )}
                    />
                  </button>
                  <AnimatePresence>
                    {isOpen && (
                      <motion.div
                        initial={{ height: 0, opacity: 0 }}
                        animate={{ height: "auto", opacity: 1 }}
                        exit={{ height: 0, opacity: 0 }}
                        transition={{ duration: 0.2 }}
                      >
                        <div className="px-5 pb-5 text-sm text-zinc-600 leading-relaxed border-t border-zinc-200 pt-3">
                          {faq.answer}
                        </div>
                      </motion.div>
                    )}
                  </AnimatePresence>
                </div>
              );
            })}
          </div>
        </motion.div>
      </div>
    </section>
  );
}
