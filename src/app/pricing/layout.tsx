import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Pricing",
  description:
    "Plans for individuals and teams — start free and upgrade as you grow. Compare Sage IT pricing tiers and pick the plan that scales with your goals.",
  path: "/pricing",
});

export default function PricingLayout({ children }: { children: React.ReactNode }) {
  return children;
}
