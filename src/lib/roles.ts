/**
 * Where each role lands after signing in, and the role inside a sign-in
 * token. Shared by the route guard (middleware.ts, which can't import
 * api.ts) and the pages, so they always agree — a page that sends a role
 * somewhere the guard refuses is what made System Admin and Instructor
 * sign-ins loop.
 *
 *   ERM                        -> /erm-dashboard
 *   COACH / TECHNICAL_ADVISOR  -> /coach-dashboard
 *   FINANCE                    -> /finance-dashboard
 *   OPERATIONS_ADMIN           -> /operations
 *   SYSTEM_ADMIN / ADMIN       -> /admin (and /operations from there)
 *   INSTRUCTOR                 -> /instructor
 *   anything else / participant -> /dashboard
 */
export function homeForRole(role: string | null | undefined): string {
  const r = (role ?? "").toUpperCase();
  if (r === "ERM") return "/erm-dashboard";
  if (r === "COACH" || r === "TECHNICAL_ADVISOR") return "/coach-dashboard";
  if (r === "FINANCE") return "/finance-dashboard";
  if (r === "OPERATIONS_ADMIN") return "/operations";
  if (r === "SYSTEM_ADMIN" || r === "ADMIN") return "/admin";
  if (r === "INSTRUCTOR") return "/instructor";
  return "/dashboard";
}

/** Roles allowed into /admin. */
export const ADMIN_PAGE_ROLES: readonly string[] = ["ADMIN", "SYSTEM_ADMIN"];

export function canOpenAdminPages(role: string | null | undefined): boolean {
  return ADMIN_PAGE_ROLES.includes((role ?? "").toUpperCase());
}

/** The role claim inside a sign-in token (JWT payload, base64url), or null. */
export function roleFromToken(token: string | null | undefined): string | null {
  if (!token) return null;
  try {
    const part = token.split(".")[1] ?? "";
    const b64 = part.replace(/-/g, "+").replace(/_/g, "/");
    const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
    const payload = JSON.parse(atob(padded)) as { role?: unknown };
    return typeof payload.role === "string" ? payload.role.toUpperCase() : null;
  } catch {
    return null;
  }
}

/**
 * The route guard reads the sign-in token from this cookie. It must
 * follow every new token (sign-in and each refresh), or a changed role
 * stays stuck in it for the cookie's 7 days.
 */
export function setAccessTokenCookie(token: string, days = 7): void {
  if (typeof document === "undefined") return;
  const expires = new Date(Date.now() + days * 864e5).toUTCString();
  const secure = typeof location !== "undefined" && location.protocol === "https:" ? "; Secure" : "";
  document.cookie = `access_token=${encodeURIComponent(token)}; expires=${expires}; path=/; SameSite=Lax${secure}`;
}

export function clearAccessTokenCookie(): void {
  if (typeof document === "undefined") return;
  document.cookie = "access_token=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/;";
}
