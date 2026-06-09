"use client";

import { useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";

import { getConsultantToken } from "@/lib/api";

/**
 * Build V — bare /consultant/{appId} route. Every invitation email
 * now links to /consultant/{appId}/login, but this stub still routes
 * older saved links sensibly:
 *   - verified consultant → straight to the fill flow.
 *   - unverified → the per-app login (the email is resolved server-
 *     side from the appId; the consultant never types it).
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
      router.replace(`/consultant/${encodeURIComponent(appId)}/login`);
    }
  }, [appId, router]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <Loader2 size={28} className="animate-spin text-sage-navy" />
    </div>
  );
}
