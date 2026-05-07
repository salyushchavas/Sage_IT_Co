"use client";

import { motion } from "framer-motion";

// Service "cities" placed around the periphery — center stays clean for hero text
const cities = [
  { label: "Cloud",         code: "CLD", x: 8,  y: 18, color: "#1B2A5C", delay: 0.2 },
  { label: "Intelligence",  code: "AI",  x: 88, y: 14, color: "#C87D5C", delay: 0.4 },
  { label: "Security",      code: "SEC", x: 6,  y: 78, color: "#0F1F44", delay: 0.6 },
  { label: "Data",          code: "DAT", x: 92, y: 82, color: "#C87D5C", delay: 0.8 },
  { label: "DevOps",        code: "OPS", x: 14, y: 48, color: "#1B2A5C", delay: 1.0 },
  { label: "Analytics",     code: "ANL", x: 86, y: 50, color: "#0F1F44", delay: 1.2 },
];

// Trade routes — curved bezier flight paths between cities
const routes: { from: number; to: number; curve: number }[] = [
  { from: 0, to: 1, curve: -15 },
  { from: 0, to: 4, curve: 10 },
  { from: 1, to: 5, curve: -10 },
  { from: 2, to: 4, curve: -10 },
  { from: 3, to: 5, curve: 10 },
  { from: 2, to: 3, curve: 18 },
  { from: 4, to: 5, curve: -8 },
  { from: 0, to: 5, curve: 25 },
  { from: 1, to: 4, curve: -25 },
];

function bezierPath(x1: number, y1: number, x2: number, y2: number, curve: number) {
  const mx = (x1 + x2) / 2;
  const my = (y1 + y2) / 2;
  const dx = x2 - x1;
  const dy = y2 - y1;
  const nx = -dy;
  const ny = dx;
  const len = Math.hypot(nx, ny) || 1;
  const cx = mx + (nx / len) * curve;
  const cy = my + (ny / len) * curve;
  return `M ${x1} ${y1} Q ${cx} ${cy} ${x2} ${y2}`;
}

// Animated flowing contour lines — generative topography
function ContourField({ side }: { side: "left" | "right" }) {
  const lines = Array.from({ length: 7 });
  return (
    <svg
      className={`absolute top-0 ${side === "left" ? "left-0" : "right-0"} h-full w-[55%] hidden md:block`}
      viewBox="0 0 100 100"
      preserveAspectRatio="none"
      style={{ opacity: 0.18 }}
    >
      <defs>
        <linearGradient id={`contourGrad-${side}`} x1="0%" y1="0%" x2="100%" y2="0%">
          {side === "left" ? (
            <>
              <stop offset="0%" stopColor="#1B2A5C" stopOpacity="0.7" />
              <stop offset="100%" stopColor="#1B2A5C" stopOpacity="0" />
            </>
          ) : (
            <>
              <stop offset="0%" stopColor="#C87D5C" stopOpacity="0" />
              <stop offset="100%" stopColor="#C87D5C" stopOpacity="0.7" />
            </>
          )}
        </linearGradient>
      </defs>
      {lines.map((_, i) => {
        const baseY = 10 + i * 12;
        const path = side === "left"
          ? `M 0 ${baseY} Q 25 ${baseY - 8} 50 ${baseY + 4} T 100 ${baseY}`
          : `M 0 ${baseY} Q 25 ${baseY + 6} 50 ${baseY - 5} T 100 ${baseY + 2}`;
        return (
          <motion.path
            key={i}
            d={path}
            fill="none"
            stroke={`url(#contourGrad-${side})`}
            strokeWidth="0.25"
            initial={{ pathLength: 0 }}
            animate={{
              pathLength: 1,
              y: [0, side === "left" ? -2 : 2, 0],
            }}
            transition={{
              pathLength: { duration: 2, delay: i * 0.15 },
              y: { duration: 8 + i * 0.5, repeat: Infinity, ease: "easeInOut" },
            }}
          />
        );
      })}
    </svg>
  );
}

