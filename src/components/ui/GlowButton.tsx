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
    "group relative inline-flex items-center justify-center gap-2 px-8 py-3 rounded-full font-medium text-sm tracking-wide transition-all duration-300 overflow-hidden hover:scale-[1.04]";

  const variants = {
    primary:
      "bg-sage-green text-white shadow-[0_4px_20px_rgba(15,81,50,0.25)] hover:shadow-[0_0_20px_rgba(255,215,0,0.85),0_0_40px_rgba(255,215,0,0.6),0_0_70px_rgba(255,215,0,0.35)]",
    secondary:
      "bg-white border-2 border-sage-green text-sage-green hover:shadow-[0_0_20px_rgba(255,215,0,0.75),0_0_40px_rgba(255,215,0,0.5),0_0_70px_rgba(255,215,0,0.3)]",
  };

  const classes = cn(base, variants[variant], className);

  const inner = (
    <>
      {/* Shimmer sweep — diagonal shine that slides across on hover */}
      <span
        aria-hidden
        className="absolute inset-0 pointer-events-none -translate-x-full group-hover:translate-x-full transition-transform duration-[900ms] ease-out"
        style={{
          background:
            "linear-gradient(110deg, transparent 30%, rgba(255, 215, 0, 0.55) 50%, transparent 70%)",
        }}
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
      whileTap={{ scale: 0.96 }}
    >
      {inner}
    </motion.button>
  );
}
