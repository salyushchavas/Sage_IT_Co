"use client";

import { cn } from "@/lib/utils";
import { motion, useMotionValue, useSpring, useTransform } from "framer-motion";
import Link from "next/link";
import { ReactNode, useRef, useState } from "react";

interface GlowButtonProps {
  children: ReactNode;
  href?: string;
  onClick?: () => void;
  variant?: "primary" | "secondary";
  className?: string;
  type?: "button" | "submit";
  disabled?: boolean;
}

/**
 * Magnetic CTA button — the button gently follows the cursor when hovered.
 * The pull is small (max 8px) and damped via spring so it feels alive but
 * never disorienting. Combined with the existing spotlight + scale, this
 * gives a satisfying "tactile" hover state.
 */
export default function GlowButton({
  children,
  href,
  onClick,
  variant = "primary",
  className,
  type = "button",
  disabled = false,
}: GlowButtonProps) {
  const ref = useRef<HTMLElement>(null);
  const [pos, setPos] = useState({ x: 50, y: 50 });
  const [hovered, setHovered] = useState(false);

  const mx = useMotionValue(0);
  const my = useMotionValue(0);
  const sx = useSpring(mx, { stiffness: 220, damping: 18, mass: 0.4 });
  const sy = useSpring(my, { stiffness: 220, damping: 18, mass: 0.4 });
  // Subtle inner-content lift in the opposite direction for depth
  const ix = useTransform(sx, (v) => v * -0.25);
  const iy = useTransform(sy, (v) => v * -0.25);

  function handleMouseMove(e: React.MouseEvent) {
    if (!ref.current) return;
    const rect = ref.current.getBoundingClientRect();
    const xPct = ((e.clientX - rect.left) / rect.width) * 100;
    const yPct = ((e.clientY - rect.top) / rect.height) * 100;
    setPos({ x: xPct, y: yPct });

    // Magnetic pull — relative to button center, capped at ~8px
    const cx = e.clientX - (rect.left + rect.width / 2);
    const cy = e.clientY - (rect.top + rect.height / 2);
    const max = 8;
    mx.set(Math.max(-max, Math.min(max, cx * 0.3)));
    my.set(Math.max(-max, Math.min(max, cy * 0.3)));
  }

  function handleEnter() {
    setHovered(true);
  }
  function handleLeave() {
    setHovered(false);
    mx.set(0);
    my.set(0);
  }

  const base =
    "relative inline-flex items-center justify-center gap-2 px-6 sm:px-8 py-3 min-h-[44px] rounded-full font-medium text-sm tracking-wide transition-shadow duration-300 overflow-hidden hover:scale-[1.03] sheen-on-hover disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:scale-100";

  const variants = {
    primary:
      "bg-sage-green text-white shadow-[0_4px_20px_rgba(27,42,92,0.25)] hover:shadow-[0_8px_30px_rgba(200,125,92,0.35)]",
    secondary:
      "bg-white border-2 border-sage-green text-sage-green hover:shadow-[0_8px_30px_rgba(27,42,92,0.18)]",
  };

  const classes = cn(base, variants[variant], className);

  const inner = (
    <>
      {/* Spotlight — bright focused light at cursor */}
      <span
        aria-hidden
        className="absolute inset-0 pointer-events-none transition-opacity duration-200 ease-out z-[2]"
        style={{
          opacity: hovered ? 1 : 0,
          background: `radial-gradient(circle 110px at ${pos.x}% ${pos.y}%, rgba(255, 215, 0, 0.65) 0%, rgba(255, 215, 0, 0.35) 35%, rgba(255, 215, 0, 0.15) 60%, transparent 85%)`,
        }}
      />
      <motion.span
        className="relative z-[3] flex items-center gap-2"
        style={{ x: ix, y: iy }}
      >
        {children}
      </motion.span>
    </>
  );

  if (href) {
    // Anchors keep scale + sheen + spotlight (no magnetic spring) so they
    // preserve their natural width inside flex layouts (items-stretch, w-full).
    return (
      <Link
        ref={ref as React.RefObject<HTMLAnchorElement>}
        href={href}
        className={classes}
        onMouseMove={(e) => {
          if (!ref.current) return;
          const rect = ref.current.getBoundingClientRect();
          const xPct = ((e.clientX - rect.left) / rect.width) * 100;
          const yPct = ((e.clientY - rect.top) / rect.height) * 100;
          setPos({ x: xPct, y: yPct });
        }}
        onMouseEnter={handleEnter}
        onMouseLeave={() => setHovered(false)}
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
      disabled={disabled}
      onMouseMove={handleMouseMove}
      onMouseEnter={handleEnter}
      onMouseLeave={handleLeave}
      className={classes}
      style={{ x: sx, y: sy }}
      whileTap={{ scale: 0.96 }}
    >
      {inner}
    </motion.button>
  );
}
