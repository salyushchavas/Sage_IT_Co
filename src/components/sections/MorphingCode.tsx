"use client";

import { motion } from "framer-motion";

const services = [
  { label: "Cloud",     icon: "☁",  angle: 0,   radius: 38, color: "#0F5132", delay: 0 },
  { label: "AI / ML",   icon: "◈",  angle: 60,  radius: 42, color: "#D4A017", delay: 0.2 },
  { label: "Security",  icon: "✦",  angle: 120, radius: 36, color: "#0A3D26", delay: 0.4 },
  { label: "Data",      icon: "▦",  angle: 180, radius: 40, color: "#0F5132", delay: 0.6 },
  { label: "DevOps",    icon: "⟳",  angle: 240, radius: 38, color: "#D4A017", delay: 0.8 },
  { label: "Analytics", icon: "▲",  angle: 300, radius: 42, color: "#0A3D26", delay: 1.0 },
];

function polar(angleDeg: number, radius: number) {
  const rad = (angleDeg * Math.PI) / 180;
  return {
    left: `${50 + radius * Math.cos(rad)}%`,
    top: `${50 + radius * Math.sin(rad) * 0.55}%`,
  };
}

function DataPulse({ x1, y1, x2, y2, delay }: { x1: string; y1: string; x2: string; y2: string; delay: number }) {
  return (
    <motion.circle
      r="2.5"
      fill="#D4A017"
      initial={{ opacity: 0 }}
      animate={{
        cx: [x1, x2],
        cy: [y1, y2],
        opacity: [0, 1, 1, 0],
      }}
      transition={{
        duration: 3,
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
      <div className="absolute top-1/4 left-1/4 w-[500px] h-[500px] bg-emerald-900/10 rounded-full blur-[120px] animate-pulse-glow" />
      <div className="absolute bottom-1/4 right-1/4 w-[400px] h-[400px] bg-yellow-600/10 rounded-full blur-[100px] animate-pulse-glow" style={{ animationDelay: "1s" }} />
      <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] bg-emerald-700/5 rounded-full blur-[150px]" />

      {/* SVG network — connection lines + data pulses */}
      <svg className="absolute inset-0 w-full h-full hidden md:block" preserveAspectRatio="none">
        <defs>
          <linearGradient id="netGrad" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#0F5132" stopOpacity="0.35" />
            <stop offset="100%" stopColor="#D4A017" stopOpacity="0.35" />
          </linearGradient>
          <radialGradient id="coreGrad">
            <stop offset="0%" stopColor="#D4A017" stopOpacity="0.6" />
            <stop offset="100%" stopColor="#0F5132" stopOpacity="0" />
          </radialGradient>
        </defs>

        {/* Concentric orbital rings */}
        {[18, 28, 40].map((r, i) => (
          <motion.ellipse
            key={r}
            cx="50%"
            cy="50%"
            rx={`${r}%`}
            ry={`${r * 0.55}%`}
            fill="none"
            stroke="url(#netGrad)"
            strokeWidth="0.6"
            strokeDasharray="3 6"
            initial={{ opacity: 0, rotate: 0 }}
            animate={{ opacity: 0.35, rotate: i % 2 === 0 ? 360 : -360 }}
            transition={{
              opacity: { duration: 1.5, delay: i * 0.3 },
              rotate: { duration: 60 + i * 20, repeat: Infinity, ease: "linear" },
            }}
            style={{ transformOrigin: "50% 50%" }}
          />
        ))}

        {/* Central core glow */}
        <circle cx="50%" cy="50%" r="80" fill="url(#coreGrad)" />

        {/* Connection lines from center to each service node */}
        {services.map((s, i) => {
          const p = polar(s.angle, s.radius);
          return (
            <motion.line
              key={`line-${i}`}
              x1={center.x}
              y1={center.y}
              x2={p.left}
              y2={p.top}
              stroke="url(#netGrad)"
              strokeWidth="1"
              strokeDasharray="4 4"
              initial={{ pathLength: 0, opacity: 0 }}
              animate={{ pathLength: 1, opacity: 0.5 }}
              transition={{ duration: 1.2, delay: 0.3 + s.delay }}
            />
          );
        })}

        {/* Data pulses traveling outward */}
        {services.map((s, i) => {
          const p = polar(s.angle, s.radius);
          return (
            <DataPulse
              key={`pulse-${i}`}
              x1={center.x}
              y1={center.y}
              x2={p.left}
              y2={p.top}
              delay={i * 0.5}
            />
          );
        })}
      </svg>

      {/* Pulsing core node */}
      <motion.div
        className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 hidden md:block"
        animate={{ scale: [1, 1.15, 1] }}
        transition={{ duration: 3, repeat: Infinity, ease: "easeInOut" }}
      >
        <div className="relative">
          <div className="w-20 h-20 rounded-full bg-gradient-to-br from-emerald-900/20 to-yellow-600/20 backdrop-blur-sm border border-emerald-900/20" />
          <motion.div
            className="absolute inset-0 rounded-full border border-yellow-600/40"
            animate={{ scale: [1, 1.8], opacity: [0.6, 0] }}
            transition={{ duration: 2.5, repeat: Infinity, ease: "easeOut" }}
          />
        </div>
      </motion.div>

      {/* Orbiting service nodes */}
      {services.map((s, i) => {
        const p = polar(s.angle, s.radius);
        return (
          <motion.div
            key={s.label}
            className="absolute hidden md:flex items-center gap-2 px-3 py-1.5 rounded-full glass border border-emerald-900/15 shadow-sm"
            style={{
              left: p.left,
              top: p.top,
              transform: "translate(-50%, -50%)",
            }}
            initial={{ opacity: 0, scale: 0.6 }}
            animate={{
              opacity: 0.85,
              scale: 1,
              y: [0, -6, 0],
            }}
            transition={{
              opacity: { duration: 0.8, delay: 0.6 + s.delay },
              scale: { duration: 0.8, delay: 0.6 + s.delay },
              y: { duration: 4 + i * 0.4, repeat: Infinity, ease: "easeInOut", delay: s.delay },
            }}
          >
            <span className="text-base leading-none" style={{ color: s.color }}>{s.icon}</span>
            <span className="text-[11px] font-mono font-semibold tracking-wide" style={{ color: s.color }}>
              {s.label}
            </span>
            <motion.span
              className="w-1.5 h-1.5 rounded-full"
              style={{ backgroundColor: s.color }}
              animate={{ opacity: [0.3, 1, 0.3] }}
              transition={{ duration: 1.8, repeat: Infinity, delay: i * 0.3 }}
            />
          </motion.div>
        );
      })}

      {/* Floating ambient particles */}
      {Array.from({ length: 18 }).map((_, i) => (
        <motion.div
          key={i}
          className="absolute w-1 h-1 rounded-full bg-yellow-600/40"
          style={{
            left: `${(i * 53) % 100}%`,
            top: `${(i * 31) % 100}%`,
          }}
          animate={{
            y: [0, -60, 0],
            opacity: [0, 0.7, 0],
            scale: [0, 1.4, 0],
          }}
          transition={{
            duration: 5 + (i % 4),
            repeat: Infinity,
            delay: i * 0.3,
            ease: "easeInOut",
          }}
        />
      ))}
    </div>
  );
}
