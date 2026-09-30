"use client";

import { blogPosts } from "@/lib/blog-posts";
import { staggerContainer } from "@/lib/utils";
import { motion } from "framer-motion";
import SectionHeading from "../ui/SectionHeading";
import BlogCard from "../ui/BlogCard";
import GlowButton from "../ui/GlowButton";

export default function BlogsPreview() {
  return (
    <section className="py-20 sm:py-28 lg:py-32 px-4 sm:px-6 relative">
      <div className="max-w-7xl mx-auto">
        <SectionHeading
          label="Blogs"
          title="From Our Blog"
          description="Practical advice on learning technology and building a career with it."
        />

        <motion.div
          className="grid sm:grid-cols-2 lg:grid-cols-3 gap-5 sm:gap-6 lg:gap-8"
          variants={staggerContainer}
          initial="hidden"
          whileInView="visible"
          viewport={{ once: true, margin: "-50px" }}
        >
          {blogPosts.slice(0, 3).map((post, i) => (
            <BlogCard key={post.id} post={post} index={i} />
          ))}
        </motion.div>

        <div className="text-center mt-10 sm:mt-12">
          <GlowButton href="/blogs" variant="secondary">
            Read All Blogs
          </GlowButton>
        </div>
      </div>
    </section>
  );
}
