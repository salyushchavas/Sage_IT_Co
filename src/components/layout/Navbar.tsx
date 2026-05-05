"use client";

import { navLinks } from "@/lib/data";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth-context";
import { AnimatePresence, motion } from "framer-motion";
import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState, useRef } from "react";
import GlowButton from "../ui/GlowButton";

const lmsLinks = [
  { label: "Courses", href: "/courses" },
  { label: "Categories", href: "/categories" },
  { label: "Pricing", href: "/pricing" },
];

export default function Navbar() {
  const [scrolled, setScrolled] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const pathname = usePathname();
  const { user, isAuthenticated, isLoading, logout } = useAuth();
  const menuRef = useRef<HTMLDivElement>(null);

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
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  const allLinks = [...navLinks, ...lmsLinks];
  const isAdmin = user?.role?.toUpperCase() === "ADMIN";

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
      <div className="max-w-7xl mx-auto px-6 h-20 flex items-center justify-between">
        {/* Logo */}
        <Link href="/" className="flex items-center gap-2 group">
          <div className="logo-glow relative w-12 h-12 transition-transform duration-300 group-hover:scale-110">
            <Image
              src="/sage_logo.png"
              alt="Sage IT Co"
              fill
              priority
              className="object-contain drop-shadow-[0_0_15px_rgba(15,81,50,0.3)] group-hover:drop-shadow-[0_0_20px_rgba(212,160,23,0.4)]"
            />
          </div>
        </Link>

        {/* Desktop links */}
        <div className="hidden lg:flex items-center gap-1">
          {allLinks.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              className={cn(
                "px-4 py-2 rounded-lg text-sm font-medium transition-all duration-300",
                pathname === link.href
                  ? "text-neon-blue bg-neon-blue/10"
                  : "text-zinc-600 hover:text-zinc-900 hover:bg-white/60"
              )}
            >
              {link.label}
            </Link>
          ))}
          {isAuthenticated && (
            <Link
              href="/dashboard"
              className={cn(
                "px-4 py-2 rounded-lg text-sm font-medium transition-all duration-300",
                pathname === "/dashboard"
                  ? "text-neon-blue bg-neon-blue/10"
                  : "text-zinc-600 hover:text-zinc-900 hover:bg-white/60"
              )}
            >
              Dashboard
            </Link>
          )}
          {isAdmin && (
            <Link
              href="/admin"
              className={cn(
                "px-4 py-2 rounded-lg text-sm font-medium transition-all duration-300",
                pathname === "/admin"
                  ? "text-neon-violet bg-neon-violet/10"
                  : "text-zinc-600 hover:text-zinc-900 hover:bg-white/60"
              )}
            >
              Admin
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
            <div className="px-6 py-4 space-y-2">
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
