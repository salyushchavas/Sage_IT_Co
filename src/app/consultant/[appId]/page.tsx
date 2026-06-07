"use client";

import { useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";

/**
 * Bare /consultant/{appId} route -- typically reached only when the
 * email invitation link drops without the /verify suffix. Forwards to
 * the verification gate, which is the actual entry point (the consultant
 * must pass the email-OTP gate before reaching the form).
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
    router.replace(`/consultant/${encodeURIComponent(appId)}/verify`);
  }, [appId, router]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <Loader2 size={28} className="animate-spin text-sage-navy" />
    </div>
  );
}
