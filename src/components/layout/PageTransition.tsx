"use client";

import { AnimatePresence, motion } from "framer-motion";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

export default function PageTransition({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  return (
    <AnimatePresence mode="wait" initial={false}>
      <motion.div
        key={pathname}
        initial={{ opacity: 0, y: 16, scale: 0.985, filter: "blur(8px)" }}
        animate={{ opacity: 1, y: 0, scale: 1, filter: "blur(0px)" }}
        exit={{ opacity: 0, y: -10, scale: 0.99, filter: "blur(6px)" }}
        transition={{
          duration: 0.55,
          ease: [0.22, 1, 0.36, 1],
          opacity: { duration: 0.4 },
        }}
      >
        {children}
      </motion.div>
    </AnimatePresence>
  );
}
