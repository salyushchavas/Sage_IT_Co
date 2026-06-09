"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Loader2, Mail, ShieldCheck } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import { getConsultantToken } from "@/lib/api";

/**
 * Build V — bare /consultant landing. The portal no longer accepts a
 * consultant-typed email; every invitation link carries the appId and
 * lands on /consultant/{appId}/login. This page is a safety net for
 * anyone who reaches /consultant without context:
 *   - already signed in → bounce to the dashboard.
 *   - otherwise → tell them to open their invitation email (no email
 *     input, no way to type an address).
 */
export default function ConsultantPortalLandingPage() {
  const router = useRouter();

  useEffect(() => {
    if (getConsultantToken()) {
      router.replace("/consultant/dashboard");
    }
  }, [router]);

  return (
    <SplitAuthLayout
      heroTitle={"Sage IT\nConsultant Portal."}
      heroSubtitle="Open the invitation email Sage IT sent you and click the agreement link to verify your identity."
      heroFooter="Secure access · Sage IT Co"
    >
      <meta name="robots" content="noindex,nofollow" />
      <div className="max-w-md mx-auto py-12 px-6 space-y-5">
        <div className="inline-flex items-center gap-2 text-sage-copper">
          <ShieldCheck size={18} />
          <span className="text-xs font-bold uppercase tracking-wider">
            Portal sign-in
          </span>
        </div>
        <h1 className="text-2xl font-serif text-sage-navy">
          Open your invitation email
        </h1>
        <p className="text-sm text-gray-600 leading-relaxed">
          For your security, this portal does not accept a typed
          email. Open the invitation email Sage IT sent you and click
          the agreement link. The link will send a 6-digit
          verification code to the email Sage IT has on file.
        </p>
        <div className="rounded-md border border-stone-200 bg-stone-50 px-4 py-3 inline-flex items-start gap-2 text-xs text-gray-700">
          <Mail size={14} className="text-sage-navy mt-0.5 shrink-0" />
          <span>
            Can&apos;t find the email? Check your spam folder, or
            contact Sage IT to resend the invitation.
          </span>
        </div>
        <div className="text-[11px] text-gray-500 inline-flex items-center gap-1.5">
          <Loader2 size={11} className="animate-spin" />
          If you&apos;re already signed in, taking you to your
          dashboard…
        </div>
      </div>
    </SplitAuthLayout>
  );
}
