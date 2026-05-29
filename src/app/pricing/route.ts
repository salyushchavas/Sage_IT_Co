/**
 * /pricing was removed in commit aef1b0b. Google still has the old URL
 * indexed from when it was live and listed in our sitemap. A default
 * Next 404 would eventually clear it, but 410 Gone is the explicit
 * "permanently removed, drop it from the index" signal — Google
 * typically de-indexes 410s in roughly half the time of 404s.
 *
 * Once Google has confirmed the URL is gone (give it ~4 weeks, watch
 * Search Console → Pages → Not found), this whole handler can be
 * deleted and Next will fall back to its default 404 for the path.
 */

function gone(): Response {
  return new Response("Gone — this page has been permanently removed.", {
    status: 410,
    headers: {
      "Content-Type": "text/plain; charset=utf-8",
      // Crawlers occasionally cache 4xx responses; keep TTL short so a
      // future re-launch of /pricing isn't shadowed by stale caches.
      "Cache-Control": "public, max-age=0, must-revalidate",
    },
  });
}

export function GET(): Response {
  return gone();
}

export function HEAD(): Response {
  return new Response(null, {
    status: 410,
    headers: { "Cache-Control": "public, max-age=0, must-revalidate" },
  });
}
