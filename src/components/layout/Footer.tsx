import { navLinks, socialLinks } from "@/lib/data";
import Image from "next/image";
import Link from "next/link";

export default function Footer() {
  return (
    <footer className="relative border-t border-zinc-200 bg-background">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 py-12 sm:py-16">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-8 sm:gap-10 lg:gap-12">
          {/* Brand */}
          <div className="sm:col-span-2 lg:col-span-1">
            <Link href="/" className="inline-block mb-3 sm:mb-4">
              <div className="logo-glow relative w-24 h-24 sm:w-28 sm:h-28 lg:w-32 lg:h-32">
                <Image
                  src="/sage_logo.png"
                  alt="Sage IT Co"
                  fill
                  className="object-contain drop-shadow-[0_0_20px_rgba(27,42,92,0.25)]"
                />
              </div>
            </Link>
            <p className="text-zinc-600 text-sm leading-relaxed max-w-md">
              Engineering Intelligence. Empowering Growth. We deliver next-gen technology solutions for enterprises worldwide.
            </p>
          </div>

          {/* Quick Links */}
          <div>
            <h4 className="text-zinc-900 font-semibold mb-4">Quick Links</h4>
            <ul className="space-y-2">
              {navLinks.map((link) => (
                <li key={link.href}>
                  <Link href={link.href} className="text-zinc-600 hover:text-neon-blue transition-colors text-sm">
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* Services */}
          <div>
            <h4 className="text-zinc-900 font-semibold mb-4">Services</h4>
            <ul className="space-y-2 text-sm text-zinc-600">
              <li>Cloud Solutions</li>
              <li>Cybersecurity</li>
              <li>Web Development</li>
              <li>AI Solutions</li>
              <li>Digital Marketing</li>
              <li>Data & Analytics</li>
            </ul>
          </div>

          {/* Contact */}
          <div>
            <h4 className="text-zinc-900 font-semibold mb-4">Contact</h4>
            <ul className="space-y-2 text-sm text-zinc-600">
              <li>info@sageitco.com</li>
              <li>+1 (555) 123-4567</li>
              <li>Hyderabad, India</li>
            </ul>
            <div className="flex gap-3 mt-6">
              {socialLinks.map((s) => (
                <a
                  key={s.label}
                  href={s.href}
                  className="w-10 h-10 rounded-lg bg-white/60 border border-zinc-200 flex items-center justify-center text-zinc-600 hover:text-neon-blue hover:border-neon-blue/30 transition-all"
                  aria-label={s.label}
                >
                  {s.icon[0].toUpperCase()}
                </a>
              ))}
            </div>
          </div>
        </div>

        <div className="mt-12 sm:mt-16 pt-6 sm:pt-8 border-t border-zinc-200 flex flex-col md:flex-row items-center justify-between gap-3 sm:gap-4 text-center md:text-left">
          <p className="text-zinc-500 text-xs sm:text-sm">
            &copy; {new Date().getFullYear()} Sage IT. All rights reserved.
          </p>
          <div className="flex gap-4 sm:gap-6 text-xs sm:text-sm text-zinc-500">
            <span>Privacy Policy</span>
            <span>Terms of Service</span>
          </div>
        </div>
      </div>
    </footer>
  );
}
