import axios from "axios";
import { clearSession, getRefreshToken, getToken, setSessionTokens } from "@/lib/auth/auth-context";

/**
 * Story 16.11 — silent renewal of the dashboard session.
 *
 * The access token lives 30 minutes and proves identity only; the super-admin's refresh token
 * slides over 12 h. The client renews the access token shortly before it expires and on a
 * 401 TOKEN_EXPIRED, then replays the request. A refused renewal ends the session.
 */

/** Renew this long before expiry, so a request does not expire in flight. */
export const RENEWAL_MARGIN_MS = 60_000;

const refreshClient = axios.create({
  baseURL: "/api/backend",
  headers: { "Content-Type": "application/json" },
});

/** Expiry of a JWT (`exp`, ms), or null when unreadable. Only the expiry is read. */
export function tokenExpiry(token: string | null): number | null {
  if (!token) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const json = atob(parts[1].replace(/-/g, "+").replace(/_/g, "/"));
    const exp = (JSON.parse(json) as { exp?: unknown }).exp;
    return typeof exp === "number" ? exp * 1000 : null;
  } catch {
    return null;
  }
}

export function expiresSoon(token: string | null, now = Date.now()): boolean {
  const exp = tokenExpiry(token);
  return exp !== null && exp - now <= RENEWAL_MARGIN_MS;
}

export type RenewOutcome = "renewed" | "rejected" | "unreachable";

let inFlight: Promise<RenewOutcome> | null = null;

/** Single-flight: refresh tokens are single-use, two parallel renewals would look like a replay. */
export function renewSession(): Promise<RenewOutcome> {
  if (!inFlight) {
    inFlight = doRenew().finally(() => {
      inFlight = null;
    });
  }
  return inFlight;
}

async function doRenew(): Promise<RenewOutcome> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return "rejected";
  try {
    const res = await refreshClient.post<{ accessToken?: string; refreshToken?: string }>(
      "/auth/refresh",
      { refreshToken, deviceLabel: deviceLabel() },
    );
    const { accessToken, refreshToken: next } = res.data ?? {};
    if (!accessToken || !next) return "unreachable";
    setSessionTokens(accessToken, next);
    return "renewed";
  } catch (err: unknown) {
    const status = axios.isAxiosError(err) ? err.response?.status : undefined;
    if (status === 400 || status === 401 || status === 403) {
      clearSession();
      return "rejected";
    }
    return "unreachable";
  }
}

/** Readable label of this browser for « Appareils connectés » (no identifier). */
export function deviceLabel(): string {
  if (typeof navigator === "undefined") return "Navigateur";
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox"
    : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Navigateur";
  const os = /Windows/.test(ua) ? "Windows" : /Mac OS X/.test(ua) ? "macOS"
    : /Android/.test(ua) ? "Android" : /Linux/.test(ua) ? "Linux" : /iPhone|iPad/.test(ua) ? "iOS" : "";
  return os ? `${browser} · ${os}` : browser;
}

/** The current access token, renewed first when it is about to expire. */
export async function freshToken(): Promise<string | null> {
  const token = getToken();
  if (token && expiresSoon(token) && getRefreshToken()) {
    await renewSession();
    return getToken();
  }
  return token;
}
