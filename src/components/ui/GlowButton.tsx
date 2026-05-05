"use client";

import { cn } from "@/lib/utils";
import { motion } from "framer-motion";
import Link from "next/link";
import { ReactNode, useRef, useState } from "react";

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
  const ref = useRef<HTMLElement>(null);
  const [pos, setPos] = useState({ x: 50, y: 50 });
  const [hovered, setHovered] = useState(false);

  function handleMouseMove(e: React.MouseEvent) {
    if (!ref.current) return;
    const rect = ref.current.getBoundingClientRect();
    const x = ((e.clientX - rect.left) / rect.width) * 100;
    const y = ((e.clientY - rect.top) / rect.height) * 100;
    setPos({ x, y });
  }

  const base =
    "group relative inline-flex items-center justify-center gap-2 px-8 py-3 rounded-full font-medium text-sm tracking-wide transition-all duration-300 overflow-hidden hover:scale-[1.04]";

  const variants = {
    primary:
      "bg-sage-green text-white shadow-[0_4px_20px_rgba(15,81,50,0.25)] hover:shadow-[0_0_20px_rgba(255,215,0,0.85),0_0_40px_rgba(255,215,0,0.6),0_0_70px_rgba(255,215,0,0.35)]",
    secondary:
      "bg-white border-2 border-sage-green text-sage-green hover:text-zinc-900 hover:shadow-[0_0_20px_rgba(255,215,0,0.75),0_0_40px_rgba(255,215,0,0.5),0_0_70px_rgba(255,215,0,0.3)]",
  };

  const classes = cn(base, variants[variant], className);

  const inner = (
    <>
      {/* Cursor-following gold glow with strong opacity */}
      <span
        aria-hidden
        className="absolute inset-0 pointer-events-none transition-opacity duration-300 ease-out"
        style={{
          opacity: hovered ? 1 : 0,
          background: `radial-gradient(circle 70px at ${pos.x}% ${pos.y}%, #FFD700 0%, #FFD700 40%, rgba(255,215,0,0.55) 70%, transparent 100%)`,
          filter: "blur(2px)",
        }}
      />
      <span className="relative z-10 flex items-center gap-2">{children}</span>
    </>
  );

  const handleEnter = () => setHovered(true);
  const handleLeave = () => setHovered(false);

  if (href) {
    return (
      <Link
        ref={ref as React.RefObject<HTMLAnchorElement>}
        href={href}
        className={classes}
        onMouseMove={handleMouseMove}
        onMouseEnter={handleEnter}
        onMouseLeave={handleLeave}
      >
        {inner}
      </Link>
    );
  }

  return (
    <motion.button
      ref={ref as React.RefObject<HTMLButtonElement>}
      type={type}
      onClick={onClick}
      onMouseMove={handleMouseMove}
      onMouseEnter={handleEnter}
      onMouseLeave={handleLeave}
      className={classes}
      whileTap={{ scale: 0.96 }}
    >
      {inner}
    </motion.button>
  );
}
