import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SyncHealthTable } from "@/components/sync/SyncHealthTable";
import type { AdminTenantSyncHealth } from "@/types/sync-monitoring";

const TENANT_A = "3f2b1c4d-0000-4000-8000-00000000000a";
const TENANT_B = "3f2b1c4d-0000-4000-8000-00000000000b";

function row(overrides: Partial<AdminTenantSyncHealth>): AdminTenantSyncHealth {
  return {
    tenantId: TENANT_A,
    tenantName: "Boutique Awa",
    tenantPlan: "PREMIUM_TRIAL",
    failedOps7d: 0,
    conflicts7d: 0,
    deviceCount: 2,
    lastPushAt: null,
    lastPullAt: null,
    status: "OK",
    ...overrides,
  };
}

const DATA = [
  row({ tenantId: TENANT_A, tenantName: "Boutique Awa", status: "ERROR", tenantPlan: "PREMIUM" }),
  row({ tenantId: TENANT_B, tenantName: "Boutique Bello", status: "WARN", tenantPlan: "FREE" }),
];

describe("SyncHealthTable", () => {
  it("shows skeleton rows while loading", () => {
    const { container } = render(
      <SyncHealthTable data={undefined} isLoading onRowClick={vi.fn()} selectedTenantId={null} />,
    );
    expect(container.querySelectorAll("tr.animate-pulse")).toHaveLength(5);
  });

  it("keeps the skeleton (no error) while a query is pending without error", () => {
    const { container } = render(
      <SyncHealthTable data={undefined} isLoading={false} onRowClick={vi.fn()} selectedTenantId={null} />,
    );
    expect(container.querySelectorAll("tr.animate-pulse")).toHaveLength(5);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows the empty state", () => {
    render(<SyncHealthTable data={[]} isLoading={false} onRowClick={vi.fn()} selectedTenantId={null} />);
    expect(screen.getByText("Aucune entreprise")).toBeInTheDocument();
  });

  it("shows a load error with a retry button on first-load failure", async () => {
    const onRetry = vi.fn();
    render(
      <SyncHealthTable
        data={undefined}
        isLoading={false}
        isError
        onRetry={onRetry}
        onRowClick={vi.fn()}
        selectedTenantId={null}
      />,
    );
    expect(screen.getByText("Erreur de chargement")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("keeps stale rows and warns when a refresh fails", async () => {
    const onRetry = vi.fn();
    render(
      <SyncHealthTable
        data={DATA}
        isLoading={false}
        isError
        onRetry={onRetry}
        onRowClick={vi.fn()}
        selectedTenantId={null}
      />,
    );
    expect(screen.getByText("Boutique Awa")).toBeInTheDocument();
    expect(
      screen.getByText("Échec de l'actualisation — données possiblement obsolètes"),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("renders French plan and status labels, never raw codes", () => {
    render(<SyncHealthTable data={DATA} isLoading={false} onRowClick={vi.fn()} selectedTenantId={null} />);
    expect(screen.getByText("Premium")).toBeInTheDocument();
    expect(screen.getByText("Gratuit")).toBeInTheDocument();
    expect(screen.getByText("En erreur")).toBeInTheDocument();
    expect(screen.getByText("À surveiller")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/\b(ERROR|WARN|PREMIUM|FREE)\b/);
  });

  it("calls onRowClick on click and on Enter", async () => {
    const onRowClick = vi.fn();
    render(<SyncHealthTable data={DATA} isLoading={false} onRowClick={onRowClick} selectedTenantId={null} />);

    await userEvent.click(screen.getByText("Boutique Awa"));
    expect(onRowClick).toHaveBeenLastCalledWith(TENANT_A);

    const rowB = screen.getByText("Boutique Bello").closest("tr")!;
    rowB.focus();
    await userEvent.keyboard("{Enter}");
    expect(onRowClick).toHaveBeenLastCalledWith(TENANT_B);
  });

  it("marks the selected row", () => {
    render(<SyncHealthTable data={DATA} isLoading={false} onRowClick={vi.fn()} selectedTenantId={TENANT_B} />);
    const rows = screen.getAllByRole("button").filter((el) => el.tagName === "TR");
    const selected = rows.find((r) => r.getAttribute("aria-pressed") === "true")!;
    expect(within(selected).getByText("Boutique Bello")).toBeInTheDocument();
    expect(selected).toHaveClass("bg-blue-50");
  });
});
