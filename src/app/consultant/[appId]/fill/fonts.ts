// Build S — typography stack scoped to /consultant/[appId]/fill.
//
// Fraunces  → section titles + display headings (premium contract feel).
// Newsreader → clause body (legal-grade serif, comfortable at 17px).
// Inter     → UI labels, buttons, captions, the progress + nav strip.
//
// Loaded via next/font/google with display:swap and variables that
// the page wrapper attaches as className so only the wizard route
// pays the font-download cost.

import { Fraunces, Inter, Newsreader } from "next/font/google";

export const fraunces = Fraunces({
  subsets: ["latin"],
  weight: ["500", "600", "700"],
  display: "swap",
  variable: "--font-fraunces",
});

export const newsreader = Newsreader({
  subsets: ["latin"],
  weight: ["400", "500"],
  display: "swap",
  variable: "--font-newsreader",
});

export const inter = Inter({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  display: "swap",
  variable: "--font-inter",
});

export const wizardFontClass = [
  fraunces.variable,
  newsreader.variable,
  inter.variable,
].join(" ");
