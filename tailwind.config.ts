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
        // Brand palette (sage green + warm gold from logo)
        "sage-green": "#22C55E",
        "sage-green-deep": "#14653F",
        "sage-gold": "#E5B62E",
        "sage-gold-light": "#FBBF24",
        // Legacy "neon-*" tokens remapped to brand palette so existing
        // references throughout the codebase keep working without edits.
        "neon-blue": "#22C55E",
        "neon-violet": "#E5B62E",
        "neon-cyan": "#14653F",
        "neon-purple": "#FBBF24",
        "dark-card": "#FFFFFF",
      },
      boxShadow: {
        "glow-blue": "0 0 24px rgba(34, 197, 94, 0.18), 0 0 60px rgba(34, 197, 94, 0.08)",
        "glow-violet": "0 0 24px rgba(229, 182, 46, 0.18), 0 0 60px rgba(229, 182, 46, 0.08)",
        "glow-cyan": "0 0 24px rgba(20, 101, 63, 0.18), 0 0 60px rgba(20, 101, 63, 0.08)",
        "glow-green": "0 0 24px rgba(34, 197, 94, 0.18), 0 0 60px rgba(34, 197, 94, 0.08)",
        "glow-gold": "0 0 24px rgba(229, 182, 46, 0.18), 0 0 60px rgba(229, 182, 46, 0.08)",
        glass: "0 8px 32px rgba(20, 101, 63, 0.08)",
        soft: "0 4px 24px rgba(0, 0, 0, 0.06)",
      },
      backgroundImage: {
        "gradient-radial": "radial-gradient(var(--tw-gradient-stops))",
        "gradient-neon": "linear-gradient(135deg, #22C55E, #E5B62E)",
        "gradient-sage": "linear-gradient(135deg, #22C55E, #E5B62E)",
      },
      animation: {
        "pulse-glow": "pulse-glow 2s ease-in-out infinite",
        float: "float 6s ease-in-out infinite",
        marquee: "marquee 30s linear infinite",
        "spin-slow": "spin 20s linear infinite",
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
      },
    },
  },
  plugins: [],
};
export default config;
