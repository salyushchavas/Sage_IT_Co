"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { AlertCircle, Loader2 } from "lucide-react";

const STEPS = [
  { num: 1, title: "Enter ID",       desc: "Click the link in your email or paste your application ID below." },
  { num: 2, title: "Review Details", desc: "Check the information your ERM has prepared." },
  { num: 3, title: "Sign",           desc: "Draw your signature or upload an image." },
  { num: 4, title: "Receive Copy",   desc: "Get the signed agreement emailed to you." },
] as const;

function ConsultantEntryInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const appFromUrl = searchParams?.get("app") ?? "";

  const [appId, setAppId] = useState(appFromUrl);
  const [redirecting, setRedirecting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    // Email link with ?app=<uuid> sends the consultant straight to the
    // verification gate -- they confirm their email before the form.
    if (appFromUrl && isValidUuidLike(appFromUrl)) {
      setRedirecting(true);
      router.replace(`/consultant/${encodeURIComponent(appFromUrl)}/verify`);
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
    setRedirecting(true);
    router.push(`/consultant/${encodeURIComponent(trimmed)}/verify`);
  };

  if (redirecting) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <main className="min-h-screen bg-stone-50">
      <meta name="robots" content="noindex,nofollow" />

      {/* Hero */}
      <section className="bg-sage-navy text-white">
        <div className="max-w-3xl mx-auto px-4 py-16 text-center">
          <h1 className="font-serif text-4xl md:text-5xl mb-4">
            Welcome to Your Agreement Portal
          </h1>
          <p className="text-lg text-white/80 max-w-xl mx-auto">
            Enter your application ID to review and sign your agreement.
          </p>
        </div>
      </section>

      {/* Process steps */}
      <section className="max-w-5xl mx-auto px-4 py-12">
        <div className="grid grid-cols-1 md:grid-cols-4 gap-8">
          {STEPS.map((s) => (
            <div key={s.num} className="text-center">
              <div className="w-12 h-12 rounded-full bg-sage-copper text-white flex items-center justify-center text-xl font-semibold mx-auto mb-3">
                {s.num}
              </div>
              <h3 className="font-semibold text-sage-navy mb-1">{s.title}</h3>
              <p className="text-sm text-gray-600">{s.desc}</p>
            </div>
          ))}
        </div>
      </section>

      {/* Input card */}
      <section className="max-w-md mx-auto px-4 pb-20">
        <div className="bg-white rounded-lg shadow-md border border-gray-100 p-8">
          <form onSubmit={handleSubmit}>
            <label
              htmlFor="appId"
              className="block text-sm font-semibold text-sage-navy mb-2"
            >
              Application ID
            </label>
            <input
              id="appId"
              type="text"
              value={appId}
              onChange={(e) => setAppId(e.target.value)}
              placeholder="a1b2c3d4-e5f6-7890-abcd-ef1234567890"
              autoComplete="off"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              className="w-full px-4 py-3 border border-gray-300 rounded-md focus:ring-2 focus:ring-sage-navy focus:border-sage-navy outline-none font-mono text-sm"
            />
            {error && (
              <p className="mt-2 inline-flex items-center gap-1.5 text-sm text-red-600">
                <AlertCircle size={14} /> {error}
              </p>
            )}
            <button
              type="submit"
              disabled={!appId.trim()}
              className="w-full mt-4 bg-sage-navy hover:bg-sage-navy-deep text-white font-semibold py-3 rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Continue →
            </button>
          </form>
          <p className="text-xs text-gray-500 mt-4 text-center">
            Don&apos;t have an ID? Check your email or contact your ERM.
          </p>
        </div>
      </section>
    </main>
  );
}

function isValidUuidLike(value: string) {
  // Loose UUID check -- backend is the source of truth, this is
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