// Vintage compass rose — bottom-left corner
function CompassRose() {
  return (
    <motion.svg
      className="absolute bottom-8 left-8 hidden lg:block"
      width="90"
      height="90"
      viewBox="0 0 100 100"
      initial={{ opacity: 0, scale: 0.7 }}
      animate={{ opacity: 0.35, scale: 1 }}
      transition={{ duration: 1.5, delay: 0.5 }}
    >
      <motion.g
        animate={{ rotate: 360 }}
        transition={{ duration: 80, repeat: Infinity, ease: "linear" }}
        style={{ transformOrigin: "50px 50px" }}
      >
        <circle cx="50" cy="50" r="40" fill="none" stroke="#1B2A5C" strokeWidth="0.6" />
        <circle cx="50" cy="50" r="32" fill="none" stroke="#1B2A5C" strokeWidth="0.4" strokeDasharray="2 2" />
        {/* 4-point star */}
        <polygon points="50,8 54,46 92,50 54,54 50,92 46,54 8,50 46,46" fill="#C87D5C" fillOpacity="0.4" stroke="#1B2A5C" strokeWidth="0.5" />
        {/* Cardinal letters */}
        <text x="50" y="6" fontSize="6" fontFamily="monospace" textAnchor="middle" fill="#1B2A5C" fillOpacity="0.7">N</text>
        <text x="96" y="52" fontSize="6" fontFamily="monospace" textAnchor="middle" fill="#1B2A5C" fillOpacity="0.7">E</text>
        <text x="50" y="98" fontSize="6" fontFamily="monospace" textAnchor="middle" fill="#1B2A5C" fillOpacity="0.7">S</text>
        <text x="4" y="52" fontSize="6" fontFamily="monospace" textAnchor="middle" fill="#1B2A5C" fillOpacity="0.7">W</text>
      </motion.g>
    </motion.svg>
  );
}

// Coordinate annotation — top-right corner
function CoordinateBadge() {
  return (
    <motion.div
      className="absolute top-8 right-8 hidden lg:flex flex-col items-end gap-1 font-mono text-[10px] text-slate-900/40"
      initial={{ opacity: 0, x: 20 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ duration: 1, delay: 0.8 }}
    >
      <span className="tracking-[0.3em]">SAGE • ATLAS</span>
      <motion.span
        animate={{ opacity: [0.4, 1, 0.4] }}
        transition={{ duration: 2.5, repeat: Infinity }}
      >
        ◉ 40.7128°N · 74.0060°W
      </motion.span>
      <span>v.2026.05 · live</span>
    </motion.div>
  );
}

