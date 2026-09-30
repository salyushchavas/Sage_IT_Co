import { notFound } from "next/navigation";
import type { Metadata } from "next";
import { resources } from "@/lib/data";
import { pageMeta } from "@/lib/seo";
import ResourceDetail from "@/components/sections/ResourceDetail";

// Only the five known resources exist; anything else is a 404.
export const dynamicParams = false;

export function generateStaticParams() {
  return resources.map((r) => ({ slug: r.id }));
}

export function generateMetadata({ params }: { params: { slug: string } }): Metadata {
  const resource = resources.find((r) => r.id === params.slug);
  if (!resource) return {};
  return pageMeta({
    title: resource.title,
    description: resource.description,
    path: `/resources/${resource.id}`,
  });
}

export default function ResourcePage({ params }: { params: { slug: string } }) {
  const resource = resources.find((r) => r.id === params.slug);
  if (!resource) notFound();
  return <ResourceDetail resource={resource} />;
}
