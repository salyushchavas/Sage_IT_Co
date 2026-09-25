import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Sign in",
  description:
    "Sign in to your Sage IT Co account.",
  path: "/login",
});

export default function LoginLayout({ children }: { children: React.ReactNode }) {
  return children;
}
