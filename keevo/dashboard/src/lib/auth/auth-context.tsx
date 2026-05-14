"use client";

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
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
  const [token, setToken] = useState<string | null>(null);

  // Restore token from sessionStorage on mount (page refresh)
  useEffect(() => {
    const stored = sessionStorage.getItem(STORAGE_KEY);
    if (stored) {
      _token = stored;
      setToken(stored);
    }
  }, []);

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
