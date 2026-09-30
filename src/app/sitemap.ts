import type { MetadataRoute } from "next";
import { SITE_URL } from "@/lib/seo";
import { resources } from "@/lib/data";
import { blogPosts } from "@/lib/blog-posts";

/**
 * Public, indexable routes. Auth pages, dashboards, and API surfaces
 * are intentionally excluded — see robots.ts for the disallow list.
 *
 * priority/changeFrequency are advisory hints to crawlers, not promises.
 * Keep "/" highest, then primary marketing pages, then secondary.
 */
const routes: Array<{
  path: string;
  priority: number;
  changeFrequency: "daily" | "weekly" | "monthly" | "yearly";
}> = [
  { path: "/", priority: 1.0, changeFrequency: "weekly" },
  { path: "/about", priority: 0.8, changeFrequency: "monthly" },
  { path: "/resources", priority: 0.9, changeFrequency: "monthly" },
  ...resources.map((r) => ({
    path: `/resources/${r.id}`,
    priority: 0.8,
    changeFrequency: "monthly" as const,
  })),
  { path: "/testimonials", priority: 0.7, changeFrequency: "monthly" },
  { path: "/blogs", priority: 0.7, changeFrequency: "weekly" },
  ...blogPosts.map((p) => ({
    path: `/blogs/${p.id}`,
    priority: 0.6,
    changeFrequency: "yearly" as const,
  })),
  { path: "/contact", priority: 0.7, changeFrequency: "yearly" },
  { path: "/courses", priority: 0.6, changeFrequency: "weekly" },
  { path: "/categories", priority: 0.5, changeFrequency: "monthly" },
];

export default function sitemap(): MetadataRoute.Sitemap {
  const lastModified = new Date();
  return routes.map((r) => ({
    // Next emits canonical tags without a trailing slash (even for "/"),
    // so the sitemap must use the same form or Google flags it as a
    // duplicate with a different canonical than the user submitted.
    url: r.path === "/" ? SITE_URL : `${SITE_URL}${r.path}`,
    lastModified,
    changeFrequency: r.changeFrequency,
    priority: r.priority,
  }));
}
