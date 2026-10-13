import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import AnalyticsPage from "@/app/analytics/page";
import {
  getAnalyticsGeography,
  getAnalyticsGrowth,
  getAnalyticsOverview,
  getAnalyticsRetention,
  getFeatureAdoption,
  getTopStores,
} from "@/lib/api/admin-analytics";
import { lastDaysRange } from "@/lib/period";

vi.mock("@/lib/api/admin-analytics", () => ({
  getAnalyticsOverview: vi.fn(),
  getAnalyticsGrowth: vi.fn(),
  getAnalyticsGeography: vi.fn(),
  getAnalyticsRetention: vi.fn(),
  getTopStores: vi.fn(),
  getFeatureAdoption: vi.fn(),
}));

vi.mock("@/components/layout/AdminLayout", () => ({
  AdminLayout: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

// jsdom has no layout for recharts' ResponsiveContainer.
vi.mock("@/components/analytics/GrowthCharts", () => ({ GrowthCharts: () => <div data-testid="growth" /> }));
vi.mock("@/components/analytics/AdoptionChart", () => ({ AdoptionChart: () => <div data-testid="adoption" /> }));

const overview = vi.mocked(getAnalyticsOverview);
const growth = vi.mocked(getAnalyticsGrowth);
const geography = vi.mocked(getAnalyticsGeography);
const retention = vi.mocked(getAnalyticsRetention);
const topStores = vi.mocked(getTopStores);
const adoption = vi.mocked(getFeatureAdoption);

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AnalyticsPage />
    </QueryClientProvider>,
  );
}

describe("Analytics page", () => {
  beforeEach(() => {
    overview.mockReset().mockResolvedValue({
      dau: 3, mau: 10, dauMauRatio: 0.3, totalTenants: 12, totalStores: 15, activeStores: 9,
      totalProducts: 4200, totalCompletedSales: 870, totalGmv: 1250000,
    });
    growth.mockReset().mockResolvedValue([]);
    geography.mockReset().mockResolvedValue([{ country: "CM", tenantCount: 12 }]);
    retention.mockReset().mockResolvedValue({ weeks: 8, cohorts: [] });
    topStores.mockReset().mockResolvedValue({ from: "2026-09-14", to: "2026-10-13", stores: [] });
    adoption.mockReset().mockResolvedValue({ activeTenants: 0, features: [] });
  });

  it("opens on « Vue d'ensemble » and loads only its data", async () => {
    renderPage();
    expect(await screen.findByText("30 %")).toBeInTheDocument();
    expect(screen.getByText("Cameroun")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Statistiques" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Vue d'ensemble" })).toHaveAttribute("aria-selected", "true");
    expect(growth).toHaveBeenCalledWith(12);
    expect(retention).not.toHaveBeenCalled();
    expect(topStores).not.toHaveBeenCalled();
  });

  it("tabs load their own data: rétention, top boutiques (last 30 days), adoption", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole("tab", { name: "Rétention" }));
    await waitFor(() => expect(retention).toHaveBeenCalledWith(8));

    await user.click(screen.getByRole("tab", { name: "Top boutiques" }));
    await waitFor(() => expect(topStores).toHaveBeenCalledWith(lastDaysRange(30)));
    expect(await screen.findByText("Aucune vente sur la période")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Adoption" }));
    expect(screen.getByTestId("adoption")).toBeInTheDocument();
    await waitFor(() => expect(adoption).toHaveBeenCalled());
  });

  it("top boutiques: an invalid range sends nothing and explains why", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole("tab", { name: "Top boutiques" }));
    await waitFor(() => expect(topStores).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByLabelText("Du"), { target: { value: "2020-01-01" } });

    expect(await screen.findByText("La période ne peut pas dépasser 366 jours.")).toBeInTheDocument();
    expect(screen.getByText("Choisissez une période valide pour afficher le classement")).toBeInTheDocument();
    expect(topStores).toHaveBeenCalledTimes(1);
  });

  it("tabs: id / aria-controls / aria-labelledby, roving tabIndex, ArrowLeft / ArrowRight navigation", async () => {
    const user = userEvent.setup();
    renderPage();
    const overviewTab = screen.getByRole("tab", { name: "Vue d'ensemble" });
    const retentionTab = screen.getByRole("tab", { name: "Rétention" });
    const adoptionTab = screen.getByRole("tab", { name: "Adoption" });
    const panel = screen.getByRole("tabpanel");

    expect(overviewTab).toHaveAttribute("aria-controls", panel.id);
    expect(panel).toHaveAttribute("aria-labelledby", overviewTab.id);
    expect(overviewTab).toHaveAttribute("tabindex", "0");
    expect(retentionTab).toHaveAttribute("tabindex", "-1");

    overviewTab.focus();
    await user.keyboard("{ArrowRight}");
    expect(retentionTab).toHaveFocus();
    expect(retentionTab).toHaveAttribute("aria-selected", "true");
    expect(retentionTab).toHaveAttribute("tabindex", "0");
    expect(overviewTab).toHaveAttribute("tabindex", "-1");
    expect(screen.getByRole("tabpanel")).toHaveAttribute("aria-labelledby", retentionTab.id);

    await user.keyboard("{ArrowLeft}");
    expect(overviewTab).toHaveFocus();
    await user.keyboard("{ArrowLeft}"); // wraps to the last tab
    expect(adoptionTab).toHaveFocus();
    expect(adoptionTab).toHaveAttribute("aria-selected", "true");
  });

  it("« Actualiser » refetches the overview data and is disabled while fetching", async () => {
    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByText("30 %")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Actualiser" })).toBeEnabled());
    expect(overview).toHaveBeenCalledTimes(1);

    let release: () => void = () => {};
    overview.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          release = () =>
            resolve({
              dau: 3, mau: 10, dauMauRatio: 0.3, totalTenants: 12, totalStores: 15, activeStores: 9,
              totalProducts: 4200, totalCompletedSales: 870, totalGmv: 1250000,
            });
        }),
    );
    await user.click(screen.getByRole("button", { name: "Actualiser" }));

    expect(await screen.findByRole("button", { name: "Actualisation…" })).toBeDisabled();
    expect(overview).toHaveBeenCalledTimes(2);
    expect(growth).toHaveBeenCalledTimes(2);
    expect(geography).toHaveBeenCalledTimes(2);

    release();
    await waitFor(() => expect(screen.getByRole("button", { name: "Actualiser" })).toBeEnabled());
  });

  it("first-load failure shows « Erreur de chargement » and « Réessayer » refetches", async () => {
    overview.mockReset().mockRejectedValueOnce(new Error("boom")).mockResolvedValue({
      dau: 1, mau: 2, dauMauRatio: 0.5, totalTenants: 1, totalStores: 1, activeStores: 1,
      totalProducts: 1, totalCompletedSales: 1, totalGmv: 1,
    });
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("Erreur de chargement")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(await screen.findByText("50 %")).toBeInTheDocument();
  });
});
