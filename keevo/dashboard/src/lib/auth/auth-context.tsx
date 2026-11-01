"use client";

import React, {
  createContext,
  useCallback,
  useContext,
  useState,
} from "react";

const STORAGE_KEY = "ks_admin_token";
const REFRESH_KEY = "ks_admin_refresh";
/** Story 16.11 — the super-admin session slides over 12 h (server side); the cookie follows. */
const SESSION_COOKIE_MAX_AGE = 12 * 3600;

// Module-level singletons so the axios interceptor can read them without React
let _token: string | null = null;
let _refreshToken: string | null = null;
export const getToken = () => _token;
export const getRefreshToken = () => {
  if (_refreshToken) return _refreshToken;
  try {
    _refreshToken = typeof window === "undefined" ? null : sessionStorage.getItem(REFRESH_KEY);
  } catch {
    _refreshToken = null;
  }
  return _refreshToken;
};

function setAuthCookie() {
  const secure = window.location.protocol === "https:" ? "; Secure" : "";
  document.cookie = `ks_admin_auth=1; path=/; max-age=${SESSION_COOKIE_MAX_AGE}; SameSite=Strict${secure}`;
}

/**
 * Story 16.11 — stores a new token pair (sign-in or silent renewal). The access token lives
 * 30 minutes; the refresh token renews it (12 h sliding for the super-admin).
 */
export function setSessionTokens(accessToken: string, refreshToken: string | null) {
  _token = accessToken;
  if (refreshToken) _refreshToken = refreshToken;
  try {
    sessionStorage.setItem(STORAGE_KEY, accessToken);
    if (refreshToken) sessionStorage.setItem(REFRESH_KEY, refreshToken);
  } catch {
    // storage blocked: the session lives in memory for this page only
  }
  setAuthCookie();
}

/**
 * Drops the in-memory token, its sessionStorage copy and the auth cookie read by the proxy.
 * Called on logout and by the axios interceptor on a 401 (expired or revoked token, or a
 * new tab that has the cookie but no stored token), so a dead session is not restored again
 * on the next refresh.
 */
export function clearSession() {
  _token = null;
  _refreshToken = null;
  try {
    sessionStorage.removeItem(STORAGE_KEY);
    sessionStorage.removeItem(REFRESH_KEY);
  } catch {
    // storage blocked: nothing persisted
  }
  document.cookie = `ks_admin_auth=; path=/; max-age=0`;
}

interface AuthContextValue {
  token: string | null;
  login: (token: string, refreshToken?: string | null) => void;
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

  const login = useCallback((newToken: string, refreshToken?: string | null) => {
    // Also sets the cookie the Next.js middleware checks.
    setSessionTokens(newToken, refreshToken ?? null);
    setToken(newToken);
  }, []);

  const logout = useCallback(() => {
    clearSession();
    setToken(null);
  }, []);

  return (
    <AuthContext.Provider value={{ token, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
