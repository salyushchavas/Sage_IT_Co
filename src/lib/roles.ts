/**
 * Where each role lands after signing in, and the role inside a sign-in
 * token. Shared by the route guard (middleware.ts, which can't import
 * api.ts) and the pages, so they always agree — a page that sends a role
 * somewhere the guard refuses is what made System Admin and Instructor
 * sign-ins loop.
 *
 *   ERM                        -> /erm-dashboard
 *   MANAGER / ACCOUNTS         -> /approver-dashboard
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
  if (r === "MANAGER" || r === "ACCOUNTS") return "/approver-dashboard";
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

/**
 * The website agreement's two approval gates (the console's MANAGER and
 * ACCOUNTS users): Manager signs off in Phase 1 and Phase 2, Accounts in
 * Phase 2 only. They are the only roles /approver-dashboard lets in; a
 * System Admin reaches the approver API with ?role= but has no screen
 * there, like the console's super-admin.
 */
export const APPROVER_ROLES: readonly ("MANAGER" | "ACCOUNTS")[] = ["MANAGER", "ACCOUNTS"];

export function canOpenApproverDashboard(role: string | null | undefined): boolean {
  return (APPROVER_ROLES as readonly string[]).includes((role ?? "").toUpperCase());
}

/** The gate's name on screen ("Manager" / "Accounts"), as the console says it. */
export const APPROVER_ROLE_LABEL: Record<"MANAGER" | "ACCOUNTS", string> = {
  MANAGER: "Manager",
  ACCOUNTS: "Accounts",
};

/**
 * Role word for an approval gate. Anything that isn't ACCOUNTS reads
 * "Manager", the same fallback as the console's roleWord.
 */
export function approverRoleLabel(role: string | null | undefined): string {
  return (role ?? "").toUpperCase() === "ACCOUNTS" ? APPROVER_ROLE_LABEL.ACCOUNTS : APPROVER_ROLE_LABEL.MANAGER;
}

/** The claims inside a sign-in token (JWT payload, base64url), or null. */
function tokenPayload(token: string | null | undefined): Record<string, unknown> | null {
  if (!token) return null;
  try {
    const part = token.split(".")[1] ?? "";
    const b64 = part.replace(/-/g, "+").replace(/_/g, "/");
    const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
    return JSON.parse(atob(padded)) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** The role claim inside a sign-in token, or null. */
export function roleFromToken(token: string | null | undefined): string | null {
  const role = tokenPayload(token)?.role;
  return typeof role === "string" ? role.toUpperCase() : null;
}

/** When the sign-in token stops working (ms since epoch), or null if unknown. */
export function tokenExpiresAt(token: string | null | undefined): number | null {
  const exp = tokenPayload(token)?.exp;
  return typeof exp === "number" ? exp * 1000 : null;
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
