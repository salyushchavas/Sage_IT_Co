"use client";

import { useEffect, useRef, useState } from "react";
import { useInView, animate } from "framer-motion";

interface CountUpProps {
  value: string;
  duration?: number;
  className?: string;
}

/**
 * Parses a numeric value from a string like "200+", "99.9%", "50+" and
 * animates it from 0 → target when first scrolled into view. Surrounding
 * non-numeric characters (prefix and suffix) are preserved verbatim.
 *
 * Why: stat figures feel static. Counting up draws the eye and signals
 * scale at the moment the user notices the number.
 */
export default function CountUp({ value, duration = 1.6, className }: CountUpProps) {
  const ref = useRef<HTMLSpanElement>(null);
  const inView = useInView(ref, { once: true, margin: "-80px" });
  const [display, setDisplay] = useState(value);

  // Pull leading non-digit prefix, numeric core (with optional decimal), trailing suffix.
  const match = value.match(/^([^\d.-]*)(-?\d+(?:\.\d+)?)(.*)$/);

  useEffect(() => {
    if (!inView || !match) return;
    const [, prefix = "", numStr, suffix = ""] = match;
    const target = parseFloat(numStr);
    const decimals = (numStr.split(".")[1] || "").length;

    const controls = animate(0, target, {
      duration,
      ease: [0.22, 1, 0.36, 1],
      onUpdate: (latest) => {
        setDisplay(`${prefix}${latest.toFixed(decimals)}${suffix}`);
      },
    });
    return () => controls.stop();
  }, [inView, match, duration]);

  // Fallback for non-numeric strings — render verbatim.
  if (!match) {
    return <span ref={ref} className={className}>{value}</span>;
  }

  return (
    <span ref={ref} className={className}>
      {display}
    </span>
  );
}
