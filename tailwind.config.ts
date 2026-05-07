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
