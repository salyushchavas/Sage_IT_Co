"use client";

import dynamic from "next/dynamic";

const LoadingScreen = dynamic(() => import("./LoadingScreen"), { ssr: false });
const ScrollProgress = dynamic(() => import("./ScrollProgress"), { ssr: false });
const AnimatedCursor = dynamic(() => import("./AnimatedCursor"), { ssr: false });

export default function ClientProviders() {
  return (
    <>
      <LoadingScreen />
      <ScrollProgress />
      <AnimatedCursor />
    </>
  );
}
