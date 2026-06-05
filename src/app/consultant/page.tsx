"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Image from "next/image";
import Link from "next/link";
import {
  AlertCircle,
  ArrowRight,
  CheckCircle2,
  ClipboardCheck,
  FileText,
  KeyRound,
  Loader2,
  Mail,
  PenLine,
} from "lucide-react";

const STEPS = [
  {
    label: "Enter Application ID",
    description: "Paste the ID from your invitation email.",
    Icon: KeyRound,
  },
  {
    label: "Review your details",
    description: "Make sure the engagement terms look correct.",
    Icon: ClipboardCheck,
  },
  {
    label: "Sign agreement",
    description: "Add your full legal name and digital signature.",
    Icon: PenLine,
  },
  {
    label: "Receive signed copy",
    description: "We email a signed PDF the moment you're done.",
    Icon: Mail,
  },
] as const;

function ConsultantEntryInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const appFromUrl = searchParams?.get("app") ?? "";

  const [appId, setAppId] = useState(appFromUrl);
  const [redirecting, setRedirecting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    // Email link with ?app=<uuid> drops the consultant straight on
    // the review screen -- no need to make them click again.
    if (appFromUrl && isValidUuidLike(appFromUrl)) {
      setRedirecting(true);
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
    setRedirecting(true);
    router.push(`/consultant/${encodeURIComponent(trimmed)}/review`);
  };

  if (redirecting) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <meta name="robots" content="noindex,nofollow" />

      {/* Hero -- Sage navy band with copper accent */}
      <header className="relative overflow-hidden bg-sage-navy text-white">
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -top-24 -right-24 w-96 h-96 rounded-full opacity-20 blur-3xl"
          style={{ background: "#C87D5C" }}
        />
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -bottom-32 -left-16 w-80 h-80 rounded-full opacity-10 blur-3xl"
          style={{ background: "#C87D5C" }}
        />

        <div className="relative max-w-4xl mx-auto px-5 sm:px-8 pt-10 pb-14 sm:pt-14 sm:pb-20">
          <Link
            href="/"
            aria-label="Sage IT Co home"
            className="inline-flex items-center gap-3 group w-fit"
          >
            <Image
              src="/sage_logo.png"
              alt="Sage IT Co"
              width={40}
              height={40}
              priority
              className="rounded-md object-contain transition-transform group-hover:scale-105"
            />
            <span className="text-lg sm:text-xl font-bold tracking-tight">
              Sage IT Co
            </span>
          </Link>

          <div className="mt-8 sm:mt-10 max-w-2xl">
            <p className="text-xs sm:text-sm font-semibold uppercase tracking-[0.18em] text-sage-copper">
              Sage Consultant Portal
            </p>
            <h1 className="mt-2 text-3xl sm:text-5xl font-bold leading-tight">
              Welcome — review and sign your engagement.
            </h1>
            <p className="mt-3 sm:mt-4 text-sm sm:text-base text-white/80 leading-relaxed max-w-xl">
              A secure, signed copy of your agreement lands in your inbox the
              moment you finish. The whole flow takes about two minutes.
            </p>
          </div>
        </div>
      </header>

      {/* Main content */}
      <main className="flex-1 px-5 sm:px-8 -mt-10 sm:-mt-14 pb-12">
        <div className="max-w-4xl mx-auto grid grid-cols-1 lg:grid-cols-5 gap-6">
          {/* Form card */}
          <section className="lg:col-span-3 bg-white rounded-2xl shadow-xl border border-gray-100 p-6 sm:p-8">
            <h2 className="font-serif text-xl sm:text-2xl font-bold text-gray-900">
              Enter your Application ID
            </h2>
            <p className="text-sm text-gray-500 mt-1">
              Find this at the top of the invitation email from your Sage
              contact. Treat it like a password — it grants access to your
              agreement.
            </p>

            <form onSubmit={handleSubmit} className="mt-5 space-y-4">
              <div>
                <label
                  htmlFor="appId"
                  className="block text-[11px] font-semibold uppercase tracking-wider text-gray-600 mb-1.5"
                >
                  Application ID
                </label>
                <input
                  id="appId"
                  type="text"
                  value={appId}
                  onChange={(e) => setAppId(e.target.value)}
                  placeholder="00000000-0000-0000-0000-000000000000"
                  autoCapitalize="off"
                  autoCorrect="off"
                  spellCheck={false}
                  autoComplete="off"
                  className="w-full px-4 py-3 text-base font-mono rounded-lg border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-2 focus:ring-sage-navy/20 placeholder:text-gray-300"
                />
                <p className="mt-1.5 text-[11px] text-gray-400">
                  Format: a UUID (eight blocks of letters and numbers).
                </p>
              </div>

              {error && (
                <p className="inline-flex items-center gap-1.5 text-sm text-red-600">
                  <AlertCircle size={14} /> {error}
                </p>
              )}

              <button
                type="submit"
                className="w-full inline-flex items-center justify-center gap-1.5 px-4 py-3 rounded-lg text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep shadow-md hover:shadow-lg transition cursor-pointer"
              >
                Continue <ArrowRight size={14} />
              </button>
            </form>

            <div className="mt-6 pt-5 border-t border-gray-100 flex items-start gap-2 text-xs text-gray-500">
              <FileText size={14} className="text-gray-400 mt-0.5 shrink-0" />
              <p>
                Your invitation includes the application ID and a direct
                link. If you opened that link, you&apos;ll skip this screen
                automatically.
              </p>
            </div>
          </section>

          {/* Process steps */}
          <aside className="lg:col-span-2 bg-white rounded-2xl shadow-md border border-gray-100 p-6 sm:p-7">
            <p className="text-[11px] uppercase tracking-[0.18em] font-semibold text-sage-copper">
              How it works
            </p>
            <h3 className="mt-1 text-lg font-bold text-gray-900">
              Four quick steps
            </h3>

            <ol className="mt-4 space-y-4">
              {STEPS.map(({ label, description, Icon }, idx) => (
                <li key={label} className="flex items-start gap-3">
                  <div className="relative shrink-0">
                    <div className="w-9 h-9 rounded-full bg-sage-navy/10 text-sage-navy flex items-center justify-center">
                      <Icon size={16} />
                    </div>
                    <span className="absolute -top-1 -right-1 inline-flex items-center justify-center w-4 h-4 rounded-full bg-sage-navy text-white text-[10px] font-bold">
                      {idx + 1}
                    </span>
                  </div>
                  <div className="pt-0.5">
                    <p className="text-sm font-semibold text-gray-900 leading-tight">
                      {label}
                    </p>
                    <p className="text-xs text-gray-500 mt-0.5 leading-relaxed">
                      {description}
                    </p>
                  </div>
                </li>
              ))}
            </ol>

            <div className="mt-5 rounded-lg bg-sage-navy/5 border border-sage-navy/10 p-3 flex items-start gap-2">
              <CheckCircle2
                size={14}
                className="text-sage-navy mt-0.5 shrink-0"
              />
              <p className="text-[11px] text-sage-navy/80 leading-relaxed">
                Everything is logged for audit. Once signed, you can request
                another copy of the PDF at any time.
              </p>
            </div>
          </aside>
        </div>
      </main>

      {/* Footer */}
      <footer className="border-t border-gray-100 bg-white">
        <div className="max-w-4xl mx-auto px-5 sm:px-8 py-5 flex flex-col sm:flex-row items-center justify-between gap-2 text-xs text-gray-500">
          <p>
            Trouble accessing?{" "}
            <a
              href="mailto:noreply@sageitco.com"
              className="font-semibold text-sage-navy hover:underline"
            >
              noreply@sageitco.com
            </a>
          </p>
          <p className="text-gray-400">
            Hidden internal flow · &copy; {new Date().getFullYear()} Sage IT Co
          </p>
        </div>
      </footer>
    </div>
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
