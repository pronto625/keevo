import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { MrrTooltip, MrrTrendChart } from "@/components/revenue/MrrTrendChart";
import type { RevenueTrendPoint } from "@/types/revenue";

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

const EMPTY_YEAR: RevenueTrendPoint[] = Array.from({ length: 12 }, (_, i) => ({
  month: `2026-${String(i + 1).padStart(2, "0")}`,
  mrr: 0,
  payingTenants: 0,
  newPayingTenants: 0,
  churnedTenants: 0,
}));

describe("MrrTrendChart", () => {
  it("shows a skeleton while loading", () => {
    render(<MrrTrendChart data={undefined} isLoading />);
    expect(screen.getByTestId("mrr-chart-skeleton")).toBeInTheDocument();
  });

  it("renders the chart for 12 empty months (no « Aucune donnée »)", () => {
    render(<MrrTrendChart data={EMPTY_YEAR} isLoading={false} />);
    expect(screen.getByTestId("chart")).toBeInTheDocument();
    expect(screen.queryByText("Aucune donnée")).not.toBeInTheDocument();
  });

  it("tooltip: French month, MRR in full XAF, paying, new paying, churned", () => {
    const point: RevenueTrendPoint = {
      month: "2026-09",
      mrr: 45_000,
      payingTenants: 3,
      newPayingTenants: 1,
      churnedTenants: 2,
    };
    const { container } = render(<MrrTooltip active payload={[{ payload: point }]} />);
    const text = (container.textContent ?? "").replace(/[\u00a0\u202f]/g, " ");
    expect(text).toContain("septembre 2026");
    expect(text).toMatch(/MRR : 45 000\s?(F\s?CFA|FCFA|XAF)/);
    expect(text).toContain("Entreprises payantes : 3");
    expect(text).toContain("Nouveaux payants : 1");
    expect(text).toContain("Entreprises perdues : 2");
    expect(text).not.toMatch(/tenant|conversion/i);
  });

  it("tooltip renders nothing when inactive", () => {
    const { container } = render(<MrrTooltip active={false} payload={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("shows the load error state", () => {
    render(<MrrTrendChart data={undefined} isLoading={false} isError onRetry={vi.fn()} />);
    expect(screen.getByText("Erreur de chargement")).toBeInTheDocument();
  });
});
