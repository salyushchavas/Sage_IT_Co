import { pageMeta } from "@/lib/seo";

export const metadata = pageMeta({
  title: "Testimonials",
  description:
    "What people say about working with Sage IT — feedback from the clients and teams we have supported.",
  path: "/testimonials",
});

export default function TestimonialsLayout({ children }: { children: React.ReactNode }) {
  return children;
}
