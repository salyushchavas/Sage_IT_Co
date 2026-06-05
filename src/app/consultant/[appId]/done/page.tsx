"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { CheckCircle2, Loader2, Mail } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import { requestConsultantCopy } from "@/lib/api";
import {
  clearConsultantSession,
  getConsultantToken,
} from "@/lib/consultant-session";

export default function ConsultantDonePage() {
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";

  const [resending, setResending] = useState(false);
  const [resent, setResent] = useState(false);
  const [error, setError] = useState("");
  const [token, setToken] = useState<string | null>(null);

  useEffect(() => {
    // Grab the token before we clear it -- the consultant may want
    // to request a re-send of the PDF copy from this screen for
    // the next minute or two.
    if (!appId) return;
    const t = getConsultantToken(appId);
    setToken(t);
    // Schedule cleanup after a short grace window so the
    // "request another copy" button stays functional.
    const timer = setTimeout(() => {
      clearConsultantSession(appId);
      setToken(null);
    }, 5 * 60 * 1000);
    return () => clearTimeout(timer);
  }, [appId]);

  const handleResend = async () => {
    if (!token) return;
    setResending(true);
    setError("");
    try {
      await requestConsultantCopy(appId, token);
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
      heroSubtitle="Your signed agreement is on its way to your inbox. Your ERM has a copy too. You can safely close this tab."
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
            on file. Your ERM has also received the signed PDF.
          </p>
        </div>

        <div className="border-t border-gray-200 pt-4 max-w-md mx-auto">
          {token ? (
            <>
              <p className="text-xs text-gray-500 mb-2">
                Didn&apos;t get the email?
              </p>
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
            </>
          ) : (
            <p className="text-[11px] text-gray-400">
              Session ended. To request another copy reply to the email we
              just sent, or contact your ERM.
            </p>
          )}
        </div>

        <p className="text-[11px] text-gray-400">
          You can now close this tab.
        </p>
      </div>
    </SplitAuthLayout>
  );
}
