import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Portfolio",
  description:
    "Case studies and real-world impact — see how SAGEITCO LLC has helped enterprises modernize cloud, secure data, and ship intelligent products.",
  path: "/portfolio",
});

export default function PortfolioLayout({ children }: { children: React.ReactNode }) {
  return children;
}
