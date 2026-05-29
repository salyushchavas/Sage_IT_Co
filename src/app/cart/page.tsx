"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import { Loader2, Trash2, ShoppingCart, ArrowRight, ShieldCheck, Ticket, X, CheckCircle2 } from "lucide-react";
import { getCart, removeFromCart, clearCart, checkoutCart, validateCoupon, type CouponValidation } from "@/lib/api";
import { friendlyEnrollmentError } from "@/lib/utils";
import { useAuth } from "@/lib/auth-context";
import ProfileGateModal from "@/components/dashboard/ProfileGateModal";

interface CartCourse {
  id: number;
  title: string;
  price: number;
  isFree: boolean;
  type?: string; // "COURSE" | "SERVICE"
  instructor: { id: number; fullName: string } | null;
  trainer?: { id: number; fullName: string } | null;
  thumbnailUrl: string | null;
}

export default function CartPage() {
  const router = useRouter();
  const { user, isLoading: authLoading } = useAuth();
  const isAdmin = user?.role?.toUpperCase() === "ADMIN";
  const [items, setItems] = useState<CartCourse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [checkingOut, setCheckingOut] = useState(false);

  // Coupon state — only set once a code has been validated server-side.
  // We hold the *applied* coupon (validation result) plus a draft input
  // and validation error. couponInput is what the user is currently
  // typing; appliedCoupon is what's locked in for checkout.
  const [couponInput, setCouponInput] = useState("");
  const [couponError, setCouponError] = useState("");
  const [validatingCoupon, setValidatingCoupon] = useState(false);
  const [appliedCoupon, setAppliedCoupon] = useState<CouponValidation | null>(null);
  const [showProfileGate, setShowProfileGate] = useState(false);

  const fetchCart = async () => {
    setLoading(true);
    setError("");
    try {
      const data = await getCart();
      setItems(data as CartCourse[]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load cart");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchCart();
  }, []);

  const handleRemove = async (courseId: number) => {
    try {
      await removeFromCart(courseId);
      setItems((prev) => prev.filter((c) => c.id !== courseId));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to remove item");
    }
  };

  const handleClear = async () => {
    try {
      await clearCart();
      setItems([]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to clear cart");
    }
  };

  const handleCheckout = async () => {
    // Gate on profile completion -- participant lifecycle steps must
    // be done before we let the user transact. The modal points them
    // at the checklist; admins/instructors bypass.
    const pct = user?.profileCompletionPct ?? 100;
    const needsGate = !isAdmin && pct < 100;
    if (needsGate) {
      setShowProfileGate(true);
      return;
    }
    setCheckingOut(true);
    setError("");
    try {
      await checkoutCart(appliedCoupon?.code ?? null);
      router.push("/dashboard");
    } catch (err) {
      setError(friendlyEnrollmentError(err));
      setCheckingOut(false);
    }
  };

  const subtotal = items.reduce((sum, c) => sum + (c.price || 0), 0);
  const discount = appliedCoupon ? appliedCoupon.discountAmount : 0;
  const total = Math.max(0, subtotal - discount);

  const handleApplyCoupon = async () => {
    const code = couponInput.trim();
    if (!code) {
      setCouponError("Enter a code first.");
      return;
    }
    setValidatingCoupon(true);
    setCouponError("");
    try {
      const result = await validateCoupon(code, subtotal);
      setAppliedCoupon(result);
      setCouponInput("");
    } catch (err) {
      setCouponError(err instanceof Error ? err.message : "Invalid coupon");
      setAppliedCoupon(null);
    } finally {
      setValidatingCoupon(false);
    }
  };

  // Cart contents change → re-validate any applied coupon against the
  // new subtotal so a min-order-amount rule is re-checked. We swallow
  // failures by clearing the coupon — the user gets a fresh message
  // when they try to re-apply.
  useEffect(() => {
    if (!appliedCoupon) return;
    if (items.length === 0) {
      setAppliedCoupon(null);
      return;
    }
    let cancelled = false;
    validateCoupon(appliedCoupon.code, subtotal)
      .then((res) => { if (!cancelled) setAppliedCoupon(res); })
      .catch(() => { if (!cancelled) setAppliedCoupon(null); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subtotal]);

  // Admin supervises — they don't shop. Show a message instead of the cart.
  if (!authLoading && isAdmin) {
    return (
      <section className="mx-auto max-w-2xl px-6 pt-32 pb-20 min-h-screen" style={{ backgroundColor: "#F0EDE8" }}>
        <div className="bg-white rounded-2xl border border-amber-200 shadow-sm p-8 text-center">
          <div className="w-14 h-14 rounded-full bg-amber-100 mx-auto flex items-center justify-center mb-4">
            <ShieldCheck size={26} className="text-amber-500" />
          </div>
          <h1 className="text-2xl font-bold text-gray-900 mb-2">
            Admin accounts don&apos;t use the cart
          </h1>
          <p className="text-sm text-gray-600 leading-relaxed mb-6 max-w-md mx-auto">
            You supervise the platform — admins have direct preview access to all
            courses and services without enrolling or purchasing.
          </p>
          <div className="flex items-center justify-center gap-3">
            <button
              onClick={() => router.push("/admin")}
              className="inline-flex items-center gap-1.5 px-5 py-2.5 rounded-lg bg-sage-navy text-white text-sm font-semibold hover:bg-[#134E4A] transition"
            >
              Go to Admin <ArrowRight size={14} />
            </button>
            <Link
              href="/courses"
              className="inline-flex items-center gap-1.5 px-5 py-2.5 rounded-lg border border-gray-300 text-sm font-medium text-gray-700 hover:bg-gray-50 transition"
            >
              Browse Courses
            </Link>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="mx-auto max-w-4xl px-6 pt-32 pb-20 min-h-screen" style={{ backgroundColor: "#F0EDE8" }}>
      <h1 className="text-4xl font-bold text-sage-navy mb-8">Your Cart</h1>

      {loading && (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="animate-spin text-sage-navy" size={32} />
        </div>
      )}

      {error && (
        <div className="bg-red-50 text-red-700 px-4 py-3 rounded-xl text-sm mb-6">
          {error}
        </div>
      )}

      {!loading && items.length === 0 && (
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          className="text-center py-20"
        >
          <ShoppingCart className="mx-auto text-gray-300 mb-4" size={64} />
          <p className="text-gray-500 text-lg mb-4">Your cart is empty</p>
          <Link
            href="/courses"
            className="inline-flex items-center gap-2 bg-sage-navy text-white px-6 py-2.5 rounded-full text-sm font-semibold hover:bg-sage-navy-deep transition-colors"
          >
            Browse Courses <ArrowRight size={16} />
          </Link>
        </motion.div>
      )}

      {!loading && items.length > 0 && (
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
          <AnimatePresence mode="popLayout">
            {items.map((course) => (
              <motion.div
                key={course.id}
                layout
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: 20, height: 0 }}
                transition={{ duration: 0.3 }}
                className="flex items-center gap-4 bg-white rounded-xl p-4 mb-3 shadow-sm border border-gray-100"
              >
                <div className="w-16 h-16 rounded-lg bg-gradient-to-br from-sage-navy/10 to-sage-navy-deep/20 flex items-center justify-center flex-shrink-0 overflow-hidden">
                  {course.thumbnailUrl ? (
                    <img src={course.thumbnailUrl} alt={course.title} className="w-full h-full object-cover" />
                  ) : (
                    <ShoppingCart size={20} className="text-sage-navy/40" />
                  )}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <h3 className="font-semibold text-gray-900 truncate">{course.title}</h3>
                    <span className={`text-[10px] font-semibold uppercase tracking-wide px-2 py-0.5 rounded-full ${
                      course.type === "SERVICE"
                        ? "bg-sage-copper/20 text-sage-copper"
                        : "bg-sage-navy/10 text-sage-navy"
                    }`}>
                      {course.type === "SERVICE" ? "Service" : "Course"}
                    </span>
                  </div>
                  <p className="text-sm text-gray-500">
                    {course.type === "SERVICE"
                      ? `by ${course.trainer?.fullName ?? course.instructor?.fullName ?? "Sage IT Co Trainer"}`
                      : course.instructor?.fullName || "Unknown instructor"}
                  </p>
                </div>
                <p className="font-semibold text-gray-900 whitespace-nowrap">
                  {course.price > 0 ? `₹${course.price}` : "Free"}
                </p>
                <button
                  onClick={() => handleRemove(course.id)}
                  className="p-2 text-gray-400 hover:text-red-500 transition-colors cursor-pointer"
                  title="Remove from cart"
                >
                  <Trash2 size={18} />
                </button>
              </motion.div>
            ))}
          </AnimatePresence>

          {/* Summary */}
          <div className="mt-8 bg-white rounded-xl p-6 shadow-sm border border-gray-100">
            {/* Coupon panel */}
            <div className="mb-5 pb-5 border-b border-gray-100">
              {!appliedCoupon ? (
                <>
                  <label className="text-xs font-medium text-gray-600 flex items-center gap-1.5 mb-2">
                    <Ticket size={12} /> Coupon code
                  </label>
                  <div className="flex gap-2">
                    <input
                      type="text"
                      value={couponInput}
                      onChange={(e) => { setCouponInput(e.target.value); setCouponError(""); }}
                      onKeyDown={(e) => { if (e.key === "Enter") handleApplyCoupon(); }}
                      placeholder="Enter code"
                      className="flex-1 px-3 py-2 rounded-lg border border-gray-300 bg-white text-sm uppercase tracking-wide focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
                    />
                    <button
                      onClick={handleApplyCoupon}
                      disabled={validatingCoupon || !couponInput.trim()}
                      className="px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
                    >
                      {validatingCoupon ? <Loader2 className="animate-spin" size={14} /> : "Apply"}
                    </button>
                  </div>
                  {couponError && (
                    <p className="text-xs text-red-700 mt-2">{couponError}</p>
                  )}
                </>
              ) : (
                <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-emerald-50 border border-emerald-200">
                  <CheckCircle2 size={14} className="text-emerald-600 shrink-0" />
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-semibold text-emerald-800">
                      <span className="font-mono">{appliedCoupon.code}</span> applied
                    </p>
                    <p className="text-xs text-emerald-700">
                      {appliedCoupon.discountType === "PERCENT"
                        ? `${appliedCoupon.discountValue}% off`
                        : `₹${appliedCoupon.discountValue} off`}
                      {" — saving ₹"}
                      {Number(appliedCoupon.discountAmount).toLocaleString("en-IN")}
                    </p>
                  </div>
                  <button
                    onClick={() => { setAppliedCoupon(null); setCouponInput(""); setCouponError(""); }}
                    className="opacity-60 hover:opacity-100 text-emerald-700 cursor-pointer"
                    aria-label="Remove coupon"
                  >
                    <X size={14} />
                  </button>
                </div>
              )}
            </div>

            <div className="space-y-1.5 mb-4 text-sm">
              <div className="flex items-center justify-between text-gray-600">
                <span>Subtotal ({items.length} {items.length === 1 ? "item" : "items"})</span>
                <span className="tabular-nums">₹{subtotal.toLocaleString("en-IN")}</span>
              </div>
              {appliedCoupon && discount > 0 && (
                <div className="flex items-center justify-between text-emerald-700">
                  <span>Discount ({appliedCoupon.code})</span>
                  <span className="tabular-nums">−₹{Number(discount).toLocaleString("en-IN")}</span>
                </div>
              )}
              <div className="flex items-center justify-between pt-2 border-t border-gray-100">
                <span className="text-gray-700 font-medium">Total</span>
                <span className="text-2xl font-bold text-sage-navy tabular-nums">
                  ₹{total.toLocaleString("en-IN")}
                </span>
              </div>
            </div>
            <div className="flex gap-3">
              <button
                onClick={handleClear}
                className="px-4 py-2.5 text-sm font-medium text-gray-600 border border-gray-200 rounded-full hover:bg-gray-50 transition-colors cursor-pointer"
              >
                Clear Cart
              </button>
              <button
                onClick={handleCheckout}
                disabled={checkingOut}
                className="flex-1 flex items-center justify-center gap-2 bg-sage-navy text-white px-6 py-2.5 rounded-full text-sm font-semibold hover:bg-sage-navy-deep transition-colors disabled:opacity-50 cursor-pointer"
              >
                {checkingOut ? (
                  <Loader2 className="animate-spin" size={16} />
                ) : (
                  <>Checkout <ArrowRight size={16} /></>
                )}
              </button>
            </div>
          </div>
        </motion.div>
      )}

      <ProfileGateModal
        open={showProfileGate}
        onClose={() => setShowProfileGate(false)}
      />
    </section>
  );
}
