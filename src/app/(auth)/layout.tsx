import type { ReactNode } from "react";

/**
 * Pass-through layout for the (auth) route group. The root layout
 * owns <html>/<body> and AuthProvider; each (auth) page is free to
 * paint its own background and chrome (split-screen for /login,
 * dark glass for /signup).
 */
export default function AuthLayout({ children }: { children: ReactNode }) {
  return <>{children}</>;
}
