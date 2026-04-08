"use client";

import { useMousePosition } from "@/hooks/useMousePosition";
import { motion } from "framer-motion";
import { useEffect, useState } from "react";

export default function AnimatedCursor() {
  const { x, y } = useMousePosition();
  const [visible, setVisible] = useState(false);
  const [isTouchDevice, setIsTouchDevice] = useState(false);

  useEffect(() => {
    setIsTouchDevice("ontouchstart" in window);
    const handleEnter = () => setVisible(true);
    const handleLeave = () => setVisible(false);
    document.addEventListener("mouseenter", handleEnter);
    document.addEventListener("mouseleave", handleLeave);
    setVisible(true);
    return () => {
      document.removeEventListener("mouseenter", handleEnter);
      document.removeEventListener("mouseleave", handleLeave);
    };
  }, []);

  if (isTouchDevice) return null;

  return (
    <>
      {/* Dot */}
      <motion.div
        className="fixed top-0 left-0 w-2 h-2 rounded-full bg-neon-blue z-[100] pointer-events-none mix-blend-difference"
        animate={{ x: x - 4, y: y - 4, opacity: visible ? 1 : 0 }}
        transition={{ type: "spring", stiffness: 500, damping: 28, mass: 0.5 }}
      />
      {/* Ring */}
      <motion.div
        className="fixed top-0 left-0 w-8 h-8 rounded-full border border-neon-blue/50 z-[100] pointer-events-none"
        animate={{ x: x - 16, y: y - 16, opacity: visible ? 0.5 : 0 }}
        transition={{ type: "spring", stiffness: 150, damping: 15, mass: 0.5 }}
      />
    </>
  );
}