export default function MorphingCode() {
  return (
    <div className="absolute inset-0 z-0 overflow-hidden pointer-events-none">
      {/* Soft edge vignettes — no central glow */}
      <div className="absolute -top-40 -left-40 w-[600px] h-[600px] bg-emerald-900/[0.04] rounded-full blur-[120px]" />
      <div className="absolute -bottom-40 -right-40 w-[600px] h-[600px] bg-yellow-700/[0.04] rounded-full blur-[120px]" />

      {/* Subtle parchment grain via radial dots — drawn with SVG so it breathes */}
      <svg className="absolute inset-0 w-full h-full opacity-[0.12]" preserveAspectRatio="none">
        <defs>
          <pattern id="dots" x="0" y="0" width="32" height="32" patternUnits="userSpaceOnUse">
            <circle cx="2" cy="2" r="0.6" fill="#1B2A5C" />
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#dots)" />
      </svg>

      {/* Generative topographic contour fields on the edges */}
      <ContourField side="left" />
      <ContourField side="right" />

      {/* Trade routes & data vessels */}
      <svg className="absolute inset-0 w-full h-full hidden md:block" viewBox="0 0 100 100" preserveAspectRatio="none">
        <defs>
          <linearGradient id="routeGrad" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#1B2A5C" stopOpacity="0.5" />
            <stop offset="100%" stopColor="#C87D5C" stopOpacity="0.5" />
          </linearGradient>
        </defs>

        {routes.map((r, i) => {
          const a = cities[r.from];
          const b = cities[r.to];
          const d = bezierPath(a.x, a.y, b.x, b.y, r.curve);
          return (
            <g key={`route-${i}`}>
              <motion.path
                d={d}
                fill="none"
                stroke="url(#routeGrad)"
                strokeWidth="0.18"
                strokeDasharray="0.8 0.8"
                initial={{ pathLength: 0, opacity: 0 }}
                animate={{ pathLength: 1, opacity: 0.6 }}
                transition={{ duration: 2, delay: 1 + i * 0.15 }}
                vectorEffect="non-scaling-stroke"
              />
              {/* Data vessel travelling the route */}
              <motion.circle
                r="0.4"
                fill="#C87D5C"
                initial={{ opacity: 0 }}
                animate={{ opacity: [0, 1, 1, 0], offsetDistance: ["0%", "100%"] }}
                style={{ offsetPath: `path('${d}')` } as React.CSSProperties}
                transition={{
                  duration: 6 + (i % 3),
                  repeat: Infinity,
                  delay: 2 + i * 0.4,
                  ease: "linear",
                }}
              />
            </g>
          );
        })}

        {/* Latitude/longitude crosshairs near each city — subtle */}
        {cities.map((c, i) => (
          <g key={`xhair-${i}`} opacity="0.25">
            <motion.line
              x1={c.x - 4} y1={c.y} x2={c.x + 4} y2={c.y}
              stroke={c.color} strokeWidth="0.15"
              initial={{ pathLength: 0 }}
              animate={{ pathLength: 1 }}
              transition={{ duration: 0.8, delay: c.delay }}
              vectorEffect="non-scaling-stroke"
            />
            <motion.line
              x1={c.x} y1={c.y - 3} x2={c.x} y2={c.y + 3}
              stroke={c.color} strokeWidth="0.15"
              initial={{ pathLength: 0 }}
              animate={{ pathLength: 1 }}
              transition={{ duration: 0.8, delay: c.delay }}
              vectorEffect="non-scaling-stroke"
            />
          </g>
        ))}
      </svg>

      {/* Service "city" pins */}
      {cities.map((c, i) => (
        <motion.div
          key={c.label}
          className="absolute hidden md:block"
          style={{ left: `${c.x}%`, top: `${c.y}%`, transform: "translate(-50%, -50%)" }}
          initial={{ opacity: 0, scale: 0.5 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.8, delay: c.delay }}
        >
          <div className="relative flex flex-col items-center">
            {/* Pulsing target ring */}
            <motion.div
              className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-8 h-8 rounded-full border"
              style={{ borderColor: c.color }}
              animate={{ scale: [1, 2.2], opacity: [0.5, 0] }}
              transition={{ duration: 2.6, repeat: Infinity, delay: i * 0.4, ease: "easeOut" }}
            />
            {/* Diamond pin marker */}
            <div
              className="w-3 h-3 rotate-45 border-2 bg-white/80 backdrop-blur-sm"
              style={{ borderColor: c.color, boxShadow: `0 0 12px ${c.color}40` }}
            />
            {/* Label card */}
            <div className="mt-3 px-2.5 py-1 rounded glass border border-slate-900/15 shadow-sm">
              <div className="flex items-center gap-1.5">
                <span className="text-[8px] font-mono font-bold tracking-[0.15em]" style={{ color: c.color }}>
                  {c.code}
                </span>
                <span className="w-px h-2.5 bg-slate-900/20" />
                <span className="text-[10px] font-semibold" style={{ color: c.color }}>
                  {c.label}
                </span>
              </div>
            </div>
          </div>
        </motion.div>
      ))}

      <CompassRose />
      <CoordinateBadge />

      {/* Drifting glyphs — like dust motes / ash */}
      {Array.from({ length: 14 }).map((_, i) => (
        <motion.div
          key={i}
          className="absolute font-mono text-[10px] select-none"
          style={{
            left: `${(i * 47 + 10) % 90}%`,
            top: `${(i * 29 + 20) % 70}%`,
            color: i % 2 ? "#C87D5C" : "#1B2A5C",
            opacity: 0.18,
          }}
          animate={{
            y: [0, -40, 0],
            opacity: [0, 0.25, 0],
          }}
          transition={{
            duration: 9 + (i % 4),
            repeat: Infinity,
            delay: i * 0.6,
            ease: "easeInOut",
          }}
        >
          {["01", "△", "◇", "·", "+", "✦", "◯"][i % 7]}
        </motion.div>
      ))}
    </div>
  );
}
