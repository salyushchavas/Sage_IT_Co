"use client";

import { motion } from "framer-motion";

const outerServices = [
  { label: "Cloud Infrastructure", icon: "☁",  angle: 0,   radius: 60, color: "#0F5132", delay: 0 },
  { label: "AI / Machine Learning", icon: "◈", angle: 60,  radius: 64, color: "#D4A017", delay: 0.15 },
  { label: "Cybersecurity",        icon: "✦",  angle: 120, radius: 58, color: "#0A3D26", delay: 0.3 },
  { label: "Data Engineering",     icon: "▦",  angle: 180, radius: 62, color: "#0F5132", delay: 0.45 },
  { label: "DevOps / Automation",  icon: "⟳",  angle: 240, radius: 60, color: "#D4A017", delay: 0.6 },
  { label: "Analytics & BI",       icon: "▲",  angle: 300, radius: 64, color: "#0A3D26", delay: 0.75 },
];

const innerTags = [
  { label: "Kubernetes", angle: 30,  radius: 28 },
  { label: "PyTorch",    angle: 90,  radius: 26 },
  { label: "Zero Trust", angle: 150, radius: 28 },
  { label: "Snowflake",  angle: 210, radius: 26 },
  { label: "Terraform",  angle: 270, radius: 28 },
  { label: "GraphQL",    angle: 330, radius: 26 },
];

const floatingKeywords = [
  { text: "scalable", x: 12, y: 22 },
  { text: "intelligent", x: 82, y: 18 },
  { text: "resilient", x: 8, y: 78 },
  { text: "autonomous", x: 86, y: 82 },
  { text: "real-time", x: 50, y: 8 },
  { text: "secure", x: 50, y: 92 },
];

function polar(angleDeg: number, radius: number, squash = 0.55) {
  const rad = (angleDeg * Math.PI) / 180;
  return {
    left: `${50 + radius * Math.cos(rad)}%`,
    top: `${50 + radius * Math.sin(rad) * squash}%`,
  };
}

function DataPulse({ x1, y1, x2, y2, delay, color = "#D4A017" }: { x1: string; y1: string; x2: string; y2: string; delay: number; color?: string }) {
  return (
    <motion.circle
      r="3"
      fill={color}
      initial={{ opacity: 0 }}
      animate={{
        cx: [x1, x2],
        cy: [y1, y2],
        opacity: [0, 1, 1, 0],
      }}
      transition={{
        duration: 3.5,
        repeat: Infinity,
        delay,
        ease: "easeInOut",
      }}
    />
  );
}

