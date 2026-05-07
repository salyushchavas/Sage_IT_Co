"use client";

import { useEffect, useMemo, useRef, useState } from "react";
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
 * Why initial display is the zeroed string (not the target):
 *   If we initialized with the full target, the first animation frame
 *   would snap "200+" → "0" → count up — visible flicker. Starting at
 *   the zeroed form means motion is monotonic (only upward).
 *
 * Why parsing is memoized:
 *   String.match returns a new array each render. If the parsed match is
 *   used as a useEffect dep, the effect re-runs on every render and the
 *   in-flight animation restarts from 0 — also visible flicker.
 */
export default function CountUp({ value, duration = 1.6, className }: CountUpProps) {
  const ref = useRef<HTMLSpanElement>(null);
  const inView = useInView(ref, { once: true, margin: "-80px" });

  const parsed = useMemo(() => {
    const m = value.match(/^([^\d.-]*)(-?\d+(?:\.\d+)?)(.*)$/);
    if (!m) return null;
    const [, prefix = "", numStr, suffix = ""] = m;
    return {
      prefix,
      suffix,
      target: parseFloat(numStr),
      decimals: (numStr.split(".")[1] || "").length,
    };
  }, [value]);

  const initialDisplay = useMemo(() => {
    if (!parsed) return value;
    return `${parsed.prefix}${(0).toFixed(parsed.decimals)}${parsed.suffix}`;
  }, [parsed, value]);

  const [display, setDisplay] = useState(initialDisplay);

  useEffect(() => {
    if (!inView || !parsed) return;
    const { prefix, suffix, target, decimals } = parsed;

    const controls = animate(0, target, {
      duration,
      ease: [0.22, 1, 0.36, 1],
      onUpdate: (latest) => {
        setDisplay(`${prefix}${latest.toFixed(decimals)}${suffix}`);
      },
    });
    return () => controls.stop();
  }, [inView, parsed, duration]);

  return (
    <span ref={ref} className={className} aria-label={value}>
      {display}
    </span>
  );
}
