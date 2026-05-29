"use client";

import { useEffect } from "react";
import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { LayoutDashboard, Loader2, LogOut, ShieldCheck } from "lucide-react";

import { useAuth } from "@/lib/auth-context";
import { OperationsPanel } from "@/components/admin/OperationsPanel";

// /operations -- Operations Admin dashboard. Separate from /admin (LMS
// admin: courses, sessions, revenue, instructor approvals). Gated to
// OPERATIONS_ADMIN / SYSTEM_ADMIN; legacy ADMIN goes to /admin, everyone
// else routes via dashboardRouteForRole.
export default function OperationsPage() {
  const router = useRouter();
  const { user, isLoading, logout } = useAuth();

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.replace("/login?redirect=/operations");
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

  return (
    <div className="min-h-screen bg-gray-50 flex">
      <aside className="hidden md:flex w-56 shrink-0 bg-white border-r border-gray-200 flex-col">
        <div className="px-4 py-4 border-b border-gray-100">
          <Link href="/" className="inline-flex items-center gap-2">
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
        </div>
        <nav className="flex-1 px-2 py-3 space-y-0.5 overflow-y-auto">
          <div className="px-3 py-2 inline-flex items-center gap-2 text-sm font-medium rounded-lg bg-sage-navy text-white shadow-sm w-full">
            <ShieldCheck size={14} />
            <span>Operations</span>
          </div>
          <Link
            href="/admin"
            className="w-full inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-gray-600 hover:bg-gray-100 hover:text-gray-900 cursor-pointer"
          >
            <LayoutDashboard size={14} />
            <span className="truncate">LMS Admin</span>
          </Link>
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
          <button
            type="button"
            onClick={logout}
            className="shrink-0 text-xs text-gray-500 hover:text-red-700 cursor-pointer inline-flex items-center gap-1"
          >
            <LogOut size={12} /> Sign out
          </button>
        </div>
      </aside>

      <main className="flex-1 overflow-y-auto min-w-0">
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
