"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import { CheckCircle2, Loader2, Mail } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import { requestConsultantCopy } from "@/lib/api";

export default function ConsultantDonePage() {
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";

  const [resending, setResending] = useState(false);
  const [resent, setResent] = useState(false);
  const [error, setError] = useState("");

  const handleResend = async () => {
    setResending(true);
    setError("");
    try {
      await requestConsultantCopy(appId);
      setResent(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't resend copy.");
    } finally {
      setResending(false);
    }
  };

  return (
    <SplitAuthLayout
      heroTitle={"Signed. We've\nemailed your copy."}
      heroSubtitle="Your signed agreement is on its way to your inbox. The operator has a copy too. You can safely close this tab."
      heroFooter="Done · Thank you"
    >
      <meta name="robots" content="noindex,nofollow" />
      <div className="space-y-5 text-center">
        <div className="mx-auto inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-emerald-100 text-emerald-700">
          <CheckCircle2 size={26} />
        </div>
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Agreement signed</h1>
          <p className="text-sm text-gray-600 mt-2 max-w-md mx-auto">
            We&apos;ve emailed a copy of your signed agreement to the address
            on file. The operator has also received the signed PDF.
          </p>
        </div>

        <div className="border-t border-gray-200 pt-4 max-w-md mx-auto">
          <p className="text-xs text-gray-500 mb-2">Didn&apos;t get the email?</p>
          <button
            type="button"
            onClick={handleResend}
            disabled={resending || resent}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 disabled:opacity-60 cursor-pointer"
          >
            {resending ? (
              <Loader2 size={12} className="animate-spin" />
            ) : (
              <Mail size={12} />
            )}
            {resent ? "Copy resent ✓" : "Email me another copy"}
          </button>
          {error && <p className="text-xs text-red-600 mt-2">{error}</p>}
        </div>

        <p className="text-[11px] text-gray-400">You can now close this tab.</p>
      </div>
    </SplitAuthLayout>
  );
}
