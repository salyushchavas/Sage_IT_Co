"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { Check, X, Loader2, Sparkles } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { createOrder, verifyPayment } from "@/lib/api";
import { PRICING_TIERS } from "@/lib/constants";
import { cn, staggerContainer, fadeUp } from "@/lib/utils";

declare global {
  interface Window {
    Razorpay: new (options: Record<string, unknown>) => { open: () => void };
  }
}

export default function PricingPage() {
  const router = useRouter();
  const { isAuthenticated } = useAuth();
  const [loadingPlan, setLoadingPlan] = useState<string | null>(null);

  const handleSubscribe = async (planName: string) => {
    if (planName === "Free") { router.push("/signup"); return; }
    if (planName === "Enterprise") { router.push("/contact"); return; }
    if (!isAuthenticated) { router.push(`/login?redirect=/pricing`); return; }

    setLoadingPlan(planName);
    try {
      const order = (await createOrder(planName.toLowerCase())) as {
        razorpayOrderId: string; amount: number; currency: string;
      };

      const options = {
        key: process.env.NEXT_PUBLIC_RAZORPAY_KEY_ID,
        amount: order.amount,
        currency: order.currency || "INR",
        name: "Sage IT",
        description: `${planName} Plan Subscription`,
        order_id: order.razorpayOrderId,
        handler: async (response: { razorpay_order_id: string; razorpay_payment_id: string; razorpay_signature: string }) => {
          try {
            await verifyPayment({
              razorpayOrderId: response.razorpay_order_id,
              razorpayPaymentId: response.razorpay_payment_id,
              razorpaySignature: response.razorpay_signature,
            });
            router.push("/dashboard");
          } catch { alert("Payment verification failed. Contact support."); }
        },
        theme: { color: "#C87D5C" },
      };

      const rzp = new window.Razorpay(options);
      rzp.open();
    } catch (err) {
      alert(err instanceof Error ? err.message : "Failed to create order");
    } finally { setLoadingPlan(null); }
  };

  // Feature comparison
  const allFeatures = [
    "Access to free courses",
    "Community forum access",
    "Basic progress tracking",
    "Course bookmarks",
    "Unlimited course access",
    "Certificates of completion",
    "Priority support",
    "Offline downloads",
    "Project reviews",
    "Team management dashboard",
    "Custom learning paths",
    "Dedicated account manager",
    "API access",
    "SSO integration",
    "Analytics & reporting",
  ];

  const tierFeatureSet = PRICING_TIERS.map((t) => new Set<string>(t.features));

  return (
    <section className="min-h-screen pt-28 sm:pt-32 pb-16 sm:pb-20 px-4 sm:px-6">
      <div className="mx-auto max-w-6xl">
        {/* Header */}
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} className="text-center mb-10 sm:mb-16">
          <span className="inline-flex items-center gap-1.5 text-xs sm:text-sm text-[#1B2A5C] font-medium mb-3 sm:mb-4">
            <Sparkles className="w-4 h-4" /> Pricing
          </span>
          <h1 className="text-3xl sm:text-4xl md:text-5xl font-bold text-zinc-900 mb-3 sm:mb-4">
            Choose your{" "}
            <span className="bg-gradient-to-r from-[#1B2A5C] to-[#C87D5C] bg-clip-text text-transparent">plan</span>
          </h1>
          <p className="text-zinc-600 max-w-lg mx-auto text-sm sm:text-base">
            Start for free and upgrade as you grow. Cancel anytime.
          </p>
        </motion.div>

        {/* Cards */}
        <motion.div variants={staggerContainer} initial="hidden" animate="visible" className="grid gap-5 sm:gap-6 sm:grid-cols-2 md:grid-cols-3">
          {PRICING_TIERS.map((tier) => (
            <motion.div key={tier.name} variants={fadeUp}
              className={cn(
                "bg-white/60 backdrop-blur-xl border rounded-2xl p-6 sm:p-8 relative flex flex-col",
                tier.highlighted
                  ? "border-[#C87D5C]/50 shadow-[0_0_40px_rgba(200,125,92,0.15)]"
                  : "border-zinc-200"
              )}>
              {tier.highlighted && (
                <span className="absolute -top-3 left-1/2 -translate-x-1/2 px-3 sm:px-4 py-1 rounded-full btn-fill text-white text-xs font-semibold whitespace-nowrap">
                  Most Popular
                </span>
              )}

              <h3 className="text-base sm:text-lg font-bold text-zinc-900 mb-1">{tier.name}</h3>
              <p className="text-xs sm:text-sm text-zinc-600 mb-4 sm:mb-6">{tier.description}</p>

              <div className="mb-4 sm:mb-6">
                <span className="text-3xl sm:text-4xl font-bold text-zinc-900">
                  {tier.price === 0 ? "Free" : `₹${tier.price}`}
                </span>
                {tier.price > 0 && <span className="text-zinc-500 text-sm">/{tier.interval}</span>}
              </div>

              {/* Features */}
              <ul className="space-y-2.5 sm:space-y-3 mb-6 sm:mb-8 flex-1">
                {tier.features.map((f) => (
                  <li key={f} className="flex items-center gap-2 text-xs sm:text-sm text-zinc-500">
                    <Check className="w-4 h-4 text-[#1B2A5C] shrink-0" /> {f}
                  </li>
                ))}
              </ul>

              <button
                onClick={() => handleSubscribe(tier.name)}
                disabled={loadingPlan === tier.name}
                className={cn(
                  "w-full py-3 rounded-xl text-sm font-semibold transition-all flex items-center justify-center gap-2 min-h-[44px]",
                  tier.highlighted
                    ? "btn-fill text-white hover:shadow-[0_0_30px_rgba(200,125,92,0.3)] hover:scale-[1.02]"
                    : "bg-white/60 border border-zinc-200 text-zinc-900 hover:border-[#1B2A5C]/30 hover:bg-white/80",
                  "disabled:opacity-50"
                )}
              >
                {loadingPlan === tier.name ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
                {tier.cta}
              </button>
            </motion.div>
          ))}
        </motion.div>

        {/* Feature comparison table */}
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.4 }} className="mt-14 sm:mt-20">
          <h2 className="text-xl sm:text-2xl font-bold text-zinc-900 text-center mb-6 sm:mb-10">Feature Comparison</h2>
          <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-xs sm:text-sm min-w-[480px]">
                <thead>
                  <tr className="border-b border-zinc-200">
                    <th className="text-left px-3 sm:px-6 py-3 sm:py-4 text-zinc-600 font-medium">Feature</th>
                    {PRICING_TIERS.map((t) => (
                      <th key={t.name} className="px-3 sm:px-6 py-3 sm:py-4 text-center text-zinc-900 font-medium">{t.name}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {allFeatures.map((f) => (
                    <tr key={f} className="border-b border-zinc-200">
                      <td className="px-3 sm:px-6 py-2.5 sm:py-3 text-zinc-500">{f}</td>
                      {tierFeatureSet.map((set, i) => (
                        <td key={i} className="px-3 sm:px-6 py-2.5 sm:py-3 text-center">
                          {set.has(f)
                            ? <Check className="w-4 h-4 text-[#1B2A5C] mx-auto" />
                            : <X className="w-4 h-4 text-zinc-500 mx-auto" />}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </motion.div>
      </div>
    </section>
  );
}
