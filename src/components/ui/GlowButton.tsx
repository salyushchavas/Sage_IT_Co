"use client";

import { cn } from "@/lib/utils";
import { motion } from "framer-motion";
import Link from "next/link";
import { ReactNode } from "react";

interface GlowButtonProps {
  children: ReactNode;
  href?: string;
  onClick?: () => void;
  variant?: "primary" | "secondary";
  className?: string;
  type?: "button" | "submit";
}

export default function GlowButton({
  children,
  href,
  onClick,
  variant = "primary",
  className,
  type = "button",
}: GlowButtonProps) {
  const base =
    "group relative inline-flex items-center justify-center gap-2 px-8 py-3 rounded-full font-medium text-sm tracking-wide transition-all duration-300 overflow-hidden hover:scale-105";

  const variants = {
    primary:
      "bg-sage-green text-white shadow-glow-blue hover:shadow-glow-gold",
    secondary:
      "bg-transparent border-2 border-sage-green text-sage-green hover:text-white hover:shadow-glow-gold",
  };

  const classes = cn(base, variants[variant], className);

  const inner = (
    <>
      {/* Gold fill that slides in from left to right on hover */}
      <span
        aria-hidden
        className="absolute inset-0 bg-sage-gold origin-left scale-x-0 transition-transform duration-500 ease-out group-hover:scale-x-100"
      />
      <span className="relative z-10 flex items-center gap-2">{children}</span>
    </>
  );

  if (href) {
    return (
      <Link href={href} className={classes}>
        {inner}
      </Link>
    );
  }

  return (
    <motion.button
      type={type}
      onClick={onClick}
      className={classes}
      whileTap={{ scale: 0.95 }}
    >
      {inner}
    </motion.button>
  );
}
