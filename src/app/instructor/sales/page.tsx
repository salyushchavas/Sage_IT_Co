"use client";

import { useEffect } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChevronLeft, Loader2, ShieldCheck } from "lucide-react";

import { useAuth } from "@/lib/auth-context";
import { InstructorSalesInbox } from "@/components/sales/InstructorSalesInbox";

/**
 * /instructor/sales -- instructor B2B sales inbox.
 *
 * Lists every sales inquiry submitted on a course the signed-in
 * instructor owns. Selecting a row opens the conversation thread
 * inline; a "Send Quote" button on each open row launches the
 * SendQuoteModal so the instructor can respond with itemised
 * custom pricing in INR.
 *
 * Locked to INSTRUCTOR / ADMIN / SYSTEM_ADMIN.
 */
export default function InstructorSalesPage() {
  const router = useRouter();
  const { user, isLoading: authLoading } = useAuth();
  const role = user?.role?.toUpperCase();
  const isAllowed = role === "INSTRUCTOR" || role === "ADMIN" || role === "SYSTEM_ADMIN";

  useEffect(() => {
    if (authLoading) return;
    if (!user) {
      router.push("/login?redirect=/instructor/sales");
      return;
    }
    if (!isAllowed) {
      router.push("/dashboard");
    }
  }, [user, authLoading, isAllowed, router]);

  if (authLoading || !user) {
    return (
      <section className="mx-auto max-w-4xl px-6 pt-32 pb-20 flex items-center justify-center min-h-[60vh]">
        <Loader2 size={32} className="animate-spin text-sage-navy" />
      </section>
    );
  }

  if (!isAllowed) {
    return (
      <section className="mx-auto max-w-md px-6 pt-32 pb-20 text-center">
        <ShieldCheck size={48} className="text-gray-300 mx-auto mb-4" />
        <h1 className="text-2xl font-bold text-gray-900 mb-2">Instructor only</h1>
        <p className="text-gray-500 mb-6">
          This panel is for approved instructors.
        </p>
        <Link href="/dashboard" className="text-sage-navy underline text-sm">
          Go to dashboard
        </Link>
      </section>
    );
  }

  return (
    <section className="mx-auto max-w-4xl px-6 pt-28 pb-20 min-h-screen">
      <Link
        href="/instructor"
        className="inline-flex items-center gap-1 text-sm text-gray-500 hover:text-sage-navy mb-4"
      >
        <ChevronLeft size={16} /> Back to my courses
      </Link>

      <div className="mb-6">
        <h1 className="text-3xl font-bold text-sage-navy">Sales Inbox</h1>
        <p className="text-sm text-gray-500 mt-1">
          Custom-pricing requests on the courses you teach. Reply to keep the
          conversation going or send a quote with itemised pricing.
        </p>
      </div>

      <InstructorSalesInbox currentUserId={user.id} />
    </section>
  );
}
