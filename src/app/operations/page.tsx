"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { LayoutDashboard, Loader2, LogOut, Menu, ShieldCheck, X } from "lucide-react";

import { useAuth } from "@/lib/auth-context";
import { canOpenAdminPages } from "@/lib/roles";
import { loginHere } from "@/lib/api";
import { OperationsPanel } from "@/components/admin/OperationsPanel";

// /operations -- Operations Admin dashboard. Separate from /admin (LMS
// admin: courses, sessions, revenue, instructor approvals). Gated to
// OPERATIONS_ADMIN / SYSTEM_ADMIN; legacy ADMIN goes to /admin, everyone
// else routes via dashboardRouteForRole.
export default function OperationsPage() {
  const router = useRouter();
  const { user, isLoading, logout } = useAuth();
  // Phones: the sidebar opens as a drawer from the top bar.
  const [drawerOpen, setDrawerOpen] = useState(false);

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.replace(loginHere());
      return;
    }
    const role = (user.role ?? "").toUpperCase();
    if (role !== "OPERATIONS_ADMIN" && role !== "SYSTEM_ADMIN") {
      import("@/lib/api").then(({ dashboardRouteForRole }) => {
        router.replace(dashboardRouteForRole(role));
      });
    }
  }, [isLoading, user, router]);

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  const role = (user?.role ?? "").toUpperCase();
  if (role !== "OPERATIONS_ADMIN" && role !== "SYSTEM_ADMIN") {
    return null;
  }

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
              Operations
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
        <button
          type="button"
          onClick={() => setDrawerOpen(false)}
          className="px-3 py-2 inline-flex items-center gap-2 text-sm font-medium rounded-lg bg-sage-navy text-white shadow-sm w-full cursor-pointer"
        >
          <ShieldCheck size={14} />
          <span>Operations</span>
        </button>
        {canOpenAdminPages(role) && (
          <Link
            href="/admin"
            className="w-full inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-gray-600 hover:bg-gray-100 hover:text-gray-900 cursor-pointer"
          >
            <LayoutDashboard size={14} />
            <span className="truncate">LMS Admin</span>
          </Link>
        )}
      </nav>
      <div className="p-3 border-t border-gray-100 flex items-center justify-between gap-2">
        <div className="min-w-0">
          <p className="text-[11px] font-semibold text-gray-700 truncate">
            {user?.fullName ?? ""}
          </p>
          <p className="text-[10px] text-gray-400 truncate">
            {user?.role ?? ""}
          </p>
        </div>
        <div className="shrink-0 flex flex-col items-end gap-0.5">
          <Link href="/change-password" className="text-[11px] text-gray-500 hover:text-sage-navy">
            Change password
          </Link>
          <button
            type="button"
            onClick={logout}
            className="text-xs text-gray-500 hover:text-red-700 cursor-pointer inline-flex items-center gap-1"
          >
            <LogOut size={12} /> Sign out
          </button>
        </div>
      </div>
    </>
  );

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
            <span className="text-sm font-semibold">Operations</span>
          </button>
          <span className="text-[10px] uppercase tracking-wider font-semibold text-gray-400">
            Sage IT Co
          </span>
        </div>
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-6">
          <div className="mb-5">
            <h1 className="text-2xl font-bold text-gray-900">
              Operations
            </h1>
            <p className="text-sm text-gray-500 mt-0.5">
              Participant-lifecycle queues -- enrollment, document review,
              agreement signing, ERM &amp; coach assignments, audit trail,
              and exceptions.
            </p>
          </div>
          <OperationsPanel />
        </div>
      </main>
    </div>
  );
}
