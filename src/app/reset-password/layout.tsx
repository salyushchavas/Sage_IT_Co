import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Reset password",
  description:
    "Choose a new password for your Sage IT Co account, then sign in with it.",
  path: "/reset-password",
});

export default function ResetPasswordLayout({ children }: { children: React.ReactNode }) {
  return children;
}
