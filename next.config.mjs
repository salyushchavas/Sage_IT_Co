/** @type {import('next').NextConfig} */
const nextConfig = {
  images: {
    unoptimized: false,
  },
  // Ensure Three.js works with SSR disabled via dynamic imports
  transpilePackages: ["three"],
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
