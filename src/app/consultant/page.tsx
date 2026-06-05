"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { AlertCircle, ArrowRight, Loader2 } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";

function ConsultantEntryInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const appFromUrl = searchParams?.get("app") ?? "";

  const [appId, setAppId] = useState(appFromUrl);
  const [error, setError] = useState("");

  useEffect(() => {
    // If someone arrives with ?app=... pre-filled, send them straight
    // through. The /review page handles any "not found" responses.
    if (appFromUrl && isValidUuidLike(appFromUrl)) {
      router.replace(`/consultant/${encodeURIComponent(appFromUrl)}/review`);
    }
  }, [appFromUrl, router]);

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setError("");
    const trimmed = appId.trim();
    if (!isValidUuidLike(trimmed)) {
      setError("That doesn't look like a valid application ID.");
      return;
    }
    router.push(`/consultant/${encodeURIComponent(trimmed)}/review`);
  };

  return (
    <SplitAuthLayout
      heroTitle={"Sign your\nconsulting agreement."}
      heroSubtitle="Open the secure link from your invitation email, or paste the application ID below to review and sign your engagement details."
      heroFooter="Hidden internal flow · Sage IT Co"
    >
      <meta name="robots" content="noindex,nofollow" />
      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <h1 className="font-serif text-2xl font-bold text-gray-900">
            Consultant access
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            Enter the application ID from your invitation.
          </p>
        </div>

        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-0.5">
            Application ID
          </label>
          <input
            type="text"
            value={appId}
            onChange={(e) => setAppId(e.target.value)}
            placeholder="paste the ID from your invite"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            className="w-full px-3 py-2.5 text-sm font-mono rounded-lg border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
          />
        </div>

        {error && (
          <p className="inline-flex items-center gap-1.5 text-sm text-red-600">
            <AlertCircle size={14} /> {error}
          </p>
        )}

        <button
          type="submit"
          className="w-full inline-flex items-center justify-center gap-1.5 px-3 py-3 rounded-lg text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
        >
          Continue <ArrowRight size={14} />
        </button>

        <p className="text-[11px] text-gray-500 text-center">
          The application ID alone gives access. Treat it like a password —
          don&apos;t forward your invite email to anyone else.
        </p>
      </form>
    </SplitAuthLayout>
  );
}

function isValidUuidLike(value: string) {
  // Loose UUID v4 check -- backend is the source of truth, this is
  // just a frontend sanity gate to catch typos.
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

export default function ConsultantEntryPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen flex items-center justify-center bg-gray-50">
          <Loader2 size={28} className="animate-spin text-sage-navy" />
        </div>
      }
    >
      <ConsultantEntryInner />
    </Suspense>
  );
}
