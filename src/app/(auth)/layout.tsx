import type { ReactNode } from "react";
import { AuthProvider } from "@/lib/auth-context";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" className="dark">
      <body className="antialiased bg-[#050510] text-zinc-900 min-h-screen relative overflow-hidden">
        {/* Background grid pattern */}
        <div
          className="fixed inset-0 pointer-events-none z-0"
          style={{
            backgroundImage:
              "linear-gradient(rgba(255,255,255,0.03) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.03) 1px, transparent 1px)",
            backgroundSize: "60px 60px",
          }}
        />
        {/* Ambient glow */}
        <div className="fixed top-1/4 left-1/2 -translate-x-1/2 w-[600px] h-[600px] bg-[#C87D5C]/10 rounded-full blur-[150px] pointer-events-none z-0" />

        <AuthProvider>
          <div className="relative z-10 flex items-center justify-center min-h-screen px-4 py-12">
            {children}
          </div>
        </AuthProvider>
      </body>
    </html>
  );
}
