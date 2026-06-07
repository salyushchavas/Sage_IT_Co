"use client";

import { useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";

import { getConsultantToken } from "@/lib/api";

/**
 * Bare /consultant/{appId} route. In the portal phase nothing useful
 * lives at this exact path -- the email invites point at /consultant
 * (the portal login). If anything still resolves an appId-suffixed URL
 * (an old saved link), we either:
 *   - send a verified consultant straight to the fill flow for that app
 *     (the email-match guard at the API layer will 404 if it isn't
 *     theirs); or
 *   - bounce an unverified visitor to the portal login.
 */
export default function ConsultantAppRootPage() {
  const router = useRouter();
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";

  useEffect(() => {
    if (!appId) {
      router.replace("/consultant");
      return;
    }
    if (getConsultantToken()) {
      router.replace(`/consultant/${encodeURIComponent(appId)}/fill`);
    } else {
      router.replace("/consultant");
    }
  }, [appId, router]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <Loader2 size={28} className="animate-spin text-sage-navy" />
    </div>
  );
}
