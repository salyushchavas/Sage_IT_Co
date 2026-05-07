import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Careers",
  description:
    "Join SAGEITCO LLC — build the future of technology with a remote-first team that values ownership, learning, and impact. Browse open roles and perks.",
  path: "/careers",
});

export default function CareersLayout({ children }: { children: React.ReactNode }) {
  return children;
}
