/**
 * Single source of truth for the canonical site origin.
 *
 * Why:
 *   - Next's metadataBase, robots.ts, and sitemap.ts all need the same
 *     absolute origin. Keeping it here avoids drift when the domain
 *     changes (e.g., switching from a Vercel preview to sageitco.com).
 *
 * Resolution order:
 *   1. NEXT_PUBLIC_SITE_URL — set this in Vercel for prod (e.g.
 *      "https://www.sageitco.com").
 *   2. VERCEL_PROJECT_PRODUCTION_URL — Vercel injects this for the
 *      production deployment automatically (no protocol).
 *   3. Localhost fallback — only used during `next dev`.
 */
function resolveSiteUrl(): string {
  const explicit = process.env.NEXT_PUBLIC_SITE_URL;
  if (explicit) return explicit.replace(/\/$/, "");

  const vercelProd = process.env.VERCEL_PROJECT_PRODUCTION_URL;
  if (vercelProd) return `https://${vercelProd}`;

  return "https://www.sageitco.com";
}

export const SITE_URL = resolveSiteUrl();

import type { Metadata } from "next";

interface PageMetaInput {
  title: string;
  description: string;
  /** Path relative to site root, e.g. "/services". Used for canonical + OG URL. */
  path: string;
  /** Optional override for the social-card image. Defaults to the brand logo. */
  image?: string;
}

/**
 * Build a Metadata object for an individual page.
 *
 * The root layout already sets defaults (siteName, twitter card type,
 * locale, robots, etc.). This helper only fills in fields that should
 * differ per page — title, description, canonical, and per-page social
 * preview text. Anything not specified inherits from the root.
 */
export function pageMeta({ title, description, path, image }: PageMetaInput): Metadata {
  const url = path.startsWith("/") ? path : `/${path}`;
  return {
    title,
    description,
    alternates: { canonical: url },
    openGraph: {
      title,
      description,
      url,
      type: "website",
      images: image ? [image] : undefined,
    },
    twitter: {
      title,
      description,
      images: image ? [image] : undefined,
    },
  };
}
