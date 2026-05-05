"use client";

import { useEffect, useRef, useState, useCallback } from "react";
import { motion, AnimatePresence } from "framer-motion";

// Real code snippets representing different tech domains
const codeSnippets = [
  {
    label: "cloud.tf",
    lang: "terraform",
    lines: [
      { text: 'resource "aws_lambda_function" "ai_engine" {', color: "#D4A017" },
      { text: '  function_name = "sage-inference"', color: "#0A3D26" },
      { text: '  runtime       = "python3.12"', color: "#0A3D26" },
      { text: '  memory_size   = 2048', color: "#0F5132" },
      { text: '  timeout       = 30', color: "#0F5132" },
      { text: "", color: "#555" },
      { text: "  environment {", color: "#D4A017" },
      { text: "    variables = {", color: "#E8B82E" },
      { text: '      MODEL_ENDPOINT = var.sagemaker_ep', color: "#0A3D26" },
      { text: '      CACHE_TTL      = "3600"', color: "#0A3D26" },
      { text: "    }", color: "#E8B82E" },
      { text: "  }", color: "#D4A017" },
      { text: "}", color: "#D4A017" },
    ],
  },
  {
    label: "model.py",
    lang: "python",
    lines: [
      { text: "import torch", color: "#0F5132" },
      { text: "from transformers import AutoModel", color: "#0F5132" },
      { text: "", color: "#555" },
      { text: "class SagePredictor(nn.Module):", color: "#D4A017" },
      { text: "    def __init__(self, config):", color: "#E8B82E" },
      { text: "        super().__init__()", color: "#0A3D26" },
      { text: '        self.encoder = AutoModel.from_pretrained("sage-xl")', color: "#0A3D26" },
      { text: "        self.head = nn.Linear(768, config.num_classes)", color: "#0A3D26" },
      { text: "", color: "#555" },
      { text: "    def forward(self, x):", color: "#E8B82E" },
      { text: "        embeddings = self.encoder(x).last_hidden_state", color: "#0A3D26" },
      { text: "        return self.head(embeddings[:, 0])", color: "#0A3D26" },
      { text: '        # accuracy: 97.3% | latency: 12ms', color: "#555" },
    ],
  },
  {
    label: "deploy.yaml",
    lang: "kubernetes",
    lines: [
      { text: "apiVersion: apps/v1", color: "#0F5132" },
      { text: "kind: Deployment", color: "#D4A017" },
      { text: "metadata:", color: "#E8B82E" },
      { text: "  name: sage-api-gateway", color: "#0A3D26" },
      { text: "  namespace: production", color: "#0A3D26" },
      { text: "spec:", color: "#E8B82E" },
      { text: "  replicas: 12", color: "#0F5132" },
      { text: "  strategy:", color: "#E8B82E" },
      { text: "    type: RollingUpdate", color: "#0A3D26" },
      { text: "    maxSurge: 25%", color: "#0A3D26" },
      { text: "  template:", color: "#E8B82E" },
      { text: "    spec:", color: "#E8B82E" },
      { text: "      containers:", color: "#D4A017" },
    ],
  },
  {
    label: "security.rs",
    lang: "rust",
    lines: [
      { text: "use sage_crypto::shield;", color: "#0F5132" },
      { text: "use zero_trust::verify;", color: "#0F5132" },
      { text: "", color: "#555" },
      { text: "#[derive(Shield)]", color: "#E8B82E" },
      { text: "pub struct ThreatEngine {", color: "#D4A017" },
      { text: "    model: NeuralDetector,", color: "#0A3D26" },
      { text: "    policy: ZeroTrustPolicy,", color: "#0A3D26" },
      { text: "}", color: "#D4A017" },
      { text: "", color: "#555" },
      { text: "impl ThreatEngine {", color: "#D4A017" },
      { text: "    pub async fn scan(&self, req: &Request)", color: "#E8B82E" },
      { text: "        -> Result<Verdict, SecurityError> {", color: "#E8B82E" },
      { text: "        let score = self.model.analyze(req).await?;", color: "#0A3D26" },
    ],
  },
  {
    label: "analytics.tsx",
    lang: "typescript",
    lines: [
      { text: 'import { Pipeline } from "@sage/data-nexus";', color: "#0F5132" },
      { text: 'import { useRealtime } from "@sage/hooks";', color: "#0F5132" },
      { text: "", color: "#555" },
      { text: "export function InsightDashboard() {", color: "#D4A017" },
      { text: "  const { stream, metrics } = useRealtime({", color: "#E8B82E" },
      { text: '    source: "kafka://events",', color: "#0A3D26" },
      { text: "    window: '5m',", color: "#0A3D26" },
      { text: "    aggregate: ['count', 'p99_latency'],", color: "#0A3D26" },
      { text: "  });", color: "#E8B82E" },
      { text: "", color: "#555" },
      { text: "  return (", color: "#D4A017" },
      { text: "    <Pipeline.Visualize data={stream} />", color: "#0A3D26" },
      { text: "  );", color: "#D4A017" },
    ],
  },
];

