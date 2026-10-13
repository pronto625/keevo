import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { IncidentBanner, joinMetrics } from "@/components/health/IncidentBanner";
import { getHealthStatus } from "@/lib/api/admin-health";

vi.mock("@/lib/api/admin-health", () => ({ getHealthStatus: vi.fn(), getSystemHealth: vi.fn() }));

const status = vi.mocked(getHealthStatus);

function renderBanner() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IncidentBanner />
    </QueryClientProvider>,
  );
}

describe("IncidentBanner (Story 9-5)", () => {
  beforeEach(() => status.mockReset());

  it("shown while a threshold is breached", async () => {
    status.mockResolvedValue({ incident: true, breachedMetrics: ["Taux d'erreur au-dessus de 2 %"] });
    renderBanner();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "🔴 Incident en cours — Taux d'erreur au-dessus de 2 %. Vérifiez les journaux du serveur.",
    );
  });

  it("several metrics are listed", async () => {
    status.mockResolvedValue({
      incident: true,
      breachedMetrics: ["Base de données injoignable", "Disponibilité sous 99 %", "Latence p99 au-dessus de 2 s"],
    });
    renderBanner();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Incident en cours — Base de données injoignable, Disponibilité sous 99 % et Latence p99 au-dessus de 2 s.",
    );
    expect(screen.getByRole("alert").textContent).not.toContain("dépasse");
  });

  it("hidden when healthy", async () => {
    status.mockResolvedValue({ incident: false, breachedMetrics: [] });
    renderBanner();
    await waitFor(() => expect(status).toHaveBeenCalled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("joinMetrics", () => {
    expect(joinMetrics(["A"])).toBe("A");
    expect(joinMetrics(["A", "B", "C"])).toBe("A, B et C");
  });
});
