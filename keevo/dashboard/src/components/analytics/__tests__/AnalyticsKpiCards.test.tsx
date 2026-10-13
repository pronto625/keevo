import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { AnalyticsKpiCards } from "@/components/analytics/AnalyticsKpiCards";
import type { AnalyticsOverview } from "@/types/analytics";

const OVERVIEW: AnalyticsOverview = {
  dau: 3, mau: 10, dauMauRatio: 0.3, totalTenants: 12, totalStores: 15, activeStores: 9,
  totalProducts: 4200, totalCompletedSales: 870, totalGmv: 1250000,
};

describe("AnalyticsKpiCards", () => {
  it("shows the French KPI labels and values, DAU/MAU as a percentage", () => {
    render(<AnalyticsKpiCards overview={OVERVIEW} isLoading={false} />);
    expect(screen.getByText("Utilisateurs actifs aujourd'hui")).toBeInTheDocument();
    expect(screen.getByText("30 %")).toBeInTheDocument();
    expect(screen.getByText("Utilisateurs actifs (30 derniers jours)")).toBeInTheDocument();
    expect(screen.getByText("Taux d'engagement (actifs du jour / actifs 30 jours)")).toBeInTheDocument();
    expect(screen.queryByText(/DAU|MAU/)).not.toBeInTheDocument();
    expect(screen.getByText("Entreprises")).toBeInTheDocument();
    expect(screen.getByText("Boutiques actives (30 jours)")).toBeInTheDocument();
    expect(screen.queryByText(/tenant/i)).not.toBeInTheDocument();
  });

  it("MAU 0 → « — » for the ratio", () => {
    render(<AnalyticsKpiCards overview={{ ...OVERVIEW, dau: 0, mau: 0, dauMauRatio: null }} isLoading={false} />);
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("skeletons while loading, error with retry on first-load failure", () => {
    const { rerender } = render(<AnalyticsKpiCards overview={undefined} isLoading />);
    expect(screen.getAllByTestId("kpi-skeleton")).toHaveLength(9);
    rerender(<AnalyticsKpiCards overview={undefined} isLoading={false} isError onRetry={() => {}} />);
    expect(screen.getByText("Erreur de chargement")).toBeInTheDocument();
  });
});
