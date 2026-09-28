"use client";

import {
  createContext,
  useContext,
  useEffect,
  useState,
  useCallback,
  type ReactNode,
} from "react";
import {
  login as apiLogin,
  register as apiRegister,
  logout as apiLogout,
  getProfile,
  refreshSession,
  type AuthResponse,
  type UserDTO,
} from "./api";
import { clearAccessTokenCookie, roleFromToken, setAccessTokenCookie } from "./roles";

// Frontend uses same shape as Spring Boot UserDTO (camelCase)
type AuthUser = UserDTO;

interface AuthContextValue {
  user: AuthUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<AuthUser>;
  register: (name: string, email: string, password: string) => Promise<void>;
  logout: () => void;
  // Stash a freshly-minted session (e.g. from the /enroll endpoint
  // that returns tokens alongside the new participant) without
  // having to re-login. Mirrors the storeTokens path used after a
  // normal login/register.
  setSession: (data: AuthResponse) => void;
  // Re-fetch the current user profile from the backend. Used by the
  // onboarding pages to pick up workflow-state changes (e.g.
  // acknowledgmentComplete flipping true after the page submits).
  refreshUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const storeTokens = (data: AuthResponse) => {
    localStorage.setItem("access_token", data.accessToken);
    localStorage.setItem("refresh_token", data.refreshToken);
    setAccessTokenCookie(data.accessToken);
    setUser(data.user);
  };

  const clearAuth = useCallback(() => {
    localStorage.removeItem("access_token");
    localStorage.removeItem("refresh_token");
    clearAccessTokenCookie();
    setUser(null);
  }, []);

  useEffect(() => {
    const init = async () => {
      const token = localStorage.getItem("access_token");
      if (!token) {
        setIsLoading(false);
        return;
      }
      try {
        // A server waking up (Railway cold start, a 502) or a dropped
        // connection must not sign anyone out: try a few times first.
        let profile: Awaited<ReturnType<typeof getProfile>> | null = null;
        for (let attempt = 0; profile === null; attempt++) {
          try {
            profile = await getProfile();
          } catch (e) {
            // A refused sign-in already ended the session (apiFetch
            // renews once, then clears the tokens): nothing to retry.
            if (attempt >= 2 || !localStorage.getItem("access_token")) throw e;
            await new Promise((r) => setTimeout(r, 1000 * (attempt + 1)));
          }
        }
        // A role changed since this token was issued: get a token with
        // the current role, so the route guard and the pages agree.
        const tokenRole = roleFromToken(localStorage.getItem("access_token"));
        if (tokenRole && profile.role && tokenRole !== profile.role.toUpperCase()) {
          await refreshSession();
        }
        const current = localStorage.getItem("access_token");
        if (current) setAccessTokenCookie(current);
        setUser(profile);
      } catch {
        // Tokens still here means the server couldn't be reached, not that
        // the sign-in is invalid: keep them so a reload picks up where the
        // user left off. (A refused sign-in has already removed them.)
        if (!localStorage.getItem("access_token")) clearAuth();
      } finally {
        setIsLoading(false);
      }
    };
    init();
  }, [clearAuth]);

  const login = useCallback(async (email: string, password: string) => {
    const data = await apiLogin({ email, password });
    storeTokens(data);
    return data.user;
  }, []);

  const register = useCallback(
    async (name: string, email: string, password: string) => {
      const data = await apiRegister({ fullName: name, email, password });
      storeTokens(data);
    },
    []
  );

  const logout = useCallback(() => {
    apiLogout();
    clearAuth();
    window.location.href = "/";
  }, [clearAuth]);

  const setSession = useCallback((data: AuthResponse) => {
    storeTokens(data);
  }, []);

  const refreshUser = useCallback(async () => {
    try {
      const profile = await getProfile();
      setUser(profile);
    } catch {
      // Swallow — refreshUser is a best-effort sync. Pages decide
      // whether to redirect on stale data themselves.
    }
  }, []);

  return (
    <AuthContext.Provider
      value={{
        user,
        isAuthenticated: !!user,
        isLoading,
        login,
        register,
        logout,
        setSession,
        refreshUser,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
