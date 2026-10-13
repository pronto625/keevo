import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { AdoptionChart, AdoptionTooltip } from "@/components/analytics/AdoptionChart";

vi.mock("recharts", async (importOriginal) => {
  const actual = await importOriginal<typeof import("recharts")>();
  return {
    ...actual,
    ResponsiveContainer: ({ children }: { children: ReactNode }) => (
      <div data-testid="chart" style={{ width: 600, height: 288 }}>
        {children}
      </div>
    ),
  };
});

describe("AdoptionChart", () => {
  it("renders the chart and the active-tenant base", () => {
    render(
      <AdoptionChart
        isLoading={false}
        report={{ activeTenants: 4, features: [{ feature: "SALES", tenantCount: 4, rate: 1 }] }}
      />,
    );
    expect(screen.getByTestId("chart")).toBeInTheDocument();
    expect(screen.getByTestId("adoption-base")).toHaveTextContent("4 entreprise(s) active(s)");
  });

  it("no active tenant → explicit empty state", () => {
    render(<AdoptionChart isLoading={false} report={{ activeTenants: 0, features: [] }} />);
    expect(screen.getByText("Aucune entreprise active sur les 30 derniers jours")).toBeInTheDocument();
  });

  it("tooltip: French feature label, share and count", () => {
    render(
      <AdoptionTooltip
        active
        payload={[{ payload: { feature: "OFFLINE_SYNC", label: "Synchronisation hors ligne", tenantCount: 1, rate: 0.25, percent: 25 } }]}
      />,
    );
    expect(screen.getByText("Synchronisation hors ligne")).toBeInTheDocument();
    expect(screen.getByText("25 % — 1 entreprise(s)")).toBeInTheDocument();
  });
});
