import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { canOpenAdminPages, homeForRole, roleFromToken } from "@/lib/roles";

const PROTECTED_PATHS = ["/dashboard", "/admin"];
const ADMIN_PATHS = ["/admin"];

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;

  const isProtected = PROTECTED_PATHS.some((p) => pathname.startsWith(p));
  if (!isProtected) return NextResponse.next();

  const token =
    request.cookies.get("access_token")?.value ||
    request.headers.get("authorization")?.replace("Bearer ", "");

  if (!token) {
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("redirect", pathname);
    return NextResponse.redirect(loginUrl);
  }

  const isAdmin = ADMIN_PATHS.some((p) => pathname.startsWith(p));
  if (isAdmin) {
    const role = roleFromToken(token);
    if (!role) {
      return NextResponse.redirect(new URL("/login", request.url));
    }
    // System Admin and Admin get in; anyone else goes to their own home
    // page (never /admin, so this can't loop).
    if (!canOpenAdminPages(role)) {
      return NextResponse.redirect(new URL(homeForRole(role), request.url));
    }
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/dashboard/:path*", "/admin/:path*"],
};
