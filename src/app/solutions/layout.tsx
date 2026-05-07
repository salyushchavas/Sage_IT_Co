import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Solutions",
  description:
    "Purpose-built intelligent platforms from Sage IT — accelerator products that give enterprises an unfair advantage in the age of AI.",
  path: "/solutions",
});

export default function SolutionsLayout({ children }: { children: React.ReactNode }) {
  return children;
}