// Floating particle component
function Particle({ delay }: { delay: number }) {
  return (
    <motion.div
      className="absolute w-1 h-1 rounded-full bg-neon-blue/50"
      style={{
        left: `${Math.random() * 100}%`,
        top: `${Math.random() * 100}%`,
      }}
      animate={{
        y: [0, -80, 0],
        x: [0, (Math.random() - 0.5) * 40, 0],
        opacity: [0, 0.6, 0],
        scale: [0, 1.5, 0],
      }}
      transition={{
        duration: 4 + Math.random() * 3,
        repeat: Infinity,
        delay,
        ease: "easeInOut",
      }}
    />
  );
}

export default function MorphingCode() {
  const [activeIndex, setActiveIndex] = useState(0);
  const [typedLines, setTypedLines] = useState<number>(0);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const currentSnippet = codeSnippets[activeIndex];

  // Typing effect - reveal lines one by one
  useEffect(() => {
    setTypedLines(0);
    let line = 0;
    const timer = setInterval(() => {
      line++;
      setTypedLines(line);
      if (line >= currentSnippet.lines.length) {
        clearInterval(timer);
      }
    }, 120);
    return () => clearInterval(timer);
  }, [activeIndex, currentSnippet.lines.length]);

  // Auto-cycle snippets
  const cycleNext = useCallback(() => {
    setActiveIndex((prev) => (prev + 1) % codeSnippets.length);
  }, []);

  useEffect(() => {
    intervalRef.current = setInterval(cycleNext, 4500);
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [cycleNext]);

  return (
    <div className="absolute inset-0 z-0 overflow-hidden">
      {/* Ambient glow blobs */}
      <div className="absolute top-1/4 left-1/4 w-[500px] h-[500px] bg-neon-violet/8 rounded-full blur-[120px] animate-pulse-glow" />
      <div className="absolute bottom-1/4 right-1/4 w-[400px] h-[400px] bg-neon-blue/8 rounded-full blur-[100px] animate-pulse-glow" style={{ animationDelay: "1s" }} />
      <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] bg-neon-cyan/5 rounded-full blur-[150px]" />

      {/* Floating particles */}
      {Array.from({ length: 20 }).map((_, i) => (
        <Particle key={i} delay={i * 0.3} />
      ))}

      {/* Code blocks - left side */}
      <motion.div
        className="absolute left-[5%] top-[15%] w-[40%] max-w-[500px] hidden lg:block"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.5, duration: 1 }}
      >
        <div className="glass rounded-xl overflow-hidden shadow-glow-blue/20">
          {/* Tab bar */}
          <div className="flex items-center gap-2 px-4 py-3 border-b border-zinc-200 bg-white/[0.02]">
            <div className="flex gap-1.5">
              <div className="w-3 h-3 rounded-full bg-red-500/60" />
              <div className="w-3 h-3 rounded-full bg-yellow-500/60" />
              <div className="w-3 h-3 rounded-full bg-green-500/60" />
            </div>
            <AnimatePresence mode="wait">
              <motion.span
                key={currentSnippet.label}
                className="text-xs text-zinc-500 ml-3 font-mono"
                initial={{ opacity: 0, y: -8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: 8 }}
                transition={{ duration: 0.3 }}
              >
                {currentSnippet.label}
              </motion.span>
            </AnimatePresence>
            {/* Snippet indicator dots */}
            <div className="ml-auto flex gap-1.5">
              {codeSnippets.map((_, i) => (
                <button
                  key={i}
                  onClick={() => {
                    setActiveIndex(i);
                    if (intervalRef.current) clearInterval(intervalRef.current);
                    intervalRef.current = setInterval(cycleNext, 4500);
                  }}
                  className={`w-2 h-2 rounded-full transition-all duration-300 ${
                    i === activeIndex ? "bg-neon-blue scale-125" : "bg-zinc-300 hover:bg-zinc-400"
                  }`}
                />
              ))}
            </div>
          </div>

          {/* Code content */}
          <div className="p-5 font-mono text-[13px] leading-6 min-h-[340px]">
            <AnimatePresence mode="wait">
              <motion.div
                key={activeIndex}
                initial={{ opacity: 0, filter: "blur(4px)" }}
                animate={{ opacity: 1, filter: "blur(0px)" }}
                exit={{ opacity: 0, filter: "blur(4px)" }}
                transition={{ duration: 0.4 }}
              >
                {currentSnippet.lines.map((line, i) => (
                  <motion.div
                    key={i}
                    className="flex"
                    initial={{ opacity: 0, x: -10 }}
                    animate={{
                      opacity: i < typedLines ? 1 : 0,
                      x: i < typedLines ? 0 : -10,
                    }}
                    transition={{ duration: 0.15 }}
                  >
                    <span className="text-zinc-500 w-8 text-right mr-4 select-none shrink-0">
                      {i + 1}
                    </span>
                    <span style={{ color: line.color }}>
                      {line.text}
                      {/* Typing cursor on current line */}
                      {i === typedLines - 1 && i < currentSnippet.lines.length - 1 && (
                        <motion.span
                          className="inline-block w-[2px] h-4 bg-neon-blue ml-0.5 align-middle"
                          animate={{ opacity: [1, 0] }}
                          transition={{ duration: 0.5, repeat: Infinity }}
                        />
                      )}
                    </span>
                  </motion.div>
                ))}
              </motion.div>
            </AnimatePresence>
          </div>

          {/* Status bar */}
          <div className="flex items-center justify-between px-4 py-2 border-t border-zinc-200 bg-white/[0.02]">
            <span className="text-[10px] text-zinc-600 font-mono">{currentSnippet.lang}</span>
            <div className="flex items-center gap-3">
              <span className="text-[10px] text-zinc-600">UTF-8</span>
              <motion.span
                className="text-[10px] text-neon-blue font-medium"
                animate={{ opacity: [0.5, 1, 0.5] }}
                transition={{ duration: 2, repeat: Infinity }}
              >
                ● deploying
              </motion.span>
            </div>
          </div>
        </div>
      </motion.div>

      {/* Code blocks - right side (secondary, offset) */}
      <motion.div
        className="absolute right-[5%] bottom-[12%] w-[35%] max-w-[420px] hidden lg:block"
        initial={{ opacity: 0 }}
        animate={{ opacity: 0.4 }}
        transition={{ delay: 1, duration: 1 }}
      >
        <div className="glass rounded-xl overflow-hidden">
          <div className="flex items-center gap-2 px-4 py-2.5 border-b border-zinc-200 bg-white/[0.02]">
            <div className="flex gap-1.5">
              <div className="w-2.5 h-2.5 rounded-full bg-red-500/40" />
              <div className="w-2.5 h-2.5 rounded-full bg-yellow-500/40" />
              <div className="w-2.5 h-2.5 rounded-full bg-green-500/40" />
            </div>
            <span className="text-[10px] text-zinc-600 ml-2 font-mono">terminal</span>
          </div>
          <div className="p-4 font-mono text-[11px] leading-5 text-zinc-600">
            <motion.div
              animate={{ opacity: [0.3, 0.7, 0.3] }}
              transition={{ duration: 3, repeat: Infinity }}
            >
              <p><span className="text-neon-cyan">$</span> sage deploy --env production</p>
              <p className="text-zinc-500">Building containers... <span className="text-green-500/70">done</span></p>
              <p className="text-zinc-500">Running security scan... <span className="text-green-500/70">passed</span></p>
              <p className="text-zinc-500">Deploying to 12 regions... <span className="text-neon-blue/70">in progress</span></p>
              <p className="text-zinc-500">Model accuracy: <span className="text-neon-violet/70">97.3%</span></p>
              <p className="text-zinc-500">Latency p99: <span className="text-neon-cyan/70">12ms</span></p>
              <p><span className="text-neon-cyan">$</span> <motion.span className="inline-block w-[6px] h-3 bg-neon-cyan/50" animate={{ opacity: [1, 0] }} transition={{ duration: 0.6, repeat: Infinity }} /></p>
            </motion.div>
          </div>
        </div>
      </motion.div>

      {/* Connecting lines (decorative) */}
      <svg className="absolute inset-0 w-full h-full pointer-events-none hidden lg:block" style={{ zIndex: 0 }}>
        <motion.line
          x1="40%" y1="50%" x2="50%" y2="50%"
          stroke="url(#lineGrad)"
          strokeWidth="1"
          strokeDasharray="6 4"
          initial={{ pathLength: 0, opacity: 0 }}
          animate={{ pathLength: 1, opacity: 0.3 }}
          transition={{ delay: 2, duration: 1.5 }}
        />
        <motion.line
          x1="50%" y1="50%" x2="65%" y2="70%"
          stroke="url(#lineGrad)"
          strokeWidth="1"
          strokeDasharray="6 4"
          initial={{ pathLength: 0, opacity: 0 }}
          animate={{ pathLength: 1, opacity: 0.2 }}
          transition={{ delay: 2.5, duration: 1.5 }}
        />
        <defs>
          <linearGradient id="lineGrad" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stopColor="#0F5132" stopOpacity="0.5" />
            <stop offset="100%" stopColor="#D4A017" stopOpacity="0.5" />
          </linearGradient>
        </defs>
      </svg>
    </div>
  );
}
