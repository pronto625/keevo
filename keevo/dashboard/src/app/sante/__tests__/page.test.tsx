import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import SantePageClient from "@/app/sante/SantePageClient";
import { getSystemHealth } from "@/lib/api/admin-health";
import type { SystemHealth } from "@/types/health";

vi.mock("@/lib/api/admin-health", () => ({ getSystemHealth: vi.fn(), getHealthStatus: vi.fn() }));
vi.mock("@/components/layout/AdminLayout", () => ({
  AdminLayout: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

const health = vi.mocked(getSystemHealth);

const DATA: SystemHealth = {
  uptimePercent30d: 100,
  uptimeBreached: false,
  p50Ms: 40,
  p95Ms: 300,
  p99Ms: 2500,
  latencyBreached: true,
  errorRatePercent1h: 0.5,
  errorRateBreached: false,
  dbActiveConnections: 4,
  dbMaxConnections: 10,
  dbActiveConnectionsBreached: false,
  databaseOk: true,
  databaseBreached: false,
  syncRefusals1h: 0,
  syncRefusalsBreached: false,
  lastSampleAt: null,
  checkedAt: "2026-10-13T10:00:00Z",
  incident: true,
  breachedMetrics: ["Temps de réponse (p99)"],
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <SantePageClient />
    </QueryClientProvider>,
  );
}

describe("« Santé système » page (Story 9-5)", () => {
  beforeEach(() => health.mockReset().mockResolvedValue(DATA));

  it("shows the cards, the breached one in red; « Actualiser » refetches", async () => {
    renderPage();
    expect(await screen.findByText("2 500 ms")).toBeInTheDocument();
    expect(screen.getByLabelText("Seuil dépassé")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Actualiser" }));
    await waitFor(() => expect(health).toHaveBeenCalledTimes(2));
  });

  it("first load error: « Erreur de chargement » + « Réessayer »", async () => {
    health.mockReset().mockRejectedValueOnce(new Error("boom")).mockResolvedValue(DATA);
    renderPage();
    expect(await screen.findByText("Erreur de chargement")).toBeInTheDocument();
  });
});
