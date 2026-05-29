"use client";

import { useState, type ComponentType, type ReactNode } from "react";
import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  Briefcase,
  ClipboardList,
  CreditCard,
  FileSignature,
  FileText,
  FolderOpen,
  Heart,
  LayoutDashboard,
  Loader2,
  type LucideIcon,
  Menu,
  MessageCircle,
  MessageSquare,
  ScrollText,
  Users,
  UserCircle2,
  X,
  LogOut,
} from "lucide-react";
import { useAuth } from "@/lib/auth-context";

interface SidebarTab {
  id: string;
  label: string;
  Icon: LucideIcon | ComponentType<{ size?: number; className?: string }>;
}

const SIDEBAR_TABS: ReadonlyArray<SidebarTab> = [
  { id: "overview",         label: "Overview",         Icon: LayoutDashboard },
  { id: "complete-profile", label: "Complete Profile", Icon: ClipboardList },
  { id: "courses",          label: "My Courses",       Icon: ScrollText },
  { id: "wishlist",         label: "My Wishlist",      Icon: Heart },
  { id: "weekly-report",    label: "Weekly Report",    Icon: FileText },
  { id: "resume",           label: "Resume",           Icon: FileSignature },
  { id: "interviews",       label: "Interviews",       Icon: MessageSquare },
  { id: "employment",       label: "Employment",       Icon: Briefcase },
  { id: "payments",         label: "Payments",         Icon: CreditCard },
  { id: "documents",        label: "Documents",        Icon: FolderOpen },
  { id: "agreement",        label: "Agreement",        Icon: ScrollText },
  { id: "team",             label: "My Team",          Icon: Users },
  { id: "messages",         label: "Messages",         Icon: MessageCircle },
  { id: "profile",          label: "Profile",          Icon: UserCircle2 },
];

interface DashboardLayoutProps {
  activeTab: string;
  children: ReactNode;
  /** Optional badge to render next to a tab (e.g. completion %). */
  badges?: Record<string, ReactNode>;
}

/**
 * Participant dashboard chrome -- sidebar on the left, top header
 * with user identity on the right, content area in the middle.
 *
 * Tab content is the caller's responsibility; this component just
 * renders the shell and toggles tab styling based on the activeTab
 * prop. Tab switching is driven by ?tab= query param so it round-
 * trips through Next.js routing without remount.
 */
export default function DashboardLayout({
  activeTab,
  children,
  badges,
}: DashboardLayoutProps) {
  const router = useRouter();
  const { user, logout, isLoading } = useAuth();
  const [mobileOpen, setMobileOpen] = useState(false);

  const handleSignOut = () => {
    logout();
    // logout() already redirects to "/"; router.replace here is a
    // belt-and-suspenders no-op if logout's redirect happens.
    router.replace("/");
  };

  return (
    <div className="min-h-screen bg-gray-50 flex">
      {/* Mobile overlay backdrop */}
      {mobileOpen && (
        <button
          type="button"
          className="md:hidden fixed inset-0 z-30 bg-black/30 backdrop-blur-sm"
          onClick={() => setMobileOpen(false)}
          aria-label="Close sidebar"
        />
      )}

      {/* Sidebar */}
      <aside
        className={
          "fixed md:sticky top-0 z-40 h-screen w-64 shrink-0 bg-white border-r border-gray-200 flex flex-col transition-transform duration-200 " +
          (mobileOpen ? "translate-x-0" : "-translate-x-full md:translate-x-0")
        }
      >
        {/* Sidebar header: logo */}
        <div className="px-5 py-5 border-b border-gray-100 flex items-center justify-between">
          <Link href="/" className="flex items-center gap-2.5 group" aria-label="Sage IT Co home">
            <Image
              src="/sage_logo.png"
              alt="Sage IT Co"
              width={32}
              height={32}
              priority
              className="rounded-md object-contain transition-transform group-hover:scale-105"
            />
            <span className="text-base font-bold text-sage-navy tracking-tight">
              Sage IT Co
            </span>
          </Link>
          <button
            type="button"
            className="md:hidden text-gray-500 hover:text-gray-700"
            onClick={() => setMobileOpen(false)}
            aria-label="Close sidebar"
          >
            <X size={18} />
          </button>
        </div>

        {/* Sidebar tabs */}
        <nav className="flex-1 overflow-y-auto px-3 py-4 space-y-1">
          {SIDEBAR_TABS.map((tab) => {
            const active = tab.id === activeTab;
            const Icon = tab.Icon;
            return (
              <Link
                key={tab.id}
                href={`/dashboard?tab=${tab.id}`}
                onClick={() => setMobileOpen(false)}
                className={
                  "flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition group " +
                  (active
                    ? "bg-sage-navy text-white shadow-sm"
                    : "text-gray-700 hover:bg-sage-navy/10 hover:text-sage-navy")
                }
                aria-current={active ? "page" : undefined}
              >
                <Icon
                  size={17}
                  className={active ? "text-white" : "text-gray-500 group-hover:text-sage-navy"}
                />
                <span className="flex-1">{tab.label}</span>
                {badges?.[tab.id] && (
                  <span className="text-xs">{badges[tab.id]}</span>
                )}
              </Link>
            );
          })}
        </nav>

        {/* Sidebar footer: user info + sign out */}
        <div className="border-t border-gray-100 px-3 py-3 space-y-2">
          {isLoading ? (
            <div className="flex items-center justify-center py-2">
              <Loader2 size={16} className="animate-spin text-gray-400" />
            </div>
          ) : user ? (
            <div className="px-2 py-2 rounded-lg bg-sage-navy/5">
              <p className="text-sm font-semibold text-sage-navy truncate" title={user.fullName ?? user.email}>
                {user.fullName ?? user.email}
              </p>
              {user.participantId && (
                <p className="text-[11px] font-mono text-gray-500 mt-0.5 truncate">
                  {user.participantId}
                </p>
              )}
            </div>
          ) : null}
          <button
            type="button"
            onClick={handleSignOut}
            className="w-full inline-flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-semibold text-gray-700 hover:bg-red-50 hover:text-red-700 transition"
          >
            <LogOut size={15} /> Sign out
          </button>
        </div>
      </aside>

      {/* Main column */}
      <div className="flex-1 min-w-0 flex flex-col">
        {/* Mobile top bar (hamburger + logo) */}
        <header className="md:hidden sticky top-0 z-20 bg-white border-b border-gray-200 px-4 py-3 flex items-center justify-between">
          <button
            type="button"
            className="text-gray-700 hover:text-sage-navy"
            onClick={() => setMobileOpen(true)}
            aria-label="Open sidebar"
          >
            <Menu size={22} />
          </button>
          <Link href="/" className="inline-flex items-center gap-2">
            <Image src="/sage_logo.png" alt="Sage IT Co" width={26} height={26} className="object-contain" />
            <span className="text-sm font-bold text-sage-navy">Sage IT Co</span>
          </Link>
          <div className="w-6" /> {/* spacer */}
        </header>

        {/* Scrolling content */}
        <main className="flex-1 min-w-0">{children}</main>
      </div>
    </div>
  );
}
