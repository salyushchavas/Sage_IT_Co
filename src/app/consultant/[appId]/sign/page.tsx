"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { AlertCircle, Loader2 } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import SignaturePad from "@/components/common/SignaturePad";
import {
  getConsultantApplicationView,
  signConsultantApplication,
  type ConsultantApplication,
} from "@/lib/api";

export default function ConsultantSignPage() {
  const router = useRouter();
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";

  const [app, setApp] = useState<ConsultantApplication | null>(null);
  const [loading, setLoading] = useState(true);
  const [legalName, setLegalName] = useState("");
  const [confirmAccept, setConfirmAccept] = useState(false);
  const [signatureData, setSignatureData] = useState<string | null>(null);
  const [signing, setSigning] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!appId) return;
    getConsultantApplicationView(appId)
      .then((data) => {
        setApp(data);
        if (data.consultantName) setLegalName(data.consultantName);
        // Phase 5 state machine: consultant arrives at /sign from
        // /fill (state still SUBMITTED or REVISION_REQUESTED), signs
        // here -> POST /submit -> state becomes VERIFIED. If they
        // revisit /sign post-submit (state=VERIFIED) we keep them on
        // this page but the render branch switches to a "submitted,
        // waiting on ERM" view. SIGNED + COMPLETED land on /done.
        if (data.status === "SIGNED" || data.status === "COMPLETED") {
          router.replace(`/consultant/${encodeURIComponent(appId)}/done`);
          return;
        }
        if (data.status === "CANCELLED" || data.status === "EXPIRED") {
          router.replace("/consultant");
        }
      })
      .catch(() => {
        router.replace("/consultant");
      })
      .finally(() => setLoading(false));
  }, [appId, router]);

  const nameWordCount = legalName.trim().split(/\s+/).filter(Boolean).length;
  const canSign =
    nameWordCount >= 2 && !!signatureData && confirmAccept && !signing;

  const handleSign = async () => {
    if (!canSign || !signatureData) return;
    setSigning(true);
    setError("");
    try {
      await signConsultantApplication(appId, legalName.trim(), signatureData);
      router.push(`/consultant/${encodeURIComponent(appId)}/done`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't sign.");
    } finally {
      setSigning(false);
    }
  };

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  // Already submitted -- consultant pressed back or revisited the URL
  // after signing. Render a waiting-state instead of letting them
  // re-sign (which the backend would 409 anyway).
  if (app?.status === "VERIFIED") {
    return (
      <SplitAuthLayout
        heroTitle={"Submitted.\nWaiting on review."}
        heroSubtitle="Your signed submission is with the operator. We'll email you the countersigned PDF the moment they approve."
        heroFooter="Done from your side"
      >
        <meta name="robots" content="noindex,nofollow" />
        <div className="space-y-3 text-center">
          <p className="text-sm text-gray-600 max-w-md mx-auto">
            You signed and submitted this agreement. Nothing more to do --
            the operator will countersign and your copy will arrive in
            your inbox.
          </p>
          <p className="text-[11px] text-gray-400">
            You can safely close this tab.
          </p>
        </div>
      </SplitAuthLayout>
    );
  }

  return (
    <SplitAuthLayout
      heroTitle={"One last\nstep — sign."}
      heroSubtitle="Add your full legal name and a digital signature. We email a signed PDF to both you and the operator immediately."
      heroFooter="Step 2 of 2 · Sign"
    >
      <meta name="robots" content="noindex,nofollow" />
      <div className="space-y-4">
        <div>
          <h1 className="font-serif text-2xl font-bold text-gray-900">
            Sign your agreement
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            By signing you confirm everything on the previous screen is correct
            and you accept the agreement.
          </p>
        </div>

        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-0.5">
            Legal name <span className="text-red-500">*</span>
          </label>
          <input
            type="text"
            value={legalName}
            onChange={(e) => setLegalName(e.target.value)}
            placeholder="Your full legal name"
            className="w-full px-3 py-2.5 text-sm rounded-lg border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
          />
          {legalName.trim() && nameWordCount < 2 && (
            <p className="text-[11px] text-red-500 mt-1">
              Enter first and last name.
            </p>
          )}
        </div>

        <SignaturePad onChange={setSignatureData} fileInputId="consult-sig-upload" />

        <label className="flex items-start gap-2.5 text-sm text-gray-700 cursor-pointer">
          <input
            type="checkbox"
            checked={confirmAccept}
            onChange={(e) => setConfirmAccept(e.target.checked)}
            className="mt-0.5 w-4 h-4 rounded border-gray-300 text-sage-navy focus:ring-sage-copper"
          />
          <span>
            I confirm the details on file are correct and I accept this
            agreement. <span className="text-red-500">*</span>
          </span>
        </label>

        {error && (
          <p className="inline-flex items-center gap-1.5 text-sm text-red-600">
            <AlertCircle size={14} /> {error}
          </p>
        )}

        <button
          type="button"
          onClick={handleSign}
          disabled={!canSign}
          className={
            "w-full inline-flex items-center justify-center gap-2 px-4 py-3 rounded-lg text-sm font-bold transition " +
            (canSign
              ? "bg-sage-navy text-white hover:bg-sage-navy-deep shadow-md hover:shadow-lg cursor-pointer"
              : "bg-gray-200 text-gray-500 cursor-not-allowed")
          }
        >
          {signing && <Loader2 size={14} className="animate-spin" />}
          {signing ? "Signing…" : "Sign Agreement →"}
        </button>

        {app && (
          <Link
            href={`/consultant/${encodeURIComponent(appId)}/review`}
            className="block text-center text-xs font-semibold text-gray-500 hover:text-sage-navy"
          >
            ← Back to review
          </Link>
        )}
      </div>
    </SplitAuthLayout>
  );
}
