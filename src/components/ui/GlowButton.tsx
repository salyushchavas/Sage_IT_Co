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
    "relative inline-flex items-center justify-center gap-2 px-8 py-3 rounded-full font-medium text-sm tracking-wide transition-all duration-300 overflow-hidden";

  const variants = {
    primary:
      "bg-gradient-to-r from-neon-blue via-neon-violet to-neon-cyan text-white shadow-glow-blue hover:shadow-glow-violet hover:scale-105",
    secondary:
      "glass border-white/20 text-white hover:border-neon-blue/50 hover:shadow-glow-blue hover:scale-105",
  };

  const classes = cn(base, variants[variant], className);

  const inner = (
    <>
      <span className="relative z-10">{children}</span>
      {variant === "primary" && (
        <motion.div
          className="absolute inset-0 bg-gradient-to-r from-neon-cyan via-neon-violet to-neon-blue opacity-0 hover:opacity-100 transition-opacity duration-500"
          whileHover={{ opacity: 1 }}
        />
      )}
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
