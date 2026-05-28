"use client";

import { navLinks, learnLinks } from "@/lib/data";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth-context";
import { AnimatePresence, motion } from "framer-motion";
import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState, useRef } from "react";
import GlowButton from "../ui/GlowButton";

export default function Navbar() {
  const [scrolled, setScrolled] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [learnOpen, setLearnOpen] = useState(false);
  const pathname = usePathname();
  const { user, isAuthenticated, isLoading, logout } = useAuth();
  const menuRef = useRef<HTMLDivElement>(null);
  const learnRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleScroll = () => setScrolled(window.scrollY > 20);
    window.addEventListener("scroll", handleScroll, { passive: true });
    return () => window.removeEventListener("scroll", handleScroll);
  }, []);

  useEffect(() => {
    setMobileOpen(false);
    setUserMenuOpen(false);
  }, [pathname]);

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setUserMenuOpen(false);
      }
      if (learnRef.current && !learnRef.current.contains(e.target as Node)) {
        setLearnOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  useEffect(() => {
    setLearnOpen(false);
  }, [pathname]);

  const allLinks = [...navLinks, ...learnLinks];
  const isAdmin = user?.role?.toUpperCase() === "ADMIN";
  const learnActive = learnLinks.some((l) => pathname === l.href);

  return (
    <motion.nav
      className={cn(
        "fixed top-0 left-0 right-0 z-50 transition-all duration-300",
        scrolled
          ? "bg-white/80 backdrop-blur-xl border-b border-zinc-200 shadow-glass"
          : "bg-transparent"
      )}
      initial={{ y: -100 }}
      animate={{ y: 0 }}
      transition={{ duration: 0.6 }}
    >
      <div className="max-w-7xl mx-auto px-4 sm:px-6 h-16 sm:h-20 flex items-center justify-between">
        {/* Logo */}
        <Link href="/" className="flex items-center gap-2 group">
          <div className="logo-glow relative w-10 h-10 sm:w-12 sm:h-12 transition-transform duration-300 group-hover:scale-110">
            <Image
              src="/sage_logo.png"
              alt="Sage IT Co"
              fill
              priority
              className="object-contain drop-shadow-[0_0_15px_rgba(27,42,92,0.3)] group-hover:drop-shadow-[0_0_20px_rgba(200,125,92,0.4)]"
            />
          </div>
        </Link>

        {/* Desktop links — sliding active pill + animated underline */}
        <div className="hidden lg:flex items-center gap-1">
          {navLinks.map((link) => {
            const active = pathname === link.href;
            return (
              <Link
                key={link.href}
                href={link.href}
                className="relative group px-4 py-2 text-sm font-medium transition-colors duration-300"
              >
                {active && (
                  <motion.span
                    layoutId="nav-pill"
                    className="absolute inset-0 rounded-lg bg-gradient-to-br from-slate-900/10 to-orange-300/10 border border-slate-900/15"
                    transition={{ type: "spring", stiffness: 380, damping: 30 }}
                  />
                )}
                <span className={cn("relative z-10", active ? "text-slate-900" : "text-zinc-600 group-hover:text-zinc-900")}>
                  {link.label}
                </span>
                <span
                  className={cn(
                    "absolute left-4 right-4 -bottom-0.5 h-px origin-center scale-x-0 bg-gradient-to-r from-transparent via-orange-300 to-transparent transition-transform duration-300",
                    !active && "group-hover:scale-x-100"
                  )}
                />
              </Link>
            );
          })}

          {/* Learn dropdown */}
          <div className="relative" ref={learnRef}>
            <button
              onClick={() => setLearnOpen((v) => !v)}
              className="relative group px-4 py-2 text-sm font-medium transition-colors duration-300 flex items-center gap-1"
            >
              {learnActive && (
                <motion.span
                  layoutId="nav-pill"
                  className="absolute inset-0 rounded-lg bg-gradient-to-br from-slate-900/10 to-orange-300/10 border border-slate-900/15"
                  transition={{ type: "spring", stiffness: 380, damping: 30 }}
                />
              )}
              <span className={cn("relative z-10", learnActive ? "text-slate-900" : "text-zinc-600 group-hover:text-zinc-900")}>
                Learn
              </span>
              <motion.svg
                width="10" height="10" viewBox="0 0 10 10"
                className={cn("relative z-10", learnActive ? "text-slate-900" : "text-zinc-500")}
                animate={{ rotate: learnOpen ? 180 : 0 }}
                transition={{ duration: 0.25 }}
              >
                <path d="M2 3.5 L5 6.5 L8 3.5" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinecap="round" strokeLinejoin="round" />
              </motion.svg>
            </button>
            <AnimatePresence>
              {learnOpen && (
                <motion.div
                  className="absolute left-0 top-full mt-2 w-72 glass rounded-2xl p-2 shadow-xl border border-slate-900/10"
                  initial={{ opacity: 0, y: -8, scale: 0.96 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, y: -8, scale: 0.96 }}
                  transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }}
                >
                  {learnLinks.map((l, i) => (
                    <motion.div
                      key={l.href}
                      initial={{ opacity: 0, x: -8 }}
                      animate={{ opacity: 1, x: 0 }}
                      transition={{ delay: 0.05 + i * 0.05 }}
                    >
                      <Link
                        href={l.href}
                        onClick={() => setLearnOpen(false)}
                        className="block px-3 py-2.5 rounded-xl hover:bg-white/70 transition-colors group"
                      >
                        <div className="flex items-baseline justify-between gap-2">
                          <span className="text-sm font-semibold text-zinc-900 group-hover:text-slate-900 transition-colors">
                            {l.label}
                          </span>
                          <span className="text-orange-400 opacity-0 group-hover:opacity-100 transition-opacity text-xs">→</span>
                        </div>
                        <p className="text-xs text-zinc-500 mt-0.5">{l.desc}</p>
                      </Link>
                    </motion.div>
                  ))}
                </motion.div>
              )}
            </AnimatePresence>
          </div>

          {isAuthenticated && (
            <Link
              href="/dashboard"
              className="relative group px-4 py-2 text-sm font-medium transition-colors duration-300"
            >
              {pathname === "/dashboard" && (
                <motion.span
                  layoutId="nav-pill"
                  className="absolute inset-0 rounded-lg bg-gradient-to-br from-slate-900/10 to-orange-300/10 border border-slate-900/15"
                  transition={{ type: "spring", stiffness: 380, damping: 30 }}
                />
              )}
              <span className={cn("relative z-10", pathname === "/dashboard" ? "text-slate-900" : "text-zinc-600 group-hover:text-zinc-900")}>
                Dashboard
              </span>
            </Link>
          )}
          {isAdmin && (
            <Link
              href="/admin"
              className="relative group px-4 py-2 text-sm font-medium transition-colors duration-300"
            >
              {pathname === "/admin" && (
                <motion.span
                  layoutId="nav-pill"
                  className="absolute inset-0 rounded-lg bg-gradient-to-br from-slate-900/10 to-orange-300/10 border border-slate-900/15"
                  transition={{ type: "spring", stiffness: 380, damping: 30 }}
                />
              )}
              <span className={cn("relative z-10", pathname === "/admin" ? "text-slate-900" : "text-zinc-600 group-hover:text-zinc-900")}>
                Admin
              </span>
            </Link>
          )}
        </div>

        {/* Desktop CTA / Auth */}
        <div className="hidden lg:flex items-center gap-3">
          {isLoading ? null : isAuthenticated ? (
            <div className="relative" ref={menuRef}>
              <button
                onClick={() => setUserMenuOpen(!userMenuOpen)}
                className="flex items-center gap-2 px-3 py-2 rounded-xl hover:bg-white/60 transition-colors"
              >
                <div className="w-8 h-8 rounded-full bg-gradient-to-br from-neon-blue to-neon-violet flex items-center justify-center text-white text-xs font-bold">
                  {user?.fullName?.charAt(0)?.toUpperCase() || "U"}
                </div>
                <span className="text-sm text-zinc-500 max-w-[120px] truncate">{user?.fullName}</span>
              </button>
              <AnimatePresence>
                {userMenuOpen && (
                  <motion.div
                    className="absolute right-0 top-full mt-2 w-48 glass rounded-xl p-2 shadow-lg"
                    initial={{ opacity: 0, y: -8 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, y: -8 }}
                  >
                    <Link href="/dashboard" className="block px-3 py-2 rounded-lg text-sm text-zinc-500 hover:text-zinc-900 hover:bg-white/80 transition-colors">
                      Dashboard
                    </Link>
                    {isAdmin && (
                      <Link href="/admin" className="block px-3 py-2 rounded-lg text-sm text-zinc-500 hover:text-zinc-900 hover:bg-white/80 transition-colors">
                        Admin Panel
                      </Link>
                    )}
                    <hr className="my-1 border-zinc-200" />
                    <button
                      onClick={logout}
                      className="w-full text-left px-3 py-2 rounded-lg text-sm text-red-400 hover:text-red-300 hover:bg-white/80 transition-colors"
                    >
                      Sign Out
                    </button>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          ) : (
            <>
              <Link href="/login" className="text-sm text-zinc-600 hover:text-zinc-900 transition-colors px-4 py-2">
                Sign In
              </Link>
              <GlowButton href="/signup">Get Started</GlowButton>
            </>
          )}
        </div>

        {/* Mobile hamburger */}
        <button
          className="lg:hidden relative w-10 h-10 flex items-center justify-center"
          onClick={() => setMobileOpen(!mobileOpen)}
          aria-label="Toggle menu"
        >
          <div className="space-y-1.5">
            <span className={cn("block w-6 h-0.5 bg-zinc-900 transition-all duration-300", mobileOpen && "rotate-45 translate-y-2")} />
            <span className={cn("block w-6 h-0.5 bg-zinc-900 transition-all duration-300", mobileOpen && "opacity-0")} />
            <span className={cn("block w-6 h-0.5 bg-zinc-900 transition-all duration-300", mobileOpen && "-rotate-45 -translate-y-2")} />
          </div>
        </button>
      </div>

      {/* Mobile menu */}
      <AnimatePresence>
        {mobileOpen && (
          <motion.div
            className="lg:hidden bg-white/95 backdrop-blur-xl border-b border-zinc-200"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.3 }}
          >
            <div className="px-4 sm:px-6 py-4 space-y-2 max-h-[calc(100vh-4rem)] overflow-y-auto">
              {allLinks.map((link) => (
                <Link
                  key={link.href}
                  href={link.href}
                  className={cn(
                    "block px-4 py-3 rounded-lg text-sm font-medium transition-colors",
                    pathname === link.href
                      ? "text-neon-blue bg-neon-blue/10"
                      : "text-zinc-600 hover:text-zinc-900 hover:bg-white/60"
                  )}
                >
                  {link.label}
                </Link>
              ))}
              {isAuthenticated && (
                <Link href="/dashboard" className="block px-4 py-3 rounded-lg text-sm font-medium text-zinc-600 hover:text-zinc-900 hover:bg-white/60">
                  Dashboard
                </Link>
              )}
              {isAdmin && (
                <Link href="/admin" className="block px-4 py-3 rounded-lg text-sm font-medium text-zinc-600 hover:text-zinc-900 hover:bg-white/60">
                  Admin
                </Link>
              )}
              <div className="pt-4 space-y-2">
                {isAuthenticated ? (
                  <button onClick={logout} className="w-full px-4 py-3 rounded-lg text-sm font-medium text-red-400 hover:bg-white/60 text-left">
                    Sign Out ({user?.fullName})
                  </button>
                ) : (
                  <>
                    <Link href="/login" className="block px-4 py-3 rounded-lg text-sm font-medium text-zinc-600 hover:text-zinc-900 hover:bg-white/60">
                      Sign In
                    </Link>
                    <GlowButton href="/signup" className="w-full">
                      Get Started
                    </GlowButton>
                  </>
                )}
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.nav>
  );
}
