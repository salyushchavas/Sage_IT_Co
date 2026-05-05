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
        // Brand palette — exact colors from Sage IT logo
        "sage-green": "#0F5132",        // deep forest green (S-mark, Sage Co text)
        "sage-green-deep": "#0A3D26",   // even deeper for borders/accents
        "sage-green-light": "#1A7A4E",  // slightly brighter for hover/highlights
        "sage-gold": "#D4A017",         // mustard gold (S-mark, IT text)
        "sage-gold-light": "#E8B82E",   // brighter gold for hover
        // Legacy "neon-*" tokens remapped to brand palette so existing
        // references throughout the codebase keep working without edits.
        "neon-blue": "#0F5132",
        "neon-violet": "#D4A017",
        "neon-cyan": "#0A3D26",
        "neon-purple": "#E8B82E",
        "dark-card": "#FFFFFF",
      },
      boxShadow: {
        "glow-blue": "0 0 24px rgba(15, 81, 50, 0.18), 0 0 60px rgba(15, 81, 50, 0.08)",
        "glow-violet": "0 0 24px rgba(212, 160, 23, 0.18), 0 0 60px rgba(212, 160, 23, 0.08)",
        "glow-cyan": "0 0 24px rgba(10, 61, 38, 0.18), 0 0 60px rgba(10, 61, 38, 0.08)",
        "glow-green": "0 0 24px rgba(15, 81, 50, 0.18), 0 0 60px rgba(15, 81, 50, 0.08)",
        "glow-gold": "0 0 24px rgba(212, 160, 23, 0.18), 0 0 60px rgba(212, 160, 23, 0.08)",
        glass: "0 8px 32px rgba(15, 81, 50, 0.08)",
        soft: "0 4px 24px rgba(0, 0, 0, 0.06)",
      },
      backgroundImage: {
        "gradient-radial": "radial-gradient(var(--tw-gradient-stops))",
        "gradient-neon": "linear-gradient(135deg, #0F5132, #D4A017)",
        "gradient-sage": "linear-gradient(135deg, #0F5132, #D4A017)",
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
