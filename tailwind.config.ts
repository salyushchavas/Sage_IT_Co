import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        background: "#FAFAF7",
        foreground: "#0a0a0a",
        // Brand palette — Sage IT Co logo (navy + copper rose-gold)
        "sage-navy": "#1B2A5C",
        "sage-navy-deep": "#0F1F44",
        "sage-navy-light": "#2D4480",
        "sage-copper": "#C87D5C",
        "sage-copper-light": "#E8A78D",
        "sage-copper-deep": "#A55E40",
        // Legacy aliases — old token names map to new palette so existing
        // references throughout the codebase keep working without edits.
        "sage-green": "#1B2A5C",
        "sage-green-deep": "#0F1F44",
        "sage-green-light": "#2D4480",
        "sage-gold": "#C87D5C",
        "sage-gold-light": "#E8A78D",
        "neon-blue": "#1B2A5C",
        "neon-violet": "#C87D5C",
        "neon-cyan": "#0F1F44",
        "neon-purple": "#E8A78D",
        "dark-card": "#FFFFFF",
      },
      boxShadow: {
        "glow-blue": "0 0 24px rgba(27, 42, 92, 0.18), 0 0 60px rgba(27, 42, 92, 0.08)",
        "glow-violet": "0 0 24px rgba(200, 125, 92, 0.18), 0 0 60px rgba(200, 125, 92, 0.08)",
        "glow-cyan": "0 0 24px rgba(15, 31, 68, 0.18), 0 0 60px rgba(15, 31, 68, 0.08)",
        "glow-green": "0 0 24px rgba(27, 42, 92, 0.18), 0 0 60px rgba(27, 42, 92, 0.08)",
        "glow-gold": "0 0 24px rgba(200, 125, 92, 0.18), 0 0 60px rgba(200, 125, 92, 0.08)",
        glass: "0 8px 32px rgba(27, 42, 92, 0.08)",
        soft: "0 4px 24px rgba(0, 0, 0, 0.06)",
      },
      backgroundImage: {
        "gradient-radial": "radial-gradient(var(--tw-gradient-stops))",
        "gradient-neon": "linear-gradient(135deg, #1B2A5C, #C87D5C)",
        "gradient-sage": "linear-gradient(135deg, #1B2A5C, #C87D5C)",
      },
      animation: {
        "pulse-glow": "pulse-glow 2s ease-in-out infinite",
        float: "float 6s ease-in-out infinite",
        marquee: "marquee 30s linear infinite",
        "spin-slow": "spin 20s linear infinite",
        aurora: "aurora 18s ease-in-out infinite",
        shimmer: "shimmer 2.4s linear infinite",
        "gradient-shift": "gradient-shift 8s ease-in-out infinite",
        drift: "drift 14s ease-in-out infinite",
        "drift-slow": "drift 22s ease-in-out infinite",
        sheen: "sheen 1.4s ease-out forwards",
        "underline-grow": "underline-grow 0.9s cubic-bezier(0.22,1,0.36,1) forwards",
        "blob-1": "blob 12s ease-in-out infinite",
        "blob-2": "blob 16s ease-in-out infinite reverse",
        bob: "bob 5s ease-in-out infinite",
      },
      keyframes: {
        "pulse-glow": {
          "0%, 100%": { opacity: "0.4" },
          "50%": { opacity: "1" },
        },
        float: {
          "0%, 100%": { transform: "translateY(0px)" },
          "50%": { transform: "translateY(-20px)" },
        },
        marquee: {
          "0%": { transform: "translateX(0%)" },
          "100%": { transform: "translateX(-50%)" },
        },
        aurora: {
          "0%, 100%": { transform: "translate3d(-10%, -10%, 0) rotate(0deg) scale(1)", opacity: "0.55" },
          "33%": { transform: "translate3d(8%, -4%, 0) rotate(70deg) scale(1.18)", opacity: "0.85" },
          "66%": { transform: "translate3d(-4%, 6%, 0) rotate(140deg) scale(0.92)", opacity: "0.7" },
        },
        shimmer: {
          "0%": { backgroundPosition: "-200% 50%" },
          "100%": { backgroundPosition: "200% 50%" },
        },
        "gradient-shift": {
          "0%, 100%": { backgroundPosition: "0% 50%" },
          "50%": { backgroundPosition: "100% 50%" },
        },
        drift: {
          "0%, 100%": { transform: "translate3d(0,0,0)" },
          "50%": { transform: "translate3d(20px,-30px,0)" },
        },
        sheen: {
          "0%": { transform: "translateX(-120%) skewX(-20deg)" },
          "100%": { transform: "translateX(220%) skewX(-20deg)" },
        },
        "underline-grow": {
          "0%": { transform: "scaleX(0)", transformOrigin: "left center" },
          "100%": { transform: "scaleX(1)", transformOrigin: "left center" },
        },
        blob: {
          "0%, 100%": { borderRadius: "44% 56% 60% 40% / 48% 38% 62% 52%", transform: "translate3d(0,0,0) scale(1)" },
          "33%": { borderRadius: "60% 40% 36% 64% / 32% 60% 40% 68%", transform: "translate3d(20px,-30px,0) scale(1.06)" },
          "66%": { borderRadius: "32% 68% 56% 44% / 64% 38% 62% 36%", transform: "translate3d(-18px,16px,0) scale(0.96)" },
        },
        bob: {
          "0%, 100%": { transform: "translateY(0)" },
          "50%": { transform: "translateY(-6px)" },
        },
      },
    },
  },
  plugins: [],
};
export default config;
