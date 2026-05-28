import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Contact",
  description:
    "Get in touch with SAGEITCO LLC. Headquartered at 4400 State Hwy 121, Suite #324, Lewisville, TX 75056. Email info@sageitco.com or send a message — we respond within 24 hours.",
  path: "/contact",
});

export default function ContactLayout({ children }: { children: React.ReactNode }) {
  return children;
}
