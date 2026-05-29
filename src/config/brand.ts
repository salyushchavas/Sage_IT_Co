/**
 * Sage IT Co brand constants — used by OnboardingLayout and any
 * future component that needs a single source of truth for the
 * brand name, logo URL, and legal copyright string.
 *
 * Backend mirrors these via BrandConfig.java (Sage-side env vars
 * are BRAND_* on Railway). Keep this file in sync with the
 * backend's brand defaults.
 */
export const BRAND = {
  name: "Sage IT Co",
  shortName: "Sage",
  legalName: "Sage IT Co",
  logoUrl: "/sage_logo.png",
  logoAlt: "Sage IT Co",
  copyrightYear: new Date().getFullYear(),
  primaryColor: "#1B2A5C",
  primaryColorDark: "#0F1F44",
  accentColor: "#C87D5C",
} as const;
