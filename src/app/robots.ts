import type { MetadataRoute } from "next";
import { SITE_URL } from "@/lib/seo";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: [
      {
        userAgent: "*",
        allow: "/",
        // Block private app surfaces from indexing — they're either
        // gated, transient, or contain user-specific data.
        disallow: [
          "/api/",
          "/admin",
          "/admin/",
          "/dashboard",
          "/dashboard/",
          "/login",
          "/signup",
          // Consultant Agreement (hidden internal feature):
          "/consultant",
          "/consultant/",
          "/agreements",
          "/agreements/",
          // Legacy route (now 308-redirects to /agreements):
          "/agreement-erm",
          "/agreement-erm/",
        ],
      },
    ],
    sitemap: `${SITE_URL}/sitemap.xml`,
    host: SITE_URL,
  };
}
