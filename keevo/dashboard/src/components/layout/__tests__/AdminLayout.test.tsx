import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AdminLayout } from "@/components/layout/AdminLayout";
import { getHealthStatus } from "@/lib/api/admin-health";

vi.mock("next/navigation", () => ({
  usePathname: () => "/tenants",
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("@/hooks/useAuth", () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock("@/lib/api/admin-feedback", () => ({ getUnhandledHighCount: vi.fn().mockResolvedValue(0) }));
vi.mock("@/lib/api/admin-alerts", () => ({ getUnhandledHighAlertCount: vi.fn().mockResolvedValue(0) }));
vi.mock("@/lib/api/admin-health", () => ({ getHealthStatus: vi.fn(), getSystemHealth: vi.fn() }));

describe("AdminLayout (Story 9-5)", () => {
  it("every admin page carries the incident banner while a threshold is breached", async () => {
    vi.mocked(getHealthStatus).mockResolvedValue({ incident: true, breachedMetrics: ["Base de données injoignable"] });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <AdminLayout>
          <p>Contenu de la page</p>
        </AdminLayout>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent("Incident en cours — Base de données injoignable.");
    expect(screen.getByText("Contenu de la page")).toBeInTheDocument();
  });
});
