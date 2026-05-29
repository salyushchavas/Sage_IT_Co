"use client";

import { usePathname } from "next/navigation";
import { type ReactNode } from "react";
import Navbar from "./Navbar";
import Footer from "./Footer";
import PageTransition from "./PageTransition";

/**
 * Routes that render their own full-viewport chrome and must NOT
 * be wrapped in the marketing-site Navbar/Footer. The (auth) route
 * group has its own <html>/<body> layout so /login + /signup are
 * already excluded automatically — only the onboarding routes
 * (which live at the app root) need this opt-out.
 */
const FULLSCREEN_PREFIXES = [
  "/login",
  "/signup",
  "/forgot-password",
  "/reset-password",
  "/enroll",
  "/verify-email",
  "/participant-id",
  "/acknowledgment",
  "/document-upload",
  "/program-selection",
  "/agreement",
  "/check-upload",
  "/welcome",
  "/dashboard",
  "/erm-dashboard",
  "/coach-dashboard",
  "/finance-dashboard",
  "/operations",
] as const;

export default function ChromeWrapper({ children }: { children: ReactNode }) {
  const pathname = usePathname() ?? "";
  const isFullscreen = FULLSCREEN_PREFIXES.some(
    (prefix) => pathname === prefix || pathname.startsWith(prefix + "/"),
  );

  if (isFullscreen) {
    return <>{children}</>;
  }

  return (
    <>
      <Navbar />
      <main className="min-h-screen">
        <PageTransition>{children}</PageTransition>
      </main>
      <Footer />
    </>
  );
}
