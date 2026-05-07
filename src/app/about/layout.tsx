import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "About Us",
  description:
    "SAGEITCO LLC is a technology-first company combining deep engineering expertise with AI-driven innovation — partnering with enterprises to modernize infrastructure, secure digital assets, and unlock the power of data.",
  path: "/about",
});

export default function AboutLayout({ children }: { children: React.ReactNode }) {
  return children;
}
