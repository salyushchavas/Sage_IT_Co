"use client";

import { useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";

/**
 * Legacy /sign route. The guided wizard at /fill now owns signing
 * (the signature is drawn once on the main-agreement step and reused
 * downstream); this page just forwards anyone who lands here from a
 * saved bookmark or an old email.
 */
export default function ConsultantSignRedirectPage() {
  const router = useRouter();
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";

  useEffect(() => {
    if (!appId) {
      router.replace("/consultant");
      return;
    }
    router.replace(`/consultant/${encodeURIComponent(appId)}/fill`);
  }, [appId, router]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-stone-50">
      <Loader2 size={28} className="animate-spin text-sage-navy" />
    </div>
  );
}
