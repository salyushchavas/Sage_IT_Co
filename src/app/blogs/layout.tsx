import { pageMeta } from "@/lib/seo";

export const metadata = {
  ...pageMeta({
  title: "Blogs",
  description:
    "The Sage IT blog — practical advice on learning technology, building study habits and preparing for your next role.",
  path: "/blogs",
}),
  // A plain string title here would drop the root "%s | Sage IT" template
  // for the pages underneath, so repeat it.
  title: { default: "Blogs", template: "%s | Sage IT" },
};

export default function BlogsLayout({ children }: { children: React.ReactNode }) {
  return children;
}
