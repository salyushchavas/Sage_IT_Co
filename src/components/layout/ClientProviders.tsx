"use client";

import { useEffect, useState } from "react";
import LoadingScreen from "./LoadingScreen";
import ScrollProgress from "./ScrollProgress";
import AnimatedCursor from "./AnimatedCursor";

export default function ClientProviders() {
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
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
