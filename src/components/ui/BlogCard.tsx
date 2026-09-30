"use client";

import { BlogPost, formatPostDate } from "@/lib/blog-posts";
import { popIn } from "@/lib/utils";
import { motion } from "framer-motion";
import Link from "next/link";
import GlassCard from "./GlassCard";

export default function BlogCard({ post, index }: { post: BlogPost; index: number }) {
  return (
    <motion.div variants={popIn} custom={index} className="h-full">
      <Link href={`/blogs/${post.id}`} className="block h-full rounded-2xl">
        <GlassCard hover3D glowColor="#E8A78D" className="p-6 sm:p-8 h-full glow-border transition-all duration-500 hover:-translate-y-1">
          <span className="text-xs px-3 py-1 rounded-full bg-neon-violet/10 text-neon-violet border border-neon-violet/20 mb-3 sm:mb-4 inline-block">
            {post.category}
          </span>
          <h3 className="text-lg sm:text-xl font-bold text-zinc-900 mb-2 sm:mb-3">{post.title}</h3>
          <p className="text-zinc-600 text-sm leading-relaxed mb-4 sm:mb-6">{post.excerpt}</p>
          <div className="flex items-center justify-between gap-3 text-xs text-zinc-500">
            <span>
              {formatPostDate(post.date)} · {post.readMinutes} min read
            </span>
            <span className="text-sm font-medium text-sage-navy whitespace-nowrap">
              Read <span aria-hidden>&rarr;</span>
            </span>
          </div>
        </GlassCard>
      </Link>
    </motion.div>
  );
}
