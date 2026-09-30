/** @type {import('next').NextConfig} */
const nextConfig = {
  images: {
    unoptimized: false,
  },
  // Ensure Three.js works with SSR disabled via dynamic imports
  transpilePackages: ["three"],
  // Security headers on every response. These are the safe, render-neutral
  // ones: they stop clickjacking, MIME-sniffing and referrer leakage and turn
  // on HSTS. A full script-src CSP (with per-request nonces) is a good
  // follow-up but is intentionally NOT added here because a wrong one would
  // break Next's inline runtime; the actual XSS vectors were fixed at source.
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Content-Security-Policy", value: "frame-ancestors 'none'" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
          { key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains; preload" },
          { key: "X-DNS-Prefetch-Control", value: "off" },
        ],
      },
    ];
  },
  // The agreement console moved from /agreement-erm to /agreements.
  // Permanent-redirect old bookmarks (including deep links like
  // /agreement-erm/<appId> and /agreement-erm/login) to the new route.
  async redirects() {
    return [
      {
        source: "/agreement-erm",
        destination: "/agreements",
        permanent: true,
      },
      {
        source: "/agreement-erm/:path*",
        destination: "/agreements/:path*",
        permanent: true,
      },
      // Pages removed when the menu became Home / About Us / Resources /
      // Testimonials / Blogs / Contact Us. Old links land on the nearest
      // page that still exists. Not permanent, so a page can come back.
      // (/services/<id> and /services/create are untouched.)
      { source: "/services", destination: "/resources", permanent: false },
      { source: "/solutions", destination: "/resources", permanent: false },
      { source: "/portfolio", destination: "/testimonials", permanent: false },
      { source: "/careers", destination: "/", permanent: false },
      // Until there is a course-only path (decision D4), everyone joins
      // through the program: the old course sign-up sends people to the
      // program enrollment. Not permanent, so D4 can bring it back.
      {
        source: "/signup",
        destination: "/enroll",
        permanent: false,
      },
    ];
  },
};

export default nextConfig;
