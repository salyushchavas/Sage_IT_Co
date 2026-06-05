/**
 * Consultant-side token storage. We deliberately use sessionStorage
 * (not localStorage) so a closed tab clears the bearer token even
 * within the 1h JWT TTL. The token is also scoped per applicationId
 * so two open tabs for different applications don't collide.
 */

const tokenKey = (appId: string) => `sage_consultant_token_${appId}`;
const emailKey = (appId: string) => `sage_consultant_email_${appId}`;

export function saveConsultantSession(
  appId: string,
  token: string,
  email?: string,
) {
  if (typeof window === "undefined") return;
  try {
    sessionStorage.setItem(tokenKey(appId), token);
    if (email) sessionStorage.setItem(emailKey(appId), email);
  } catch {
    /* storage disabled */
  }
}

export function getConsultantToken(appId: string): string | null {
  if (typeof window === "undefined") return null;
  try {
    return sessionStorage.getItem(tokenKey(appId));
  } catch {
    return null;
  }
}

export function getConsultantEmail(appId: string): string | null {
  if (typeof window === "undefined") return null;
  try {
    return sessionStorage.getItem(emailKey(appId));
  } catch {
    return null;
  }
}

export function clearConsultantSession(appId: string) {
  if (typeof window === "undefined") return;
  try {
    sessionStorage.removeItem(tokenKey(appId));
    sessionStorage.removeItem(emailKey(appId));
  } catch {
    /* storage disabled */
  }
}