export default function MorphingCode() {
  const center = { x: "50%", y: "50%" };

  return (
    <div className="absolute inset-0 z-0 overflow-hidden pointer-events-none">
      {/* Ambient glow blobs */}
      <div className="absolute top-1/4 left-1/4 w-[700px] h-[700px] bg-emerald-900/10 rounded-full blur-[140px] animate-pulse-glow" />
      <div className="absolute bottom-1/4 right-1/4 w-[600px] h-[600px] bg-yellow-600/10 rounded-full blur-[120px] animate-pulse-glow" style={{ animationDelay: "1s" }} />
      <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[900px] h-[900px] bg-emerald-700/5 rounded-full blur-[180px]" />

      {/* SVG layer — orbits, lines, pulses */}
      <svg className="absolute inset-0 w-full h-full hidden md:block" preserveAspectRatio="none">
        <defs>
          <linearGradient id="netGrad" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#0F5132" stopOpacity="0.4" />
            <stop offset="100%" stopColor="#D4A017" stopOpacity="0.4" />
          </linearGradient>
          <linearGradient id="netGrad2" x1="100%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%" stopColor="#D4A017" stopOpacity="0.3" />
            <stop offset="100%" stopColor="#0A3D26" stopOpacity="0.3" />
          </linearGradient>
          <radialGradient id="coreGrad">
            <stop offset="0%" stopColor="#D4A017" stopOpacity="0.7" />
            <stop offset="60%" stopColor="#0F5132" stopOpacity="0.2" />
            <stop offset="100%" stopColor="#0F5132" stopOpacity="0" />
          </radialGradient>
        </defs>

        {/* Concentric orbital rings — bigger, counter-rotating */}
        {[22, 32, 45, 58, 70].map((r, i) => (
          <motion.ellipse
            key={r}
            cx="50%"
            cy="50%"
            rx={`${r}%`}
            ry={`${r * 0.55}%`}
            fill="none"
            stroke={i % 2 === 0 ? "url(#netGrad)" : "url(#netGrad2)"}
            strokeWidth={i === 2 || i === 3 ? "0.8" : "0.5"}
            strokeDasharray={i % 2 === 0 ? "4 6" : "2 8"}
            initial={{ opacity: 0, rotate: 0 }}
            animate={{ opacity: 0.4, rotate: i % 2 === 0 ? 360 : -360 }}
            transition={{
              opacity: { duration: 1.5, delay: i * 0.2 },
              rotate: { duration: 50 + i * 25, repeat: Infinity, ease: "linear" },
            }}
            style={{ transformOrigin: "50% 50%" }}
          />
        ))}

        {/* Massive central core glow */}
        <circle cx="50%" cy="50%" r="180" fill="url(#coreGrad)" />

        {/* Connection lines: center → outer services */}
        {outerServices.map((s, i) => {
          const p = polar(s.angle, s.radius);
          return (
            <motion.line
              key={`line-out-${i}`}
              x1={center.x}
              y1={center.y}
              x2={p.left}
              y2={p.top}
              stroke="url(#netGrad)"
              strokeWidth="1"
              strokeDasharray="5 5"
              initial={{ pathLength: 0, opacity: 0 }}
              animate={{ pathLength: 1, opacity: 0.55 }}
              transition={{ duration: 1.4, delay: 0.3 + s.delay }}
            />
          );
        })}

        {/* Inter-service connections (outer ring polygon) */}
        {outerServices.map((s, i) => {
          const next = outerServices[(i + 1) % outerServices.length];
          const p1 = polar(s.angle, s.radius);
          const p2 = polar(next.angle, next.radius);
          return (
            <motion.line
              key={`ring-${i}`}
              x1={p1.left}
              y1={p1.top}
              x2={p2.left}
              y2={p2.top}
              stroke="url(#netGrad2)"
              strokeWidth="0.8"
              strokeDasharray="3 5"
              initial={{ pathLength: 0, opacity: 0 }}
              animate={{ pathLength: 1, opacity: 0.3 }}
              transition={{ duration: 1.6, delay: 1.2 + i * 0.1 }}
            />
          );
        })}

        {/* Data pulses — outward */}
        {outerServices.map((s, i) => {
          const p = polar(s.angle, s.radius);
          return (
            <DataPulse
              key={`pulse-out-${i}`}
              x1={center.x}
              y1={center.y}
              x2={p.left}
              y2={p.top}
              delay={i * 0.4}
            />
          );
        })}

        {/* Data pulses — inward (reverse flow) */}
        {outerServices.map((s, i) => {
          const p = polar(s.angle, s.radius);
          return (
            <DataPulse
              key={`pulse-in-${i}`}
              x1={p.left}
              y1={p.top}
              x2={center.x}
              y2={center.y}
              delay={i * 0.4 + 1.7}
              color="#0F5132"
            />
          );
        })}
      </svg>

      {/* Hexagonal pulsing core */}
      <motion.div
        className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 hidden md:block"
        animate={{ scale: [1, 1.12, 1] }}
        transition={{ duration: 3.5, repeat: Infinity, ease: "easeInOut" }}
      >
        <div className="relative w-32 h-32 flex items-center justify-center">
          {/* Hexagon outline */}
          <motion.svg
            viewBox="0 0 100 100"
            className="absolute inset-0 w-full h-full"
            animate={{ rotate: 360 }}
            transition={{ duration: 30, repeat: Infinity, ease: "linear" }}
          >
            <polygon
              points="50,5 90,27 90,73 50,95 10,73 10,27"
              fill="none"
              stroke="#D4A017"
              strokeWidth="1.2"
              strokeOpacity="0.6"
              strokeDasharray="4 3"
            />
          </motion.svg>
          {/* Inner hexagon counter-rotating */}
          <motion.svg
            viewBox="0 0 100 100"
            className="absolute inset-3 w-[88%] h-[88%]"
            animate={{ rotate: -360 }}
            transition={{ duration: 22, repeat: Infinity, ease: "linear" }}
          >
            <polygon
              points="50,5 90,27 90,73 50,95 10,73 10,27"
              fill="none"
              stroke="#0F5132"
              strokeWidth="1"
              strokeOpacity="0.5"
            />
          </motion.svg>
          {/* Glass core */}
          <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-emerald-900/25 via-yellow-600/15 to-emerald-900/25 backdrop-blur-md border border-yellow-600/30 flex items-center justify-center shadow-xl">
            <span className="text-2xl font-bold text-gradient">S</span>
          </div>
          {/* Expanding rings */}
          <motion.div
            className="absolute inset-0 rounded-full border border-yellow-600/40"
            animate={{ scale: [1, 2.2], opacity: [0.6, 0] }}
            transition={{ duration: 3, repeat: Infinity, ease: "easeOut" }}
          />
          <motion.div
            className="absolute inset-0 rounded-full border border-emerald-900/40"
            animate={{ scale: [1, 2.2], opacity: [0.6, 0] }}
            transition={{ duration: 3, repeat: Infinity, ease: "easeOut", delay: 1.5 }}
          />
        </div>
      </motion.div>

      {/* Inner tech tags — small orbit */}
      {innerTags.map((t, i) => {
        const p = polar(t.angle, t.radius);
        return (
          <motion.div
            key={t.label}
            className="absolute hidden lg:block px-2.5 py-1 rounded-md bg-white/40 backdrop-blur-sm border border-emerald-900/10"
            style={{ left: p.left, top: p.top, transform: "translate(-50%, -50%)" }}
            initial={{ opacity: 0, scale: 0.5 }}
            animate={{
              opacity: 0.7,
              scale: 1,
              y: [0, -4, 0],
            }}
            transition={{
              opacity: { duration: 0.6, delay: 1 + i * 0.1 },
              scale: { duration: 0.6, delay: 1 + i * 0.1 },
              y: { duration: 3 + i * 0.3, repeat: Infinity, ease: "easeInOut", delay: i * 0.2 },
            }}
          >
            <span className="text-[9px] font-mono font-medium text-emerald-900/70 tracking-wider uppercase">
              {t.label}
            </span>
          </motion.div>
        );
      })}

      {/* Outer service nodes — bigger, more prominent */}
      {outerServices.map((s, i) => {
        const p = polar(s.angle, s.radius);
        return (
          <motion.div
            key={s.label}
            className="absolute hidden md:flex items-center gap-2.5 px-4 py-2.5 rounded-2xl glass border border-emerald-900/20 shadow-lg"
            style={{
              left: p.left,
              top: p.top,
              transform: "translate(-50%, -50%)",
            }}
            initial={{ opacity: 0, scale: 0.5 }}
            animate={{
              opacity: 0.95,
              scale: 1,
              y: [0, -8, 0],
            }}
            transition={{
              opacity: { duration: 0.9, delay: 0.6 + s.delay },
              scale: { duration: 0.9, delay: 0.6 + s.delay },
              y: { duration: 4 + i * 0.4, repeat: Infinity, ease: "easeInOut", delay: s.delay },
            }}
          >
            <span className="text-xl leading-none" style={{ color: s.color }}>{s.icon}</span>
            <span className="text-[13px] font-mono font-semibold tracking-wide whitespace-nowrap" style={{ color: s.color }}>
              {s.label}
            </span>
            <motion.span
              className="w-2 h-2 rounded-full"
              style={{ backgroundColor: s.color }}
              animate={{ opacity: [0.3, 1, 0.3], scale: [1, 1.3, 1] }}
              transition={{ duration: 1.8, repeat: Infinity, delay: i * 0.3 }}
            />
          </motion.div>
        );
      })}

      {/* Floating brand keywords — large, faint, drifting */}
      {floatingKeywords.map((k, i) => (
        <motion.div
          key={k.text}
          className="absolute hidden lg:block font-mono italic select-none"
          style={{
            left: `${k.x}%`,
            top: `${k.y}%`,
            transform: "translate(-50%, -50%)",
            fontSize: "clamp(20px, 2.4vw, 36px)",
            color: i % 2 === 0 ? "#0F5132" : "#D4A017",
            opacity: 0.08,
          }}
          animate={{
            x: [0, 12, -8, 0],
            y: [0, -10, 6, 0],
          }}
          transition={{
            duration: 18 + i * 2,
            repeat: Infinity,
            ease: "easeInOut",
            delay: i * 0.5,
          }}
        >
          {k.text}
        </motion.div>
      ))}

      {/* Floating ambient particles */}
      {Array.from({ length: 28 }).map((_, i) => (
        <motion.div
          key={i}
          className="absolute w-1 h-1 rounded-full"
          style={{
            left: `${(i * 53) % 100}%`,
            top: `${(i * 31) % 100}%`,
            backgroundColor: i % 3 === 0 ? "#D4A017" : "#0F5132",
            opacity: 0.5,
          }}
          animate={{
            y: [0, -80, 0],
            opacity: [0, 0.7, 0],
            scale: [0, 1.6, 0],
          }}
          transition={{
            duration: 5 + (i % 5),
            repeat: Infinity,
            delay: i * 0.25,
            ease: "easeInOut",
          }}
        />
      ))}
    </div>
  );
}
