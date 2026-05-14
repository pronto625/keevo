import axios from "axios";
import type { LoginResponse, LoginSessionResponse } from "@/types/auth";

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
    throw new Error("Accès refusé — compte super admin requis.");
  }

  const step2 = await authClient.post<LoginResponse>(
    "/auth/select-tenant",
    { tenantCode: "KV-ADMIN", loginToken }
  );

  return step2.data;
}
