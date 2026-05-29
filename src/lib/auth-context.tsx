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
  type AuthResponse,
  type UserDTO,
} from "./api";

// Frontend uses same shape as Spring Boot UserDTO (camelCase)
type AuthUser = UserDTO;

interface AuthContextValue {
  user: AuthUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
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

function setCookie(name: string, value: string, days: number) {
  const expires = new Date(Date.now() + days * 864e5).toUTCString();
  document.cookie = `${name}=${encodeURIComponent(value)}; expires=${expires}; path=/; SameSite=Lax`;
}

function deleteCookie(name: string) {
  document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/;`;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const storeTokens = (data: AuthResponse) => {
    localStorage.setItem("access_token", data.accessToken);
    localStorage.setItem("refresh_token", data.refreshToken);
    setCookie("access_token", data.accessToken, 7);
    setUser(data.user);
  };

  const clearAuth = useCallback(() => {
    localStorage.removeItem("access_token");
    localStorage.removeItem("refresh_token");
    deleteCookie("access_token");
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
        const profile = await getProfile();
        setUser(profile);
      } catch {
        clearAuth();
      } finally {
        setIsLoading(false);
      }
    };
    init();
  }, [clearAuth]);

  const login = useCallback(async (email: string, password: string) => {
    const data = await apiLogin({ email, password });
    storeTokens(data);
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
