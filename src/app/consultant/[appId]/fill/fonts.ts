// Build S — typography stack scoped to /consultant/[appId]/fill.
//
// Fraunces  → section titles + display headings (premium contract feel).
// Newsreader → clause body (legal-grade serif, comfortable at 17px).
// Inter     → UI labels, buttons, captions, the progress + nav strip.
//
// Self-hosted with next/font/local: the woff2 files in ./fonts are the exact
// latin files next/font/google used to download (byte-identical), kept in the
// repo so the build no longer fetches from Google Fonts at build time — on
// 25 Sep 2026 Google served Vercel's build server a font URL that Next 14's
// loader couldn't parse, which failed every deploy. Same families, weights,
// display:swap, CSS variables and fallback fonts as before; licences (SIL OFL)
// are next to the files. The page wrapper attaches the variables as className,
// so only the wizard route pays the font-download cost.
//
// Each family's stack, as next/font/google produced it:
//   1. the basic-English file (preloaded, here in ./fonts), used only for
//      Google's "latin" unicode-range, exactly as before;
//   2. "Sage Wizard <Family> Ext": the same family's other-alphabet files
//      (globals.css, unicode-range, public/fonts/wizard);
//   3. a resized stand-in (globals.css) with next/font/google's exact
//      metrics, shown only while the font loads. Newsreader never had one.
// next/font requires every option to be a literal, so the range is repeated.

import localFont from "next/font/local";

export const fraunces = localFont({
  src: [{ path: "./fonts/fraunces-latin.woff2", weight: "500 700", style: "normal" }],
  display: "swap",
  declarations: [{
    prop: "unicode-range",
    value: "U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0304, U+0308, U+0329, U+2000-206F, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD",
  }],
  variable: "--font-fraunces",
  adjustFontFallback: false,
  fallback: ["Sage Wizard Fraunces Ext", "Sage Wizard Fraunces Fallback"],
});

export const newsreader = localFont({
  src: [{ path: "./fonts/newsreader-latin.woff2", weight: "400 500", style: "normal" }],
  display: "swap",
  declarations: [{
    prop: "unicode-range",
    value: "U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0304, U+0308, U+0329, U+2000-206F, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD",
  }],
  variable: "--font-newsreader",
  adjustFontFallback: false,
  fallback: ["Sage Wizard Newsreader Ext"],
});

export const inter = localFont({
  src: [{ path: "./fonts/inter-latin.woff2", weight: "400 700", style: "normal" }],
  display: "swap",
  declarations: [{
    prop: "unicode-range",
    value: "U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0304, U+0308, U+0329, U+2000-206F, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD",
  }],
  variable: "--font-inter",
  adjustFontFallback: false,
  fallback: ["Sage Wizard Inter Ext", "Sage Wizard Inter Fallback"],
});

export const wizardFontClass = [
  fraunces.variable,
  newsreader.variable,
  inter.variable,
].join(" ");
