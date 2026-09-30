import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Apply",
  description:
    "Start your career journey with Sage IT Co: choose a course and apply. Once your application is confirmed, we email you a link to register.",
  path: "/enroll",
});

export default function EnrollLayout({ children }: { children: React.ReactNode }) {
  return children;
}
