import { afterEach, describe, expect, it, vi } from "vitest";
import client from "@/lib/api/client";
import {
  getGmvTrend,
  getInactiveTenants,
  getOperationsOverview,
  getTenantActivity,
} from "@/lib/api/admin-operations";

vi.mock("@/lib/api/client", () => ({ default: { get: vi.fn() } }));

const get = vi.mocked(client.get);

describe("admin-operations API (Story 9-7)", () => {
  afterEach(() => get.mockReset());

  it("overview sends the period and unwraps data", async () => {
    get.mockResolvedValue({ data: { data: { totalGmv: 1500 } } });
    await expect(getOperationsOverview({ period: "7D" })).resolves.toEqual({ totalGmv: 1500 });
    expect(get).toHaveBeenCalledWith("/admin/operations/overview", { params: { period: "7D" } });
  });

  it("from/to are sent only for CUSTOM", async () => {
    get.mockResolvedValue({ data: { data: {} } });
    await getOperationsOverview({ period: "30D", from: "2026-01-01", to: "2026-01-31" });
    expect(get).toHaveBeenLastCalledWith("/admin/operations/overview", { params: { period: "30D" } });

    await getOperationsOverview({ period: "CUSTOM", from: "2026-01-01", to: "2026-01-31" });
    expect(get).toHaveBeenLastCalledWith("/admin/operations/overview", {
      params: { period: "CUSTOM", from: "2026-01-01", to: "2026-01-31" },
    });
  });

  it("trend sends the period and unwraps data", async () => {
    const points = [{ day: "2026-10-13", gmv: 100, orderCount: 1 }];
    get.mockResolvedValue({ data: { data: points } });
    await expect(getGmvTrend({ period: "CUSTOM", from: "2026-10-01", to: "2026-10-13" })).resolves.toEqual(points);
    expect(get).toHaveBeenCalledWith("/admin/operations/trend", {
      params: { period: "CUSTOM", from: "2026-10-01", to: "2026-10-13" },
    });
  });

  it("tenant activity sends period, page and pageSize 25, and unwraps data", async () => {
    const page = { items: [], totalItems: 0, totalPages: 0, page: 2 };
    get.mockResolvedValue({ data: { data: page } });
    await expect(getTenantActivity({ period: "TODAY" }, 2)).resolves.toEqual(page);
    expect(get).toHaveBeenCalledWith("/admin/operations/tenants", {
      params: { period: "TODAY", page: 2, pageSize: 25 },
    });
  });

  it("inactive tenants unwraps data", async () => {
    get.mockResolvedValue({ data: { data: [] } });
    await expect(getInactiveTenants()).resolves.toEqual([]);
    expect(get).toHaveBeenCalledWith("/admin/operations/inactive-tenants");
  });
});
