import { afterEach, describe, expect, it, vi } from "vitest";
import client from "@/lib/api/client";
import { getHealthStatus, getSystemHealth } from "@/lib/api/admin-health";

vi.mock("@/lib/api/client", () => ({ default: { get: vi.fn() } }));

const get = vi.mocked(client.get);

describe("admin-health API (Story 9-5)", () => {
  afterEach(() => get.mockReset());

  it("health unwraps data", async () => {
    get.mockResolvedValue({ data: { data: { databaseOk: true } } });
    await expect(getSystemHealth()).resolves.toEqual({ databaseOk: true });
    expect(get).toHaveBeenCalledWith("/admin/health");
  });

  it("status unwraps data", async () => {
    get.mockResolvedValue({ data: { data: { incident: true, breachedMetrics: ["Base de données injoignable"] } } });
    await expect(getHealthStatus()).resolves.toEqual({ incident: true, breachedMetrics: ["Base de données injoignable"] });
    expect(get).toHaveBeenCalledWith("/admin/health/status");
  });

  it("status falls back to « no incident » when data is null", async () => {
    get.mockResolvedValue({ data: { data: null } });
    await expect(getHealthStatus()).resolves.toEqual({ incident: false, breachedMetrics: [] });
  });
});
