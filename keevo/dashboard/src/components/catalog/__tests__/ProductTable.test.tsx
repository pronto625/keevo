import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ProductTable } from "@/components/catalog/ProductTable";
import type { AdminProductPage } from "@/types/catalog";

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const CODE_PATTERN = /\b[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\b/;
/** All-caps tokens (FREE, PAID, ACTIVE…) are raw codes; XAF is the currency name. */
const ALL_CAPS_PATTERN = /\b(?!XAF\b)[A-Z]{3,}\b/;

describe("ProductTable (spec 9-1 completion)", () => {
  it("shows the company name and French plan / status labels — no id, no raw code, no « tenant »", () => {
    const data = {
      items: [
        { id: "3f2b1c4d-0000-4000-8000-0000000000aa", name: "Savon Lux", tenantId: "9a8b7c6d-1111-4111-8111-000000000001",
          tenantName: "Boutique Awa", tenantPlan: "PAID", categoryName: "Hygiène", price: 1500, stockQuantity: 23,
          status: "ACTIVE", updatedAt: "2026-10-12T09:00:00Z" },
        { id: "3f2b1c4d-0000-4000-8000-0000000000bb", name: "Riz 5kg", tenantId: "9a8b7c6d-1111-4111-8111-000000000002",
          tenantName: "Quincaillerie Kane", tenantPlan: "FREE", categoryName: null, price: 12000, stockQuantity: 0,
          status: "DRAFT", updatedAt: null },
      ],
      totalCount: 2,
      page: 0,
      pageSize: 25,
    } as unknown as AdminProductPage;

    render(<ProductTable data={data} isLoading={false} page={0} onPageChange={vi.fn()} />);

    expect(screen.getByText("Entreprise")).toBeInTheDocument();
    expect(screen.getByText("Boutique Awa")).toBeInTheDocument();
    expect(screen.getByText("Premium et essai")).toBeInTheDocument();
    expect(screen.getByText("Gratuit")).toBeInTheDocument();

    const text = document.body.textContent ?? "";
    expect(text).not.toMatch(UUID_PATTERN);
    expect(text).not.toMatch(CODE_PATTERN);
    expect(text).not.toMatch(ALL_CAPS_PATTERN);
    expect(text.toLowerCase()).not.toContain("tenant");
  });
});
