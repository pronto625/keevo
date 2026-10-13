import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { TenantTable, deletionCountdown } from "@/components/tenants/TenantTable";
import type { TenantListItem, TenantPage } from "@/types/tenant";

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const CODE_PATTERN = /\b[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\b/;
const ALL_CAPS_PATTERN = /\b[A-Z]{3,}\b/;

function item(over: Partial<TenantListItem>): TenantListItem {
  return {
    id: "3f2b1c4d-0000-4000-8000-0000000000aa",
    name: "Boutique Awa",
    ownerName: "Awa Diallo",
    ownerPhone: "+237600000001",
    plan: "PREMIUM_TRIAL",
    status: "ACTIVE",
    registeredAt: "2026-09-01T10:00:00Z",
    lastActivityAt: "2026-10-12T10:00:00Z",
    storeCount: 2,
    employeeCount: 3,
    deletionScheduledAt: null,
    ...over,
  };
}

function page(items: TenantListItem[], totalCount = items.length): TenantPage {
  return { items, totalCount, page: 0, pageSize: 25 };
}

describe("TenantTable (spec 9-1 completion)", () => {
  it("shows business data with French plan/status labels, never the code, an id or a raw enum", () => {
    const inThreeDays = new Date(Date.now() + 3 * 24 * 3600 * 1000 - 60_000).toISOString();
    render(
      <TenantTable
        data={page([
          item({}),
          item({ id: "3f2b1c4d-0000-4000-8000-0000000000bb", name: "Quincaillerie Kane", ownerName: "Propriétaire",
            plan: "PREMIUM", status: "SUSPENDED" }),
          item({ id: "3f2b1c4d-0000-4000-8000-0000000000cc", name: "Pharmacie Bio", plan: "FREE",
            status: "DELETION_PENDING", deletionScheduledAt: inThreeDays }),
        ])}
        isLoading={false}
        page={0}
        onPageChange={vi.fn()}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText("Affichage 1–3 sur 3 entreprises")).toBeInTheDocument();
    expect(screen.queryByText("Code")).not.toBeInTheDocument();
    expect(screen.getByText("Essai Premium")).toBeInTheDocument();
    expect(screen.getByText("Premium")).toBeInTheDocument();
    expect(screen.getByText("Gratuit")).toBeInTheDocument();
    expect(screen.getByText("Suspendu")).toBeInTheDocument();
    expect(screen.getByText("Suppression planifiée")).toBeInTheDocument();
    expect(screen.getAllByText("Awa Diallo")).toHaveLength(2);
    expect(screen.getByText("Suppression dans 3 jours")).toBeInTheDocument();

    const text = document.body.textContent ?? "";
    expect(text).not.toMatch(UUID_PATTERN);
    expect(text).not.toMatch(CODE_PATTERN);
    expect(text).not.toMatch(ALL_CAPS_PATTERN);
    expect(text.toLowerCase()).not.toContain("tenant");
  });

  it("highlights a DELETION_PENDING row in orange", () => {
    render(
      <TenantTable
        data={page([item({ status: "DELETION_PENDING", deletionScheduledAt: new Date(Date.now() + 86_400_000).toISOString() })])}
        isLoading={false}
        page={0}
        onPageChange={vi.fn()}
        onSelect={vi.fn()}
      />,
    );
    const row = screen.getByText("Boutique Awa").closest("tr")!;
    expect(row.className).toContain("bg-orange-50");
    expect(row.className).toContain("border-orange-400");
    expect(screen.getByText("Suppression dans 1 jour")).toBeInTheDocument();
  });

  it("a deletion date already past reads « Suppression imminente », never a countdown", () => {
    const now = Date.parse("2026-10-13T10:00:00Z");
    expect(deletionCountdown("2026-10-10T10:00:00Z", now)).toBe("Suppression imminente");
    expect(deletionCountdown("2026-10-13T10:00:00Z", now)).toBe("Suppression imminente");
    expect(deletionCountdown("2026-10-13T12:00:00Z", now)).toBe("Suppression dans 1 jour");
    expect(deletionCountdown("2026-10-16T09:00:00Z", now)).toBe("Suppression dans 3 jours");

    render(
      <TenantTable
        data={page([item({ status: "DELETION_PENDING", deletionScheduledAt: "2020-01-01T00:00:00Z" })])}
        isLoading={false}
        page={0}
        onPageChange={vi.fn()}
        onSelect={vi.fn()}
      />,
    );
    expect(screen.getByText("Suppression imminente")).toBeInTheDocument();
  });

  it("empty state says « entreprise »", () => {
    render(<TenantTable data={page([], 0)} isLoading={false} page={0} onPageChange={vi.fn()} onSelect={vi.fn()} />);
    expect(screen.getByText("Aucune entreprise trouvée")).toBeInTheDocument();
  });
});
