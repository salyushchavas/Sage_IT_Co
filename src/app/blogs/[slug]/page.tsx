import Link from "next/link";
import { notFound } from "next/navigation";
import type { Metadata } from "next";
import { blogPosts, formatPostDate } from "@/lib/blog-posts";
import { pageMeta } from "@/lib/seo";
import CTA from "@/components/sections/CTA";

// Only published posts exist; anything else is a 404.
export const dynamicParams = false;

export function generateStaticParams() {
  return blogPosts.map((p) => ({ slug: p.id }));
}

export function generateMetadata({ params }: { params: { slug: string } }): Metadata {
  const post = blogPosts.find((p) => p.id === params.slug);
  if (!post) return {};
  return pageMeta({
    title: post.title,
    description: post.excerpt,
    path: `/blogs/${post.id}`,
  });
}

export default function BlogPostPage({ params }: { params: { slug: string } }) {
  const post = blogPosts.find((p) => p.id === params.slug);
  if (!post) notFound();

  return (
    <>
      <section className="pt-28 sm:pt-32 pb-16 sm:pb-20 px-4 sm:px-6 bg-grid">
        <div className="max-w-3xl mx-auto text-center">
          <span className="text-xs px-3 py-1 rounded-full bg-neon-violet/10 text-neon-violet border border-neon-violet/20 mb-4 sm:mb-6 inline-block">
            {post.category}
          </span>
          <h1 className="text-3xl sm:text-4xl md:text-5xl font-bold text-zinc-900 mb-4 sm:mb-6 text-balance">
            {post.title}
          </h1>
          <p className="text-zinc-500 text-sm">
            Sage IT Team · {formatPostDate(post.date)} · {post.readMinutes} min read
          </p>
        </div>
      </section>

      <article className="py-16 sm:py-20 px-4 sm:px-6">
        <div className="max-w-3xl mx-auto">
          {post.sections.map((section, i) => (
            <section key={section.heading ?? i} className="mb-8 sm:mb-10">
              {section.heading && (
                <h2 className="text-xl sm:text-2xl font-bold text-zinc-900 mb-3 sm:mb-4">{section.heading}</h2>
              )}
              {section.paragraphs.map((p) => (
                <p key={p} className="text-zinc-600 text-base sm:text-lg leading-relaxed mb-4">
                  {p}
                </p>
              ))}
            </section>
          ))}

          <Link
            href="/blogs"
            className="inline-flex items-center gap-1 text-sm font-medium text-sage-navy hover:underline"
          >
            <span aria-hidden>&larr;</span> All blogs
          </Link>
        </div>
      </article>

      <CTA />
    </>
  );
}
