import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Support",
  description:
    "Help with your Sage IT Co account, program, documents and payments: answers to common questions and how to reach our team.",
  path: "/support",
});

export default function SupportLayout({ children }: { children: React.ReactNode }) {
  return children;
}
