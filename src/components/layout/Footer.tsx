import { navLinks, socialLinks } from "@/lib/data";
import Link from "next/link";

export default function Footer() {
  return (
    <footer className="relative border-t border-white/10 bg-background">
      <div className="max-w-7xl mx-auto px-6 py-16">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-12">
          {/* Brand */}
          <div>
            <Link href="/" className="flex items-center gap-3 mb-4">
              <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-neon-blue to-neon-violet flex items-center justify-center font-bold text-white text-lg">
                S
              </div>
              <span className="text-xl font-bold text-white">
                Sage<span className="text-gradient"> IT</span>
              </span>
            </Link>
            <p className="text-zinc-400 text-sm leading-relaxed">
              Engineering Intelligence. Empowering Growth. We deliver next-gen technology solutions for enterprises worldwide.
            </p>
          </div>

          {/* Quick Links */}
          <div>
            <h4 className="text-white font-semibold mb-4">Quick Links</h4>
            <ul className="space-y-2">
              {navLinks.map((link) => (
                <li key={link.href}>
                  <Link href={link.href} className="text-zinc-400 hover:text-neon-blue transition-colors text-sm">
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* Services */}
          <div>
            <h4 className="text-white font-semibold mb-4">Services</h4>
            <ul className="space-y-2 text-sm text-zinc-400">
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
            <h4 className="text-white font-semibold mb-4">Contact</h4>
            <ul className="space-y-2 text-sm text-zinc-400">
              <li>hello@sageit.com</li>
              <li>+1 (555) 123-4567</li>
              <li>Hyderabad, India</li>
            </ul>
            <div className="flex gap-3 mt-6">
              {socialLinks.map((s) => (
                <a
                  key={s.label}
                  href={s.href}
                  className="w-10 h-10 rounded-lg bg-white/5 border border-white/10 flex items-center justify-center text-zinc-400 hover:text-neon-blue hover:border-neon-blue/30 transition-all"
                  aria-label={s.label}
                >
                  {s.icon[0].toUpperCase()}
                </a>
              ))}
            </div>
          </div>
        </div>

        <div className="mt-16 pt-8 border-t border-white/10 flex flex-col md:flex-row items-center justify-between gap-4">
          <p className="text-zinc-500 text-sm">
            &copy; {new Date().getFullYear()} Sage IT. All rights reserved.
          </p>
          <div className="flex gap-6 text-sm text-zinc-500">
            <span>Privacy Policy</span>
            <span>Terms of Service</span>
          </div>
        </div>
      </div>
    </footer>
  );
}
