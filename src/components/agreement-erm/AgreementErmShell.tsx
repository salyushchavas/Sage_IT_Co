"use client";

import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { LogOut, ShieldCheck, type LucideIcon } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";

import { clearAgreementErmToken, fetchMe } from "@/lib/api";

interface Props {
  /** Optional toolbar items rendered on the right of the header. */
  toolbar?: ReactNode;
  /** Page title rendered below the header. */
  title?: string;
  /** Page subtitle below the title. */
  subtitle?: string;
  /** Page icon next to the title. */
  Icon?: LucideIcon;
  children: ReactNode;
}

/**
 * Chrome for the hidden Sage Agreements console. Deliberately a
 * one-page surface — no sidebar tabs to navigate, just the operator's
 * working area. Branding stays Sage navy + copper so the look matches
 * the rest of the platform, but the wordmark reads "Sage Agreements"
 * to make the surface distinct from the participant ERM dashboard.
 *
 * Multi-user: on mount it calls /me and shows an "Admin" link in the
 * header ONLY when the signed-in user is the SUPER_ADMIN. ERMs never
 * see it. Both roles otherwise share this exact chrome.
 */
export default function AgreementErmShell({
  toolbar,
  title,
  subtitle,
  Icon,
  children,
}: Props) {
  const router = useRouter();
  const [isSuperAdmin, setIsSuperAdmin] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchMe()
      .then((me) => {
        if (!cancelled) setIsSuperAdmin(me.role === "SUPER_ADMIN");
      })
      .catch(() => {
        // Non-fatal: a dead session is handled by each page's auth
        // gate; the Admin link simply stays hidden.
        if (!cancelled) setIsSuperAdmin(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSignOut = () => {
    clearAgreementErmToken();
    router.replace("/agreements/login");
  };

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <header className="bg-white border-b border-gray-100 sticky top-0 z-30">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 h-14 flex items-center gap-3">
          <Link href="/agreements" className="inline-flex items-center gap-2">
            <Image
              src="/sage_logo.png"
              alt="Sage IT Co"
              width={28}
              height={28}
              priority
              className="rounded-md object-contain"
            />
            <span className="text-sm font-bold tracking-tight text-sage-navy">
              Sage Agreements
            </span>
          </Link>
          <div className="ml-auto flex items-center gap-3">
            {isSuperAdmin && (
              <Link
                href="/agreements/admin"
                className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-bold border border-sage-navy/20 bg-sage-navy/5 hover:bg-sage-navy/10 text-sage-navy cursor-pointer"
              >
                <ShieldCheck size={11} /> Admin
              </Link>
            )}
            {toolbar}
            <button
              type="button"
              onClick={handleSignOut}
              className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-bold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer"
            >
              <LogOut size={11} /> Sign out
            </button>
          </div>
        </div>
      </header>

      <main className="flex-1 px-4 sm:px-6 py-6 sm:py-8">
        <div className="max-w-6xl mx-auto">
          {(title || subtitle || Icon) && (
            <div className="mb-5 flex items-start gap-3">
              {Icon && (
                <div className="mt-1 inline-flex items-center justify-center w-9 h-9 rounded-lg bg-sage-navy/10 text-sage-navy">
                  <Icon size={18} />
                </div>
              )}
              <div>
                {title && (
                  <h1 className="text-2xl font-bold text-gray-900 leading-tight">
                    {title}
                  </h1>
                )}
                {subtitle && (
                  <p className="text-sm text-gray-500 mt-0.5">{subtitle}</p>
                )}
              </div>
            </div>
          )}
          {children}
        </div>
      </main>

      <footer className="py-6 text-center text-[11px] text-gray-400">
        Sage IT Co
      </footer>
    </div>
  );
}
