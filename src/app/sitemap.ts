import type { MetadataRoute } from "next";
import { SITE_URL } from "@/lib/seo";

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
  { path: "/services", priority: 0.9, changeFrequency: "monthly" },
  { path: "/solutions", priority: 0.9, changeFrequency: "monthly" },
  { path: "/portfolio", priority: 0.8, changeFrequency: "monthly" },
  { path: "/about", priority: 0.7, changeFrequency: "monthly" },
  { path: "/careers", priority: 0.7, changeFrequency: "weekly" },
  { path: "/contact", priority: 0.7, changeFrequency: "yearly" },
  { path: "/courses", priority: 0.6, changeFrequency: "weekly" },
  { path: "/categories", priority: 0.5, changeFrequency: "monthly" },
  { path: "/pricing", priority: 0.6, changeFrequency: "monthly" },
];

export default function sitemap(): MetadataRoute.Sitemap {
  const lastModified = new Date();
  return routes.map((r) => ({
    url: `${SITE_URL}${r.path}`,
    lastModified,
    changeFrequency: r.changeFrequency,
    priority: r.priority,
  }));
}
