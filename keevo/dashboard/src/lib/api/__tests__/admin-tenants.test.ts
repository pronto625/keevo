import { afterEach, describe, expect, it, vi } from "vitest";
import client from "@/lib/api/client";
import {
  activatePremium,
  downgradeToFree,
  getPlanAlerts,
  getTenantPayments,
  reactivateTenant,
  suspendTenant,
  voidPayment,
} from "@/lib/api/admin-tenants";

vi.mock("@/lib/api/client", () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const get = vi.mocked(client.get);
const post = vi.mocked(client.post);
const T = "3f2b1c4d-0000-4000-8000-0000000000aa";
const P = "3f2b1c4d-0000-4000-8000-0000000000bb";

describe("admin-tenants API (Story 9-2)", () => {
  afterEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it("activate posts the input to /plan/premium and unwraps the new plan", async () => {
    post.mockResolvedValue({ data: { data: { planType: "PREMIUM", expiresAt: "2027-01-31T22:59:59Z" } } });
    const input = { expiresAt: "2027-01-31", amountXaf: 10000, method: "CASH" as const, paidAt: "2026-10-10" };

    await expect(activatePremium(T, input)).resolves.toEqual({
      planType: "PREMIUM",
      expiresAt: "2027-01-31T22:59:59Z",
    });
    expect(post).toHaveBeenCalledWith(`/admin/tenants/${T}/plan/premium`, input);
  });

  it("downgrade sends the reason, or null when empty", async () => {
    post.mockResolvedValue({ data: { data: null } });
    await downgradeToFree(T, "non renouvelé");
    expect(post).toHaveBeenLastCalledWith(`/admin/tenants/${T}/plan/free`, { reason: "non renouvelé" });
    await downgradeToFree(T, "");
    expect(post).toHaveBeenLastCalledWith(`/admin/tenants/${T}/plan/free`, { reason: null });
  });

  it("suspend sends the reason; reactivate posts without a body", async () => {
    post.mockResolvedValue({ data: { data: null } });
    await suspendTenant(T, "fraude");
    expect(post).toHaveBeenLastCalledWith(`/admin/tenants/${T}/suspend`, { reason: "fraude" });
    await reactivateTenant(T);
    expect(post).toHaveBeenLastCalledWith(`/admin/tenants/${T}/reactivate`);
  });

  it("void posts the reason to /payments/{id}/void", async () => {
    post.mockResolvedValue({ data: { data: null } });
    await voidPayment(T, P, "Erreur de saisie");
    expect(post).toHaveBeenCalledWith(`/admin/tenants/${T}/payments/${P}/void`, { reason: "Erreur de saisie" });
  });

  it("payments and alerts unwrap data", async () => {
    get.mockResolvedValueOnce({ data: { data: [{ id: P, amountXaf: 10000 }] } });
    await expect(getTenantPayments(T)).resolves.toEqual([{ id: P, amountXaf: 10000 }]);
    expect(get).toHaveBeenLastCalledWith(`/admin/tenants/${T}/payments`);

    get.mockResolvedValueOnce({ data: { data: [{ tenantId: T, kind: "EXPIRING" }] } });
    await expect(getPlanAlerts()).resolves.toEqual([{ tenantId: T, kind: "EXPIRING" }]);
    expect(get).toHaveBeenLastCalledWith("/admin/subscriptions/alerts");
  });
});
