import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { HealthStatusCards } from "@/components/health/HealthStatusCards";
import type { SystemHealth } from "@/types/health";

const HEALTHY: SystemHealth = {
  uptimePercent30d: 99.95,
  uptimeBreached: false,
  p50Ms: 40,
  p95Ms: 300,
  p99Ms: 800,
  latencyBreached: false,
  errorRatePercent1h: 0.5,
  errorRateBreached: false,
  dbActiveConnections: 4,
  dbMaxConnections: 10,
  dbActiveConnectionsBreached: false,
  databaseOk: true,
  databaseBreached: false,
  syncRefusals1h: 12,
  syncRefusalsBreached: false,
  lastSampleAt: new Date(Date.now() - 3 * 60_000).toISOString(),
  checkedAt: new Date().toISOString(),
  incident: false,
  breachedMetrics: [],
};

function card(label: string): HTMLElement {
  return screen.getByText(label).closest("[data-testid='health-card']") as HTMLElement;
}

describe("HealthStatusCards (Story 9-5)", () => {
  it("healthy: every card neutral, French values and thresholds", () => {
    render(<HealthStatusCards data={HEALTHY} isLoading={false} />);

    expect(screen.getAllByTestId("health-card")).toHaveLength(6);
    screen.getAllByTestId("health-card").forEach((c) => expect(c).toHaveAttribute("data-breached", "false"));
    expect(screen.queryByLabelText("Seuil dépassé")).not.toBeInTheDocument();
    expect(card("Disponibilité (30 jours)")).toHaveTextContent("99,95 %");
    expect(card("Temps de réponse (p99)")).toHaveTextContent("800 ms");
    expect(card("Temps de réponse (p99)")).toHaveTextContent("Médiane 40 ms · p95 300 ms");
    expect(card("Taux d'erreurs (1 h)")).toHaveTextContent("0,5 %");
    expect(card("Base de données")).toHaveTextContent("✅ Opérationnelle");
    expect(card("Refus de synchronisation (1 h)")).toHaveTextContent("12");
    expect(card("Connexions actives à la base")).toHaveTextContent("4 / 10");
    expect(screen.getByText(/Dernière mesure il y a 3 min/)).toBeInTheDocument();
  });

  it("breached metric: red card with the warning icon", () => {
    render(
      <HealthStatusCards
        data={{ ...HEALTHY, errorRatePercent1h: 3, errorRateBreached: true, incident: true }}
        isLoading={false}
      />,
    );

    const errors = card("Taux d'erreurs (1 h)");
    expect(errors).toHaveAttribute("data-breached", "true");
    expect(errors).toHaveClass("bg-red-50");
    expect(errors).toContainElement(screen.getByLabelText("Seuil dépassé"));
    expect(card("Base de données")).toHaveAttribute("data-breached", "false");
  });

  it("connections at 80 % of the pool: red card", () => {
    render(
      <HealthStatusCards
        data={{ ...HEALTHY, dbActiveConnections: 8, dbActiveConnectionsBreached: true }}
        isLoading={false}
      />,
    );
    const c = card("Connexions actives à la base");
    expect(c).toHaveAttribute("data-breached", "true");
    expect(c).toHaveTextContent("8 / 10");
    expect(c).toContainElement(screen.getByLabelText("Seuil dépassé"));
  });

  it("database down and no samples yet: ❌ and « — », uptime not red", () => {
    render(
      <HealthStatusCards
        data={{
          ...HEALTHY,
          uptimePercent30d: null,
          p50Ms: null,
          p95Ms: null,
          p99Ms: null,
          errorRatePercent1h: null,
          syncRefusals1h: null,
          databaseOk: false,
          databaseBreached: true,
          lastSampleAt: null,
        }}
        isLoading={false}
      />,
    );

    expect(card("Base de données")).toHaveTextContent("❌ Injoignable");
    expect(card("Base de données")).toHaveAttribute("data-breached", "true");
    expect(card("Disponibilité (30 jours)")).toHaveTextContent("—");
    expect(card("Disponibilité (30 jours)")).toHaveAttribute("data-breached", "false");
    expect(screen.getByText(/Aucune mesure enregistrée/)).toBeInTheDocument();
  });

  it("loading: skeletons", () => {
    const { container } = render(<HealthStatusCards data={undefined} isLoading />);
    expect(container.querySelectorAll(".animate-pulse")).toHaveLength(6);
  });
});
