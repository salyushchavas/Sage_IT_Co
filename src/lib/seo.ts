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
