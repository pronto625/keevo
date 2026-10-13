import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SyncOverviewCards } from "@/components/sync/SyncOverviewCards";
import type { AdminSyncOverview } from "@/types/sync-monitoring";

const DATA: AdminSyncOverview = {
  totalDevices: 12,
  totalFailedOps7d: 3,
  totalConflicts7d: 1,
  tenantsInAlert: 0,
  latestPushGlobally: null,
};

describe("SyncOverviewCards", () => {
  it("disables « Actualiser » while a refresh is in flight", () => {
    render(<SyncOverviewCards data={DATA} isLoading={false} isFetching onRefresh={vi.fn()} />);
    const button = screen.getByRole("button", { name: "Actualisation…" });
    expect(button).toBeDisabled();
  });

  it("refreshes on « Actualiser »", async () => {
    const onRefresh = vi.fn();
    render(<SyncOverviewCards data={DATA} isLoading={false} onRefresh={onRefresh} />);
    await userEvent.click(screen.getByRole("button", { name: "Actualiser" }));
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it("shows a load error with retry on first-load failure", async () => {
    const onRetry = vi.fn();
    render(
      <SyncOverviewCards data={undefined} isLoading={false} isError onRefresh={vi.fn()} onRetry={onRetry} />,
    );
    expect(screen.getByText("Erreur de chargement")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("keeps stale KPIs and warns when a refresh fails", () => {
    render(<SyncOverviewCards data={DATA} isLoading={false} isError onRefresh={vi.fn()} onRetry={vi.fn()} />);
    expect(screen.getByText("12")).toBeInTheDocument();
    expect(
      screen.getByText("Échec de l'actualisation — données possiblement obsolètes"),
    ).toBeInTheDocument();
  });
});
