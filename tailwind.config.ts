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
        background: "#050510",
        foreground: "#e4e4e7",
        // Brand palette (sage green + warm gold from logo)
        "sage-green": "#22C55E",
        "sage-green-deep": "#14653F",
        "sage-gold": "#E5B62E",
        "sage-gold-light": "#FBBF24",
        // Legacy "neon-*" tokens remapped to brand palette so existing
        // references throughout the codebase keep working without edits.
        "neon-blue": "#22C55E",     // was cyan blue → now sage green
        "neon-violet": "#E5B62E",   // was violet → now warm gold
        "neon-cyan": "#14653F",     // was cyan → now deep forest green
        "neon-purple": "#FBBF24",   // was purple → now light gold
        "dark-card": "#0a1a0f",
      },
      boxShadow: {
        "glow-blue": "0 0 20px rgba(34, 197, 94, 0.35), 0 0 60px rgba(34, 197, 94, 0.12)",
        "glow-violet": "0 0 20px rgba(229, 182, 46, 0.35), 0 0 60px rgba(229, 182, 46, 0.12)",
        "glow-cyan": "0 0 20px rgba(20, 101, 63, 0.35), 0 0 60px rgba(20, 101, 63, 0.12)",
        "glow-green": "0 0 20px rgba(34, 197, 94, 0.35), 0 0 60px rgba(34, 197, 94, 0.12)",
        "glow-gold": "0 0 20px rgba(229, 182, 46, 0.35), 0 0 60px rgba(229, 182, 46, 0.12)",
        glass: "0 8px 32px rgba(0, 0, 0, 0.3)",
      },
      backgroundImage: {
        "gradient-radial": "radial-gradient(var(--tw-gradient-stops))",
        "gradient-neon": "linear-gradient(135deg, #22C55E, #E5B62E, #14653F)",
        "gradient-sage": "linear-gradient(135deg, #22C55E, #E5B62E, #14653F)",
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
