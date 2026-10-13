import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { GmvTrendChart } from "@/components/operations/GmvTrendChart";
import type { GmvTrendPoint } from "@/types/operations";

// jsdom has no layout: give the responsive container a fixed size so the chart renders.
vi.mock("recharts", async (importOriginal) => {
  const actual = await importOriginal<typeof import("recharts")>();
  return {
    ...actual,
    ResponsiveContainer: ({ children }: { children: ReactNode }) => (
      <div data-testid="chart" style={{ width: 600, height: 256 }}>
        {children}
      </div>
    ),
  };
});

const DATA: GmvTrendPoint[] = [
  { date: "2026-10-12", gmv: 0, orderCount: 0 },
  { date: "2026-10-13", gmv: 1500, orderCount: 2 },
];

describe("GmvTrendChart", () => {
  it("shows a skeleton while loading", () => {
    render(<GmvTrendChart data={undefined} isLoading />);
    expect(screen.getByTestId("trend-skeleton")).toBeInTheDocument();
    expect(screen.queryByTestId("chart")).not.toBeInTheDocument();
  });

  it("shows a load error whose « Réessayer » calls onRetry", async () => {
    const onRetry = vi.fn();
    render(<GmvTrendChart data={undefined} isLoading={false} isError onRetry={onRetry} />);
    expect(screen.getByText("Erreur de chargement")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("shows the empty state", () => {
    render(<GmvTrendChart data={[]} isLoading={false} />);
    expect(screen.getByText("Aucune donnée")).toBeInTheDocument();
  });

  it("keeps the chart and warns when a refresh fails", () => {
    render(<GmvTrendChart data={DATA} isLoading={false} isError onRetry={vi.fn()} />);
    expect(screen.getByTestId("chart")).toBeInTheDocument();
    expect(screen.getByText("Échec de l'actualisation — données possiblement obsolètes")).toBeInTheDocument();
  });

  it("asks for a valid period while the custom range is incomplete", () => {
    render(<GmvTrendChart data={undefined} isLoading={false} rangeIncomplete />);
    expect(screen.getByText("Choisissez une période valide pour afficher les données")).toBeInTheDocument();
    expect(screen.queryByText("Aucune donnée")).not.toBeInTheDocument();
  });
});
