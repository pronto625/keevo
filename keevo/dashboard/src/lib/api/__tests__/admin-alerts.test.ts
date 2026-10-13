import { afterEach, describe, expect, it, vi } from "vitest";
import client from "@/lib/api/client";
import { getAlerts, getUnhandledHighAlertCount, markAlertHandled } from "@/lib/api/admin-alerts";

vi.mock("@/lib/api/client", () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const get = vi.mocked(client.get);
const post = vi.mocked(client.post);

describe("admin-alerts API (Story 9-5)", () => {
  afterEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it("feed sends includeHandled and unwraps data", async () => {
    get.mockResolvedValue({ data: { data: [{ key: "feedback:1" }] } });

    await expect(getAlerts(false)).resolves.toEqual([{ key: "feedback:1" }]);
    expect(get).toHaveBeenCalledWith("/admin/alerts", { params: { includeHandled: "false" } });

    await getAlerts(true);
    expect(get).toHaveBeenLastCalledWith("/admin/alerts", { params: { includeHandled: "true" } });
  });

  it("feed falls back to an empty list when data is null", async () => {
    get.mockResolvedValue({ data: { data: null } });
    await expect(getAlerts(false)).resolves.toEqual([]);
  });

  it("badge count uses the count endpoint with severity HIGH", async () => {
    get.mockResolvedValue({ data: { data: 4 } });
    await expect(getUnhandledHighAlertCount()).resolves.toBe(4);
    expect(get).toHaveBeenCalledWith("/admin/alerts/count", { params: { severity: "HIGH" } });

    get.mockResolvedValue({ data: { data: null } });
    await expect(getUnhandledHighAlertCount()).resolves.toBe(0);
  });

  it("handle encodes the key in the path", async () => {
    post.mockResolvedValue({ status: 204 });
    await markAlertHandled("sync-silence:11111111-1111-1111-1111-111111111111:1760000000");
    expect(post).toHaveBeenCalledWith(
      "/admin/alerts/sync-silence%3A11111111-1111-1111-1111-111111111111%3A1760000000/handle",
    );
  });
});
