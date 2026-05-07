import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Courses",
  description:
    "Hands-on technology training from Sage IT — courses across cloud, AI, security, and modern web development to advance your engineering skills.",
  path: "/courses",
});

export default function CoursesLayout({ children }: { children: React.ReactNode }) {
  return children;
}
