import { afterEach, describe, expect, it, vi } from "vitest";
import client from "@/lib/api/client";
import {
  cancelGlobalNotification,
  getGlobalNotifications,
  getRecipientEstimate,
  scheduleGlobalNotification,
  searchPushTenants,
} from "@/lib/api/admin-notifications";

vi.mock("@/lib/api/client", () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const get = vi.mocked(client.get);
const post = vi.mocked(client.post);
const ID = "3f2b1c4d-0000-4000-8000-0000000000aa";

describe("admin-notifications API (Story 9-5)", () => {
  afterEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it("schedule posts the body and unwraps data", async () => {
    post.mockResolvedValue({ data: { data: { id: ID, recipientEstimate: 5 } } });
    const input = { title: "T", body: "B", target: "ALL" as const, targetTenantId: null, scheduledAt: null };
    await expect(scheduleGlobalNotification(input)).resolves.toEqual({ id: ID, recipientEstimate: 5 });
    expect(post).toHaveBeenCalledWith("/admin/notifications/global", input);
  });

  it("history unwraps data, empty when null", async () => {
    get.mockResolvedValue({ data: { data: [{ id: ID }] } });
    await expect(getGlobalNotifications()).resolves.toEqual([{ id: ID }]);
    expect(get).toHaveBeenCalledWith("/admin/notifications/global");
    get.mockResolvedValue({ data: { data: null } });
    await expect(getGlobalNotifications()).resolves.toEqual([]);
  });

  it("cancel posts to the row's cancel path", async () => {
    post.mockResolvedValue({ data: null });
    await cancelGlobalNotification(ID);
    expect(post).toHaveBeenCalledWith(`/admin/notifications/global/${ID}/cancel`);
  });

  it("estimate sends the tenant only when there is one", async () => {
    get.mockResolvedValue({ data: { data: 7 } });
    await expect(getRecipientEstimate("PREMIUM", null)).resolves.toBe(7);
    expect(get).toHaveBeenCalledWith("/admin/notifications/global/estimate", { params: { target: "PREMIUM" } });
    await getRecipientEstimate("TENANT", ID);
    expect(get).toHaveBeenLastCalledWith("/admin/notifications/global/estimate", {
      params: { target: "TENANT", targetTenantId: ID },
    });
  });

  it("company search uses the push endpoint and keeps id + name only", async () => {
    get.mockResolvedValue({ data: { data: [{ id: ID, name: "Boutique Awa", extra: "x" }] } });
    await expect(searchPushTenants("Awa")).resolves.toEqual([{ id: ID, name: "Boutique Awa" }]);
    expect(get).toHaveBeenCalledWith("/admin/notifications/global/tenants", { params: { search: "Awa" } });
  });
});
