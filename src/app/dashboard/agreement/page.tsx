"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";

import WebAgreementWizard from "@/components/web-agreement/participant/WebAgreementWizard";
import { useAuth } from "@/lib/auth-context";
import { homeForRole } from "@/lib/roles";

/**
 * /dashboard/agreement — the participant fills and signs their agreement
 * here, under their normal website sign-in (the website copy of the
 * console's /consultant/{appId}/fill; no email code, no link expiry).
 * Full-screen like the other step pages: /dashboard has no layout, so the
 * wizard keeps its own header, stepper and footer.
 *
 * The route guard only checks that a sign-in token exists; this page also
 * waits for the signed-in user and sends anyone who isn't a participant to
 * /dashboard (which forwards staff to their own home). The server checks
 * that the agreement is the caller's own.
 */
export default function DashboardAgreementPage() {
  const router = useRouter();
  const { user, isLoading } = useAuth();
  const isParticipant = !!user && homeForRole(user.role) === "/dashboard" && !!user.participantId;

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.replace("/login?redirect=/dashboard/agreement");
      return;
    }
    if (!isParticipant) router.replace("/dashboard");
  }, [isLoading, user, isParticipant, router]);

  if (isLoading || !isParticipant) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-stone-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return <WebAgreementWizard />;
}
