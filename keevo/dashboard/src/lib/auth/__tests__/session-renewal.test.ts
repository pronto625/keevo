import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { InternalAxiosRequestConfig } from "axios";

/** A JWT whose payload only carries `exp` (seconds). The signature is never checked here. */
function jwt(expSeconds: number): string {
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
  return `${b64({ alg: "RS256" })}.${b64({ sub: "u1", tenantId: "public", sid: "s1", exp: expSeconds })}.sig`;
}

const now = () => Math.floor(Date.now() / 1000);

type Reply = { status: number; data: unknown };

/**
 * Story 16.11 — the dashboard keeps working with a 30-minute access token: it renews before
 * expiry and on 401 TOKEN_EXPIRED, then replays the request; a refused renewal ends the session.
 */
describe("dashboard session renewal", () => {
  let refreshCalls: unknown[];
  let refreshReply: Reply;

  async function load() {
    vi.resetModules();
    const axios = (await import("axios")).default;
    refreshCalls = [];
    // The refresh client is created with axios.create(): intercept every instance's adapter.
    axios.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
      refreshCalls.push(JSON.parse(String(config.data)));
      if (refreshReply.status >= 400) {
        const err = Object.assign(new Error("refused"), {
          isAxiosError: true,
          response: { status: refreshReply.status, data: refreshReply.data },
          config,
        });
        throw err;
      }
      return { data: refreshReply.data, status: 200, statusText: "OK", headers: {}, config };
    };
    const ctx = await import("@/lib/auth/auth-context");
    const { default: client } = await import("@/lib/api/client");
    return { ctx, client };
  }

  beforeEach(() => {
    sessionStorage.clear();
    refreshReply = { status: 200, data: { accessToken: jwt(now() + 1800), refreshToken: "r2" } };
  });

  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it("renews an access token about to expire before sending the request", async () => {
    const { ctx, client } = await load();
    ctx.setSessionTokens(jwt(now() + 30), "r1");
    const seen: string[] = [];
    client.defaults.adapter = async (config) => {
      seen.push(String(config.headers.Authorization));
      return { data: {}, status: 200, statusText: "OK", headers: {}, config };
    };

    await client.get("/admin/tenants");

    expect(refreshCalls).toHaveLength(1);
    expect((refreshCalls[0] as { refreshToken: string }).refreshToken).toBe("r1");
    expect(seen[0]).toBe(`Bearer ${refreshReply.data && (refreshReply.data as { accessToken: string }).accessToken}`);
    expect(ctx.getRefreshToken()).toBe("r2");
  });

  it("on 401 TOKEN_EXPIRED: renews and replays the request once", async () => {
    const { ctx, client } = await load();
    ctx.setSessionTokens(jwt(now() + 1800), "r1");
    let calls = 0;
    client.defaults.adapter = async (config) => {
      calls++;
      if (calls === 1) {
        throw Object.assign(new Error("expired"), {
          isAxiosError: true,
          response: { status: 401, data: { domainCode: "TOKEN_EXPIRED" } },
          config,
        });
      }
      return { data: { ok: true }, status: 200, statusText: "OK", headers: {}, config };
    };

    const res = await client.get("/admin/tenants");

    expect(res.data).toEqual({ ok: true });
    expect(calls).toBe(2);
    expect(refreshCalls).toHaveLength(1);
  });

  it("a refused renewal (12 h idle) ends the session", async () => {
    refreshReply = { status: 401, data: { domainCode: "REFRESH_TOKEN_INVALID" } };
    const { ctx } = await load();
    ctx.setSessionTokens(jwt(now() + 10), "r1");
    const { renewSession } = await import("@/lib/auth/session");

    expect(await renewSession()).toBe("rejected");
    expect(ctx.getToken()).toBeNull();
    expect(sessionStorage.getItem("ks_admin_refresh")).toBeNull();
  });

  it("reads only the expiry of the token", async () => {
    const { tokenExpiry, expiresSoon } = await import("@/lib/auth/session");
    const exp = now() + 120;
    expect(tokenExpiry(jwt(exp))).toBe(exp * 1000);
    expect(expiresSoon(jwt(now() + 30))).toBe(true);
    expect(expiresSoon(jwt(now() + 1800))).toBe(false);
    expect(tokenExpiry("garbage")).toBeNull();
  });
});
