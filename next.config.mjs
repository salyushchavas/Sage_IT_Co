/** @type {import('next').NextConfig} */
const nextConfig = {
  images: {
    unoptimized: false,
  },
  // Ensure Three.js works with SSR disabled via dynamic imports
  transpilePackages: ["three"],
};

export default nextConfig;
