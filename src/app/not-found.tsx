import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "Page not found",
};

/** Wrong URLs land here (Next still answers with status 404). */
export default function NotFound() {
  return (
    <section className="min-h-[70vh] flex items-center pt-28 sm:pt-32 pb-16 sm:pb-20 px-4 sm:px-6 bg-grid">
      <div className="max-w-2xl mx-auto text-center">
        <p className="text-xs uppercase tracking-widest font-bold text-sage-copper-deep mb-3">
          Error 404
        </p>
        <h1 className="text-3xl sm:text-4xl md:text-5xl font-bold text-zinc-900 mb-4 sm:mb-6">
          Page <span className="text-gradient">not found</span>
        </h1>
        <p className="text-zinc-600 text-base sm:text-lg leading-relaxed mb-8">
          The page you&apos;re looking for doesn&apos;t exist or has moved.
        </p>
        <div className="flex flex-wrap items-center justify-center gap-3">
          <Link
            href="/"
            className="inline-block py-3 px-6 rounded-lg bg-sage-navy hover:bg-sage-navy-deep text-white font-semibold text-sm transition"
          >
            Go to the home page
          </Link>
          <Link
            href="/resources"
            className="inline-block py-3 px-6 rounded-lg border border-zinc-200 bg-white hover:bg-zinc-50 text-sage-navy font-semibold text-sm transition"
          >
            Browse resources
          </Link>
        </div>
      </div>
    </section>
  );
}
