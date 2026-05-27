"use client";

import { useEffect, useState } from "react";
import LoadingScreen from "./LoadingScreen";
import ScrollProgress from "./ScrollProgress";
import AnimatedCursor from "./AnimatedCursor";

export default function ClientProviders() {
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);

    // Spotlight cursor tracker for .btn-fill buttons
    function handleMove(e: MouseEvent) {
      const target = e.target as HTMLElement | null;
      if (!target) return;
      const btn = target.closest(".btn-fill") as HTMLElement | null;
      if (!btn) return;
      const rect = btn.getBoundingClientRect();
      const x = ((e.clientX - rect.left) / rect.width) * 100;
      const y = ((e.clientY - rect.top) / rect.height) * 100;
      btn.style.setProperty("--spot-x", `${x}%`);
      btn.style.setProperty("--spot-y", `${y}%`);
    }
    document.addEventListener("mousemove", handleMove);
    return () => document.removeEventListener("mousemove", handleMove);
  }, []);

  if (!mounted) return null;

  return (
    <>
      <LoadingScreen />
      <ScrollProgress />
      <AnimatedCursor />
    </>
  );
}
