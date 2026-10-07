import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Register",
  description:
    "Your Sage IT Co application was confirmed. Create your account to open your roadmap.",
  path: "/register",
});

export default function RegisterLayout({ children }: { children: React.ReactNode }) {
  return children;
}
