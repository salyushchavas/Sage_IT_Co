"use client";

import { cn } from "@/lib/utils";

interface ProtectedOverlayProps {
  email: string;
  className?: string;
}

export default function ProtectedOverlay({ email, className }: ProtectedOverlayProps) {
  return (
    <div
      className={cn(
        "absolute inset-0 pointer-events-none select-none z-30 overflow-hidden",
        className
      )}
      style={{
        /* Block screen capture in supported browsers */
        WebkitUserSelect: "none",
        userSelect: "none",
      }}
    >
      {/* Diagonal watermark pattern */}
      <div
        className="absolute inset-0"
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: "80px",
          padding: "40px",
          transform: "rotate(-30deg) scale(1.5)",
          transformOrigin: "center center",
        }}
      >
        {Array.from({ length: 20 }).map((_, i) => (
          <span
            key={i}
            className="text-zinc-900/[0.04] text-sm font-mono whitespace-nowrap"
            style={{ letterSpacing: "0.05em" }}
          >
            {email}
          </span>
        ))}
      </div>
    </div>
  );
}
