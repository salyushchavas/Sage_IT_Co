import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Reset your password",
  description:
    "Forgot your Sage IT Co password? Enter your email and we will send you a link to choose a new one.",
  path: "/forgot-password",
});

export default function ForgotPasswordLayout({ children }: { children: React.ReactNode }) {
  return children;
}
