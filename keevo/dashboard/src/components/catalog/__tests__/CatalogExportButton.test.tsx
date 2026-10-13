import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import client from "@/lib/api/client";
import { downloadProductsCsv, productsCsvFilename } from "@/lib/api/admin-catalog";
import { CatalogExportButton } from "@/components/catalog/CatalogExportButton";
import type { ProductFilters } from "@/types/catalog";

vi.mock("@/lib/api/client", () => ({ default: { get: vi.fn() } }));

const FILTERS: ProductFilters = {
  search: "savon",
  tenantId: "",
  stockLevel: "LOW",
  status: "ALL",
  plan: "ALL",
};

describe("catalog CSV export (fix routed from 9-6)", () => {
  const createObjectURL = vi.fn(() => "blob:products");
  const revokeObjectURL = vi.fn();

  beforeEach(() => {
    Object.assign(URL, { createObjectURL, revokeObjectURL });
  });

  afterEach(() => {
    vi.mocked(client.get).mockReset();
    vi.restoreAllMocks();
  });

  it("names the file keevo-products-YYYY-MM-DD.csv with the Africa/Douala day", () => {
    expect(productsCsvFilename(new Date("2026-10-03T12:00:00Z"))).toBe("keevo-products-2026-10-03.csv");
    // 23:30 UTC on the 12th is already the 13th in Douala (UTC+1), whatever the browser zone
    expect(productsCsvFilename(new Date("2026-10-12T23:30:00Z"))).toBe("keevo-products-2026-10-13.csv");
  });

  it("downloads through the JWT axios client as a blob, with the table filters", async () => {
    vi.mocked(client.get).mockResolvedValue({ data: new Blob(["Produit;Entreprise"], { type: "text/csv" }) });
    let downloaded: string | null = null;
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      downloaded = this.download;
    });

    await downloadProductsCsv(FILTERS);

    expect(client.get).toHaveBeenCalledWith("/admin/catalog/products/export", {
      params: { search: "savon", stockLevel: "LOW" },
      responseType: "blob",
    });
    expect(downloaded).toBe(productsCsvFilename());
  });

  it("the button triggers the authenticated download and reports a failure in French", async () => {
    vi.mocked(client.get).mockRejectedValue(new Error("401"));
    const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <CatalogExportButton filters={FILTERS} />
      </QueryClientProvider>,
    );

    await userEvent.click(screen.getByRole("button", { name: /Exporter CSV/ }));

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Échec de l'export, réessayez"));
    expect(client.get).toHaveBeenCalledWith("/admin/catalog/products/export", expect.objectContaining({ responseType: "blob" }));
    expect(document.querySelector("a[href*='/api/backend/admin/catalog']")).toBeNull();
  });
});
