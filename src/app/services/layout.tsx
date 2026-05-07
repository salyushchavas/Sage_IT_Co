import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Services",
  description:
    "End-to-end technology services from Sage IT — Cloud, Cybersecurity, Web Development, AI, Digital Marketing, and Data & Analytics for the modern enterprise.",
  path: "/services",
});

export default function ServicesLayout({ children }: { children: React.ReactNode }) {
  return children;
}
