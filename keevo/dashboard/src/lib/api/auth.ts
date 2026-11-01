import axios, { isAxiosError } from "axios";
import type { LoginResponse, LoginSessionResponse, UserProfile } from "@/types/auth";
import { deviceLabel } from "@/lib/auth/session";

export const INVALID_CREDENTIALS_MESSAGE = "Numéro ou mot de passe incorrect";
export const ACCESS_DENIED_MESSAGE = "Accès refusé — compte super admin requis.";
export const SERVER_UNREACHABLE_MESSAGE = "Serveur injoignable. Vérifiez votre connexion.";
export const LOGIN_FAILED_MESSAGE = "Connexion impossible pour le moment. Réessayez.";

/**
 * French message for a failed sign-in. Axios' own text (« Request failed with status code 500 »)
 * is technical English and is never shown.
 */
export function loginErrorMessage(err: unknown): string {
  if (isAxiosError(err)) {
    const status = err.response?.status;
    const domainCode = (err.response?.data as { domainCode?: unknown } | undefined)?.domainCode;
    if (status === 401 || domainCode === "INVALID_CREDENTIALS") return INVALID_CREDENTIALS_MESSAGE;
    if (status === 403) return ACCESS_DENIED_MESSAGE;
    if (!err.response) return SERVER_UNREACHABLE_MESSAGE;
    return LOGIN_FAILED_MESSAGE;
  }
  // Thrown by login() itself when the account has no super-admin membership.
  if (err instanceof Error && err.message === ACCESS_DENIED_MESSAGE) return ACCESS_DENIED_MESSAGE;
  return LOGIN_FAILED_MESSAGE;
}

// Separate axios instance for auth (no auth header needed)
const authClient = axios.create({
  baseURL: "/api/backend",
  headers: { "Content-Type": "application/json" },
});

export async function login(
  phoneNumber: string,
  password: string
): Promise<LoginResponse> {
  const step1 = await authClient.post<LoginSessionResponse>(
    "/auth/login",
    { phoneNumber, password }
  );
  const { loginToken, memberships } = step1.data;

  const superAdminMembership = memberships.find(
    (m) => m.role === "SUPER_ADMIN" && m.tenantCode === "KV-ADMIN"
  );

  if (!superAdminMembership) {
    throw new Error(ACCESS_DENIED_MESSAGE);
  }

  const step2 = await authClient.post<LoginResponse>(
    "/auth/select-tenant",
    { tenantCode: "KV-ADMIN", loginToken, deviceLabel: deviceLabel() }
  );

  // Story 16.11 — the access token proves identity only: the role is read from the profile.
  const profile = await fetchProfile(step2.data.accessToken);
  if (profile.role !== "SUPER_ADMIN") {
    // Close the session tenant selection just opened (best effort).
    try {
      await authClient.post("/auth/logout", { refreshToken: step2.data.refreshToken });
    } catch {
      // ignored: the refusal below is what matters
    }
    throw new Error(ACCESS_DENIED_MESSAGE);
  }
  return step2.data;
}

/** Story 16.11 — identity of the session (role, first name…) from `GET /auth/profile`. */
export async function fetchProfile(accessToken: string): Promise<UserProfile> {
  const res = await authClient.get<UserProfile>("/auth/profile", {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  return res.data;
}
