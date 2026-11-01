import { AxiosError, AxiosHeaders } from "axios";
import { describe, expect, it } from "vitest";
import {
  ACCESS_DENIED_MESSAGE,
  INVALID_CREDENTIALS_MESSAGE,
  LOGIN_FAILED_MESSAGE,
  SERVER_UNREACHABLE_MESSAGE,
  loginErrorMessage,
} from "@/lib/api/auth";

function httpError(status: number, data: unknown = {}): AxiosError {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError(
    `Request failed with status code ${status}`,
    "ERR_BAD_RESPONSE",
    config,
    {},
    { status, statusText: "", data, headers: {}, config },
  );
}

describe("loginErrorMessage (W2)", () => {
  it("maps 401 to the wrong-credentials message", () => {
    expect(loginErrorMessage(httpError(401))).toBe(INVALID_CREDENTIALS_MESSAGE);
  });

  it("maps an INVALID_CREDENTIALS domain code to the wrong-credentials message", () => {
    expect(loginErrorMessage(httpError(400, { domainCode: "INVALID_CREDENTIALS" }))).toBe(
      INVALID_CREDENTIALS_MESSAGE,
    );
  });

  it("maps 403 to access denied", () => {
    expect(loginErrorMessage(httpError(403))).toBe(ACCESS_DENIED_MESSAGE);
  });

  it("maps a network error (no response) to server unreachable", () => {
    const err = new AxiosError("Network Error", "ERR_NETWORK", { headers: new AxiosHeaders() });
    expect(loginErrorMessage(err)).toBe(SERVER_UNREACHABLE_MESSAGE);
  });

  it("never shows axios' English text for a 500", () => {
    const msg = loginErrorMessage(httpError(500));
    expect(msg).toBe(LOGIN_FAILED_MESSAGE);
    expect(msg).not.toMatch(/Request failed/);
  });

  it("keeps the super-admin refusal thrown by login()", () => {
    expect(loginErrorMessage(new Error(ACCESS_DENIED_MESSAGE))).toBe(ACCESS_DENIED_MESSAGE);
  });

  it("falls back to the generic message for anything else", () => {
    expect(loginErrorMessage(new Error("boom"))).toBe(LOGIN_FAILED_MESSAGE);
    expect(loginErrorMessage("oops")).toBe(LOGIN_FAILED_MESSAGE);
  });
});

describe("login reads the role from the profile (16.11)", () => {
  async function loginWithProfileRole(role: string) {
    const { vi } = await import("vitest");
    vi.resetModules();
    const axios = (await import("axios")).default;
    const sent: { url?: string; auth?: unknown; body?: unknown }[] = [];
    axios.defaults.adapter = async (config) => {
      sent.push({ url: config.url, auth: config.headers?.Authorization, body: config.data });
      const data =
        config.url === "/auth/login"
          ? { loginToken: "lt", memberships: [{ tenantCode: "KV-ADMIN", role: "SUPER_ADMIN" }] }
          : config.url === "/auth/select-tenant"
            ? { accessToken: "a1", refreshToken: "r1" }
            : { role };
      return { data, status: 200, statusText: "OK", headers: {}, config };
    };
    const mod = await import("@/lib/api/auth");
    return { result: mod.login("+237600000000", "secret"), sent, mod };
  }

  it("a super-admin profile signs in; the profile is read with the new token", async () => {
    const { result, sent } = await loginWithProfileRole("SUPER_ADMIN");
    await expect(result).resolves.toMatchObject({ accessToken: "a1", refreshToken: "r1" });
    expect(sent[2]).toMatchObject({ url: "/auth/profile", auth: "Bearer a1" });
  });

  it("any other profile role is refused and the session just opened is signed out", async () => {
    const { result, mod, sent } = await loginWithProfileRole("OWNER");
    await expect(result).rejects.toThrow(mod.ACCESS_DENIED_MESSAGE);
    expect(sent[3]).toMatchObject({ url: "/auth/logout" });
    expect(JSON.parse(String(sent[3].body))).toEqual({ refreshToken: "r1" });
  });
});
