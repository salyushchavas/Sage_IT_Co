"use client";

import { ReactNode, useEffect, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { Menu, X, LucideIcon } from "lucide-react";

import { useAuth } from "@/lib/auth-context";

/**
 * Shared sidebar shell for role-scoped dashboards (ERM, Coach,
 * Finance, Operations). Same desktop / mobile pattern as the
 * participant DashboardLayout: persistent sidebar on md+, hamburger
 * + slide-over drawer below md.
 *
 * Pages own their own tab state -- the shell takes the active tab id,
 * the tab list, and a setter, then renders the children inside the
 * main column.
 */

export interface RoleDashboardTab {
  id: string;
  label: string;
  Icon: LucideIcon;
}

export function RoleDashboardShell({
  title,
  tabs,
  active,
  onSelect,
  children,
}: {
  title: string;
  tabs: ReadonlyArray<RoleDashboardTab>;
  active: string;
  onSelect: (id: string) => void;
  children: ReactNode;
}) {
  const { user, logout } = useAuth();
  const [drawerOpen, setDrawerOpen] = useState(false);
  useEffect(() => {
    setDrawerOpen(false);
  }, [active]);

  const SidebarBody = (
    <>
      <div className="px-4 py-4 border-b border-gray-100 flex items-center justify-between gap-2">
        <Link href="/" className="inline-flex items-center gap-2 min-w-0">
          <Image
            src="/sage_logo.png"
            alt="Sage IT Co"
            width={28}
            height={28}
            className="h-7 w-7 object-contain rounded"
          />
          <div className="min-w-0">
            <p className="text-sm font-bold text-sage-navy truncate">
              Sage IT Co
            </p>
            <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-400 truncate">
              {title}
            </p>
          </div>
        </Link>
        <button
          type="button"
          onClick={() => setDrawerOpen(false)}
          className="md:hidden text-gray-400 hover:text-gray-700 cursor-pointer"
          aria-label="Close menu"
        >
          <X size={16} />
        </button>
      </div>
      <nav className="flex-1 px-2 py-3 space-y-0.5 overflow-y-auto">
        {tabs.map((t) => {
          const isActive = active === t.id;
          return (
            <button
              key={t.id}
              onClick={() => onSelect(t.id)}
              className={
                "w-full inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium transition cursor-pointer " +
                (isActive
                  ? "bg-sage-navy text-white shadow-sm"
                  : "text-gray-600 hover:bg-gray-100 hover:text-gray-900")
              }
            >
              <t.Icon size={14} />
              <span className="truncate">{t.label}</span>
            </button>
          );
        })}
      </nav>
      <div className="p-3 border-t border-gray-100 flex items-center justify-between gap-2">
        <div className="min-w-0">
          <p className="text-[11px] font-semibold text-gray-700 truncate">
            {user?.fullName ?? user?.email ?? ""}
          </p>
          <p className="text-[10px] text-gray-400 truncate">
            {user?.role ?? ""}
          </p>
        </div>
        <button
          type="button"
          onClick={logout}
          className="shrink-0 text-xs text-gray-500 hover:text-red-700 cursor-pointer"
        >
          Sign out
        </button>
      </div>
    </>
  );

  const activeLabel = tabs.find((t) => t.id === active)?.label ?? title;

  return (
    <div className="min-h-screen bg-gray-50 flex">
      <aside className="hidden md:flex w-56 shrink-0 bg-white border-r border-gray-200 flex-col">
        {SidebarBody}
      </aside>
      {drawerOpen && (
        <div className="md:hidden fixed inset-0 z-40">
          <div
            className="absolute inset-0 bg-black/40"
            onClick={() => setDrawerOpen(false)}
            aria-hidden="true"
          />
          <aside className="absolute left-0 top-0 bottom-0 w-64 bg-white shadow-xl flex flex-col">
            {SidebarBody}
          </aside>
        </div>
      )}
      <main className="flex-1 overflow-y-auto min-w-0">
        <div className="md:hidden sticky top-0 z-30 flex items-center justify-between px-4 py-2.5 bg-white border-b border-gray-100">
          <button
            type="button"
            onClick={() => setDrawerOpen(true)}
            className="inline-flex items-center gap-1.5 text-gray-700 hover:text-sage-navy cursor-pointer"
            aria-label="Open menu"
          >
            <Menu size={18} />
            <span className="text-sm font-semibold">{activeLabel}</span>
          </button>
          <span className="text-[10px] uppercase tracking-wider font-semibold text-gray-400">
            {title}
          </span>
        </div>
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-6">{children}</div>
      </main>
    </div>
  );
}
