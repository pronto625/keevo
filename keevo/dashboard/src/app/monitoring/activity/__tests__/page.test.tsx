import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import ActivityPageClient from "@/app/monitoring/activity/ActivityPageClient";
import {
  getGmvTrend,
  getInactiveTenants,
  getOperationsOverview,
  getTenantActivity,
} from "@/lib/api/admin-operations";
import type { AdminTenantActivityPage } from "@/types/operations";

vi.mock("@/lib/api/admin-operations", () => ({
  TENANT_ACTIVITY_PAGE_SIZE: 25,
  getOperationsOverview: vi.fn(),
  getGmvTrend: vi.fn(),
  getTenantActivity: vi.fn(),
  getInactiveTenants: vi.fn(),
}));

// The sidebar (navigation, auth) is out of scope here.
vi.mock("@/components/layout/AdminLayout", () => ({
  AdminLayout: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

// jsdom has no layout for recharts' ResponsiveContainer.
vi.mock("@/components/operations/GmvTrendChart", () => ({
  GmvTrendChart: () => <div data-testid="trend" />,
}));

const overview = vi.mocked(getOperationsOverview);
const trend = vi.mocked(getGmvTrend);
const tenants = vi.mocked(getTenantActivity);
const inactive = vi.mocked(getInactiveTenants);

function activityPage(page: number, totalPages = 3): AdminTenantActivityPage {
  return {
    content: [
      {
        tenantId: `id-${page}`,
        tenantName: `Boutique page ${page + 1}`,
        plan: "FREE",
        gmv: 1000,
        orderCount: 1,
        lastActivityAt: null,
        storeCount: 1,
      },
    ],
    totalElements: totalPages * 25,
    totalPages,
    number: page,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ActivityPageClient />
    </QueryClientProvider>,
  );
}

describe("Activity page wiring", () => {
  beforeEach(() => {
    overview.mockReset().mockResolvedValue({
      totalGmv: 1000,
      totalOrders: 1,
      activeTenantsCount: 1,
      newTenantsCount: 0,
      periodLabel: "7 derniers jours",
    });
    trend.mockReset().mockResolvedValue([]);
    tenants.mockReset().mockImplementation(async (_p, page) => activityPage(page));
    inactive.mockReset().mockResolvedValue([]);
  });

  it("changing the period after paging refetches the tenants on page 0", async () => {
    renderPage();
    expect(await screen.findByText("Boutique page 1")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Suivant →" }));
    expect(await screen.findByText("Boutique page 2")).toBeInTheDocument();
    expect(tenants).toHaveBeenLastCalledWith({ period: "7D" }, 1);

    await userEvent.click(screen.getByRole("button", { name: "30 jours" }));

    await waitFor(() => expect(tenants).toHaveBeenLastCalledWith({ period: "30D" }, 0));
    expect(await screen.findByText("Boutique page 1")).toBeInTheDocument();
  });

  it("an incomplete CUSTOM range makes no API call and asks for a valid period", async () => {
    renderPage();
    await screen.findByText("Boutique page 1");
    overview.mockClear();
    trend.mockClear();
    tenants.mockClear();

    await userEvent.click(screen.getByRole("button", { name: "Personnalisée" }));
    fireEvent.change(screen.getByLabelText("Du"), { target: { value: "2026-01-01" } });

    // KPI cards + tenant table (the chart is mocked here; its state is covered by GmvTrendChart.test).
    expect(
      screen.getAllByText("Choisissez une période valide pour afficher les données"),
    ).toHaveLength(2);
    expect(overview).not.toHaveBeenCalled();
    expect(trend).not.toHaveBeenCalled();
    expect(tenants).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("Au"), { target: { value: "2026-01-31" } });
    await waitFor(() =>
      expect(tenants).toHaveBeenLastCalledWith({ period: "CUSTOM", from: "2026-01-01", to: "2026-01-31" }, 0),
    );
    expect(overview).toHaveBeenCalledWith({ period: "CUSTOM", from: "2026-01-01", to: "2026-01-31" });
  });

  it("« Actualiser » refetches all four sections", async () => {
    renderPage();
    const button = await screen.findByRole("button", { name: "Actualiser" });
    await waitFor(() => expect(inactive).toHaveBeenCalledTimes(1));
    expect(overview).toHaveBeenCalledTimes(1);
    expect(trend).toHaveBeenCalledTimes(1);
    expect(tenants).toHaveBeenCalledTimes(1);

    await userEvent.click(button);

    await waitFor(() => {
      expect(overview).toHaveBeenCalledTimes(2);
      expect(trend).toHaveBeenCalledTimes(2);
      expect(tenants).toHaveBeenCalledTimes(2);
      expect(inactive).toHaveBeenCalledTimes(2);
    });
  });

  it("clamps the page when a refresh returns fewer pages", async () => {
    renderPage();
    await screen.findByText("Boutique page 1");
    await userEvent.click(screen.getByRole("button", { name: "Suivant →" }));
    await userEvent.click(await screen.findByRole("button", { name: "Suivant →" }));
    expect(await screen.findByText("Boutique page 3")).toBeInTheDocument();

    // The platform now has a single page of tenants.
    tenants.mockImplementation(async (_p, page) => activityPage(Math.min(page, 0), 1));
    await userEvent.click(screen.getByRole("button", { name: "Actualiser" }));

    await waitFor(() => expect(tenants).toHaveBeenLastCalledWith({ period: "7D" }, 0));
    expect(screen.queryByText(/Page 3 \//)).not.toBeInTheDocument();
  });
});
