"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";

import { useAuth } from "@/lib/auth-context";
import { dashboardRouteForRole } from "@/lib/api";

/**
 * /profile is no longer a standalone page. Every role has a Profile
 * tab inside its own dashboard (the participant dashboard's Profile
 * tab, the ERM dashboard's Profile tab, etc.). This stub just sends
 * the user to the correct dashboard — they can click the Profile
 * tab from the sidebar there.
 */
export default function ProfileRedirectPage() {
  const router = useRouter();
  const { user, isLoading, isAuthenticated } = useAuth();

  useEffect(() => {
    if (isLoading) return;
    if (!isAuthenticated || !user) {
      router.replace("/login");
      return;
    }
    router.replace(dashboardRouteForRole(user.role));
  }, [isLoading, isAuthenticated, user, router]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <Loader2 size={28} className="animate-spin text-sage-navy" />
    </div>
  );
}
