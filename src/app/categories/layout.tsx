import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Categories",
  description:
    "Browse all learning paths offered by Sage IT — curated technology categories spanning cloud, AI, cybersecurity, web, and data engineering.",
  path: "/categories",
});

export default function CategoriesLayout({ children }: { children: React.ReactNode }) {
  return children;
}
