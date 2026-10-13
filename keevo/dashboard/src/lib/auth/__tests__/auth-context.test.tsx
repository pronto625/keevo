import { afterEach, describe, expect, it, vi } from "vitest";
import { render, waitFor } from "@testing-library/react";
import { useEffect } from "react";
import type { InternalAxiosRequestConfig } from "axios";

describe("AuthProvider token restore", () => {
  afterEach(() => {
    sessionStorage.clear();
    vi.resetModules();
  });

  it("restores ks_admin_token so the first request carries Authorization: Bearer", async () => {
    sessionStorage.setItem("ks_admin_token", "restored-jwt");
    // Fresh modules: the token lives in a module-level singleton.
    vi.resetModules();
    const { AuthProvider } = await import("@/lib/auth/auth-context");
    const { default: client } = await import("@/lib/api/client");

    const seen: InternalAxiosRequestConfig[] = [];
    client.defaults.adapter = async (config) => {
      seen.push(config);
      return { data: { data: null }, status: 200, statusText: "OK", headers: {}, config };
    };

    function FirstQuery() {
      useEffect(() => {
        void client.get("/api/v1/admin/sync/overview");
      }, []);
      return null;
    }

    render(
      <AuthProvider>
        <FirstQuery />
      </AuthProvider>,
    );

    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0].headers.Authorization).toBe("Bearer restored-jwt");
  });

  it("sends no Authorization header when nothing is stored", async () => {
    vi.resetModules();
    const { AuthProvider } = await import("@/lib/auth/auth-context");
    const { default: client } = await import("@/lib/api/client");

    const seen: InternalAxiosRequestConfig[] = [];
    client.defaults.adapter = async (config) => {
      seen.push(config);
      return { data: { data: null }, status: 200, statusText: "OK", headers: {}, config };
    };

    function FirstQuery() {
      useEffect(() => {
        void client.get("/api/v1/admin/sync/overview");
      }, []);
      return null;
    }

    render(
      <AuthProvider>
        <FirstQuery />
      </AuthProvider>,
    );

    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0].headers.Authorization).toBeUndefined();
  });
});
