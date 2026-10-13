import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TenantDetailDrawer } from "@/components/tenants/TenantDetailDrawer";
import { getTenantDetail } from "@/lib/api/admin-tenants";
import type { TenantDetail } from "@/types/tenant";

vi.mock("@/lib/api/admin-tenants", () => ({
  getTenantDetail: vi.fn(),
  cancelDeletion: vi.fn(),
  forceDelete: vi.fn(),
}));

// Plan & payments has its own tests (9-2).
vi.mock("@/components/tenants/PlanSection", () => ({
  PlanSection: () => <div data-testid="plan-section" />,
}));

const mockedDetail = vi.mocked(getTenantDetail);

const TENANT_ID = "3f2b1c4d-0000-4000-8000-0000000000aa";
const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const CODE_PATTERN = /\b[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\b/;
/** All-caps tokens (OWNER, FREE, PRODUCT…) are raw codes; XAF is the currency name. */
const ALL_CAPS_PATTERN = /\b(?!XAF\b)[A-Z]{3,}\b/;

function isoDay(offset: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

const DETAIL: TenantDetail = {
  tenant: {
    id: TENANT_ID,
    name: "Boutique Awa",
    ownerName: "Awa Diallo",
    ownerPhone: "+237600000001",
    plan: "PREMIUM_TRIAL",
    status: "ACTIVE",
    registeredAt: "2026-09-01T10:00:00Z",
    lastActivityAt: "2026-10-12T10:00:00Z",
    storeCount: 1,
    employeeCount: 3,
    deletionScheduledAt: null,
  },
  stores: [{ id: "9a8b7c6d-1111-4111-8111-0000000000f1", name: "Boutique Centre", type: "STORE", totalRevenue: 125000 }],
  employees: [
    { name: "Awa Diallo", role: "OWNER", lastActivityAt: isoDay(0) },
    { name: "Moussa Kane", role: "EMPLOYEE", lastActivityAt: isoDay(-1) },
    { name: "Employé", role: "EMPLOYEE", lastActivityAt: null },
  ],
  auditLog: {
    events: [
      { action: "PRICE_CHANGED", entityType: "Product", entityLabel: "Savon", actorName: "Awa Diallo",
        storeName: "Boutique Centre", occurredAt: "2026-10-12T09:00:00Z" },
      { action: "STOCK_THRESHOLD_BREACHED", entityType: "StockAlert", entityLabel: null, actorName: "Système",
        storeName: null, occurredAt: "2026-10-12T08:00:00Z" },
      { action: "SOME_NEW_ACTION", entityType: "MysteryThing", entityLabel: null, actorName: "Propriétaire",
        storeName: null, occurredAt: "2026-10-11T08:00:00Z" },
    ],
    total: 3,
    page: 0,
  },
  subscription: { planType: "PREMIUM_TRIAL", expiresAt: "2027-03-01T00:00:00Z" },
};

function renderDrawer() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <TenantDetailDrawer tenantId={TENANT_ID} onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe("TenantDetailDrawer (spec 9-1 completion)", () => {
  beforeEach(() => mockedDetail.mockReset());

  it("every line is readable French business data — no id, no code", async () => {
    mockedDetail.mockResolvedValue(DETAIL);
    renderDrawer();

    expect(await screen.findByText("Prix modifié — Savon")).toBeInTheDocument();
    expect(screen.getByText("Détail de l'entreprise")).toBeInTheDocument();
    expect(screen.queryByText("Code")).not.toBeInTheDocument();
    expect(screen.getByText("Essai Premium")).toBeInTheDocument();
    expect(screen.getByText("Boutique")).toBeInTheDocument();
    // employees: names, role labels, last activity
    expect(screen.getByText("Moussa Kane")).toBeInTheDocument();
    expect(screen.getByText("Propriétaire", { selector: "td" })).toBeInTheDocument();
    expect(screen.getAllByText("Employé", { selector: "td" })).toHaveLength(3);
    expect(screen.getByText("Aujourd'hui")).toBeInTheDocument();
    expect(screen.getByText("Hier")).toBeInTheDocument();
    expect(screen.getByText("Jamais")).toBeInTheDocument();
    // audit: label + actor; no label → action only; unknown action humanized
    expect(screen.getByText("Produit · par Awa Diallo · Boutique Centre")).toBeInTheDocument();
    expect(screen.getByText("Alerte de stock bas")).toBeInTheDocument();
    expect(screen.getByText("Alerte de stock · par Système")).toBeInTheDocument();
    expect(screen.getByText("Some new action")).toBeInTheDocument();
    expect(screen.getByText("Mystery thing · par Propriétaire")).toBeInTheDocument();

    const text = document.body.textContent ?? "";
    expect(text).not.toMatch(UUID_PATTERN);
    expect(text).not.toMatch(CODE_PATTERN);
    expect(text).not.toMatch(ALL_CAPS_PATTERN);
    expect(text.toLowerCase()).not.toContain("tenant");
  });
});
