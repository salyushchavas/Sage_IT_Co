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
    "relative inline-flex items-center justify-center gap-2 px-6 sm:px-8 py-3 min-h-[44px] rounded-full font-medium text-sm tracking-wide transition-transform duration-300 overflow-hidden hover:scale-[1.03]";

  const variants = {
    primary: "bg-sage-green text-white shadow-[0_4px_20px_rgba(27,42,92,0.25)]",
    secondary: "bg-white border-2 border-sage-green text-sage-green",
  };

  const classes = cn(base, variants[variant], className);

  const inner = (
    <>
      {/* Spotlight — bright focused light at cursor */}
      <span
        aria-hidden
        className="absolute inset-0 pointer-events-none transition-opacity duration-200 ease-out"
        style={{
          opacity: hovered ? 1 : 0,
          background: `radial-gradient(circle 110px at ${pos.x}% ${pos.y}%, rgba(255, 215, 0, 0.65) 0%, rgba(255, 215, 0, 0.35) 35%, rgba(255, 215, 0, 0.15) 60%, transparent 85%)`,
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
