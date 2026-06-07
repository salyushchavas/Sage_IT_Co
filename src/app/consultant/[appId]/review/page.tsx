"use client";

import { useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";

/**
 * Legacy /review route. The guided wizard at /fill includes a
 * dedicated review-and-submit step at the end; the standalone page
 * is redundant and just forwards here.
 */
export default function ConsultantReviewRedirectPage() {
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
