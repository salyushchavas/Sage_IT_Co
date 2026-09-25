import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Enroll",
  description:
    "Start your career journey with Sage IT Co: create your account, verify your email and get your Participant ID.",
  path: "/enroll",
});

export default function EnrollLayout({ children }: { children: React.ReactNode }) {
  return children;
}
