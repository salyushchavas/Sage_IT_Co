import { pageMeta } from "@/lib/seo";

export const metadata = {
  ...pageMeta({
  title: "Resources",
  description:
    "Learning resources from Sage IT — technology courses, certification pathways, hands-on labs, virtual learning and career growth support.",
  path: "/resources",
}),
  // A plain string title here would drop the root "%s | Sage IT" template
  // for the pages underneath, so repeat it.
  title: { default: "Resources", template: "%s | Sage IT" },
};

export default function ResourcesLayout({ children }: { children: React.ReactNode }) {
  return children;
}
