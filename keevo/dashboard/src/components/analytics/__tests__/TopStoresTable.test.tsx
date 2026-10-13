import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { TopStoresTable } from "@/components/analytics/TopStoresTable";
import type { TopStoresReport } from "@/types/analytics";

const REPORT: TopStoresReport = {
  from: "2026-09-14",
  to: "2026-10-13",
  stores: [
    { storeName: "Bonapriso", tenantName: "Boutique B", gmv: 50000, transactions: 3, averageBasket: 16667, growthPercent: null },
    { storeName: "Akwa", tenantName: "Boutique A", gmv: 30000, transactions: 4, averageBasket: 7500, growthPercent: 50 },
    { storeName: "Deido", tenantName: "Boutique C", gmv: 10000, transactions: 10, averageBasket: 1000, growthPercent: -20 },
  ],
};

function ranks(): string[] {
  return screen.getAllByTestId("top-store-row").map((r) => within(r).getAllByRole("cell")[0].textContent ?? "");
}

function storeNames(): string[] {
  return screen.getAllByTestId("top-store-row").map((r) => within(r).getAllByRole("cell")[1].textContent ?? "");
}

describe("TopStoresTable", () => {
  it("defaults to GMV desc, shows « Nouveau » for a null growth and signed variations", () => {
    render(<TopStoresTable report={REPORT} isLoading={false} />);

    expect(storeNames()).toEqual(["Bonapriso", "Akwa", "Deido"]);
    expect(screen.getByText("Nouveau")).toBeInTheDocument();
    expect(screen.getByText("▲ +50 %")).toBeInTheDocument();
    expect(screen.getByText("▼ −20 %")).toBeInTheDocument();
    expect(screen.getByText(/du 14\/09\/2026 au 13\/10\/2026/)).toBeInTheDocument();
  });

  it("clicking a column sorts by it, clicking again toggles asc/desc", async () => {
    const user = userEvent.setup();
    render(<TopStoresTable report={REPORT} isLoading={false} />);

    await user.click(screen.getByRole("button", { name: /Transactions/ }));
    expect(storeNames()).toEqual(["Deido", "Akwa", "Bonapriso"]);
    await user.click(screen.getByRole("button", { name: /Transactions/ }));
    expect(storeNames()).toEqual(["Bonapriso", "Akwa", "Deido"]);

    await user.click(screen.getByRole("button", { name: /Boutique$/ }));
    expect(storeNames()).toEqual(["Akwa", "Bonapriso", "Deido"]);
    // « # » stays the server GMV rank whatever the sort.
    expect(ranks()).toEqual(["2", "1", "3"]);
    await user.click(screen.getByRole("button", { name: /Boutique/ }));
    expect(storeNames()).toEqual(["Deido", "Bonapriso", "Akwa"]);
  });

  it("« Nouveau » rows stay last when sorting by growth", async () => {
    const user = userEvent.setup();
    render(<TopStoresTable report={REPORT} isLoading={false} />);

    await user.click(screen.getByRole("button", { name: /Évolution/ }));
    expect(storeNames()).toEqual(["Akwa", "Deido", "Bonapriso"]);
    await user.click(screen.getByRole("button", { name: /Évolution/ }));
    expect(storeNames()).toEqual(["Deido", "Akwa", "Bonapriso"]);
  });

  it("never renders an id-like value", () => {
    const { container } = render(<TopStoresTable report={REPORT} isLoading={false} />);
    expect(container.textContent).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-/i);
  });

  it("invalid range: asks for a valid period instead of the table", () => {
    render(<TopStoresTable report={REPORT} isLoading={false} rangeInvalid />);
    expect(screen.getByText("Choisissez une période valide pour afficher le classement")).toBeInTheDocument();
    expect(screen.queryByTestId("top-store-row")).not.toBeInTheDocument();
  });

  it("stale data warning when a refresh fails", () => {
    render(<TopStoresTable report={REPORT} isLoading={false} isError onRetry={() => {}} />);
    expect(screen.getByText(/Échec de l.actualisation/)).toBeInTheDocument();
    expect(screen.getAllByTestId("top-store-row")).toHaveLength(3);
  });
});
