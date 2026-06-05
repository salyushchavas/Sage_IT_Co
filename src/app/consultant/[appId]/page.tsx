"use client";

import { useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";

/**
 * Bare /consultant/{appId} route -- typically reached only when the
 * email invitation link drops without the /review suffix. Forwards
 * to the review screen, which is the actual entry point.
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
    router.replace(`/consultant/${encodeURIComponent(appId)}/review`);
  }, [appId, router]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <Loader2 size={28} className="animate-spin text-sage-navy" />
    </div>
  );
}
