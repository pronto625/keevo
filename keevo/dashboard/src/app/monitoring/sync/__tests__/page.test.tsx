import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import SyncMonitoringPage from "@/app/monitoring/sync/page";
import { getSyncOverview, getTenantSyncDetail, getTenantSyncHealth } from "@/lib/api/admin-sync";

vi.mock("@/lib/api/admin-sync", () => ({
  getSyncOverview: vi.fn(),
  getTenantSyncHealth: vi.fn(),
  getTenantSyncDetail: vi.fn(),
}));

// The sidebar (navigation, auth) is out of scope here.
vi.mock("@/components/layout/AdminLayout", () => ({
  AdminLayout: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

const overview = vi.mocked(getSyncOverview);
const health = vi.mocked(getTenantSyncHealth);

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SyncMonitoringPage />
    </QueryClientProvider>,
  );
}

describe("Sync monitoring page wiring", () => {
  beforeEach(() => {
    overview.mockReset();
    health.mockReset();
    vi.mocked(getTenantSyncDetail).mockReset();
  });

  it("shows « Erreur de chargement » when /tenants fails and « Réessayer » refetches it", async () => {
    overview.mockResolvedValue({
      totalDevices: 1,
      totalFailedOps7d: 0,
      totalConflicts7d: 0,
      tenantsInAlert: 0,
      latestPushGlobally: null,
    });
    health.mockRejectedValueOnce(new Error("boom")).mockResolvedValueOnce([]);

    renderPage();

    expect(await screen.findByText("Erreur de chargement")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));

    await waitFor(() => expect(health).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("Aucune entreprise")).toBeInTheDocument();
  });

  it("disables « Actualiser » while the overview is pending", async () => {
    overview.mockReturnValue(new Promise(() => {}));
    health.mockResolvedValue([]);

    renderPage();

    const button = await screen.findByRole("button", { name: "Actualisation…" });
    expect(button).toBeDisabled();
  });
});
