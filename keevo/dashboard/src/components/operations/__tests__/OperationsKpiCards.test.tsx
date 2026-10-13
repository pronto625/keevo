import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { OperationsKpiCards } from "@/components/operations/OperationsKpiCards";
import type { AdminOperationsOverview } from "@/types/operations";

const DATA: AdminOperationsOverview = {
  totalGmv: 1_500_000,
  totalOrders: 42,
  activeTenantsCount: 7,
  newTenantsCount: 2,
  periodLabel: "7 derniers jours",
};

describe("OperationsKpiCards", () => {
  it("shows 4 skeleton cards while loading", () => {
    render(<OperationsKpiCards overview={undefined} isLoading lastUpdated={null} onRefresh={vi.fn()} />);
    expect(screen.getAllByTestId("kpi-skeleton")).toHaveLength(4);
  });

  it("renders the KPIs with the period label and XAF amount", () => {
    render(<OperationsKpiCards overview={DATA} isLoading={false} lastUpdated={null} onRefresh={vi.fn()} />);
    expect(screen.queryByTestId("kpi-skeleton")).not.toBeInTheDocument();
    expect(screen.getByText("1.5M XAF")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
    expect(screen.getByText("7")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
    expect(screen.getByText(/7 derniers jours/)).toBeInTheDocument();
  });

  it("refreshes on « Actualiser » and disables it while in flight", async () => {
    const onRefresh = vi.fn();
    const { rerender } = render(
      <OperationsKpiCards overview={DATA} isLoading={false} lastUpdated={null} onRefresh={onRefresh} />,
    );
    await userEvent.click(screen.getByRole("button", { name: "Actualiser" }));
    expect(onRefresh).toHaveBeenCalledTimes(1);

    rerender(
      <OperationsKpiCards overview={DATA} isLoading={false} lastUpdated={null} onRefresh={onRefresh} isFetching />,
    );
    expect(screen.getByRole("button", { name: "Actualisation…" })).toBeDisabled();
  });

  it("asks for a valid period instead of « — » while the custom range is incomplete", () => {
    render(
      <OperationsKpiCards overview={undefined} isLoading={false} lastUpdated={null} onRefresh={vi.fn()} rangeIncomplete />,
    );
    expect(screen.getByText("Choisissez une période valide pour afficher les données")).toBeInTheDocument();
    expect(screen.queryByText("—")).not.toBeInTheDocument();
  });

  it("shows the last update time", () => {
    render(
      <OperationsKpiCards
        overview={DATA}
        isLoading={false}
        lastUpdated={new Date("2026-10-13T08:05:00Z")} // 09:05 in Douala (UTC+1)
        onRefresh={vi.fn()}
      />,
    );
    expect(screen.getByText("Mis à jour à 09:05")).toBeInTheDocument();
  });

  it("shows a load error on first-load failure and a warning when a refresh fails", () => {
    const { rerender } = render(
      <OperationsKpiCards overview={undefined} isLoading={false} isError lastUpdated={null} onRefresh={vi.fn()} />,
    );
    expect(screen.getByText("Erreur de chargement")).toBeInTheDocument();

    rerender(
      <OperationsKpiCards overview={DATA} isLoading={false} isError lastUpdated={null} onRefresh={vi.fn()} />,
    );
    expect(screen.getByText("Échec de l'actualisation — données possiblement obsolètes")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
  });
});
