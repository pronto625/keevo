"use client";

import React, {
  createContext,
  useCallback,
  useContext,
  useState,
} from "react";

const STORAGE_KEY = "ks_admin_token";

// Module-level singleton so axios interceptor can read it without React
let _token: string | null = null;
export const getToken = () => _token;

interface AuthContextValue {
  token: string | null;
  login: (token: string) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue>({
  token: null,
  login: () => {},
  logout: () => {},
});

export function AuthProvider({ children }: { children: React.ReactNode }) {
  // Restore token from sessionStorage on first client render (page refresh).
  // Lazy initializer + synchronous `_token` assignment: the axios interceptor
  // sees the token before any child query fires, and no setState-in-effect.
  const [token, setToken] = useState<string | null>(() => {
    if (typeof window === "undefined") return null;
    let stored: string | null = null;
    try {
      stored = sessionStorage.getItem(STORAGE_KEY);
    } catch {
      return null; // storage blocked (privacy mode / SecurityError)
    }
    if (stored) _token = stored;
    return stored;
  });

  const login = useCallback((newToken: string) => {
    _token = newToken;
    sessionStorage.setItem(STORAGE_KEY, newToken);
    // Set a short-lived cookie so Next.js middleware can check auth
    const secure = window.location.protocol === "https:" ? "; Secure" : "";
    document.cookie = `ks_admin_auth=1; path=/; max-age=86400; SameSite=Strict${secure}`;
    setToken(newToken);
  }, []);

  const logout = useCallback(() => {
    _token = null;
    sessionStorage.removeItem(STORAGE_KEY);
    document.cookie = `ks_admin_auth=; path=/; max-age=0`;
    setToken(null);
  }, []);

  return (
    <AuthContext.Provider value={{ token, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
