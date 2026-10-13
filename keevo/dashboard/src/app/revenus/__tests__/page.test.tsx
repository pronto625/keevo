import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import RevenusPage from "@/app/revenus/page";
import { downloadChurnCsv, getChurnReport, getRevenueSummary, getRevenueTrend } from "@/lib/api/admin-revenue";
import { currentMonth, monthRange } from "@/lib/period";

vi.mock("@/lib/api/admin-revenue", () => ({
  getRevenueSummary: vi.fn(),
  getRevenueTrend: vi.fn(),
  getChurnReport: vi.fn(),
  downloadChurnCsv: vi.fn(),
}));

vi.mock("@/components/layout/AdminLayout", () => ({
  AdminLayout: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

// jsdom has no layout for recharts' ResponsiveContainer.
vi.mock("@/components/revenue/MrrTrendChart", () => ({
  MrrTrendChart: () => <div data-testid="trend" />,
}));

const summary = vi.mocked(getRevenueSummary);
const trend = vi.mocked(getRevenueTrend);
const churn = vi.mocked(getChurnReport);
const download = vi.mocked(downloadChurnCsv);

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <RevenusPage />
    </QueryClientProvider>,
  );
}

describe("Revenus page wiring", () => {
  beforeEach(() => {
    summary.mockReset().mockResolvedValue({
      from: "2026-10-01", to: "2026-10-31", mrr: 0, arr: 0, mrrPreviousMonth: 0, mrrTrendPercent: null,
      payingTenants: 0, freeTenants: 0, trialTenants: 0, premiumTenants: 0, premiumWithoutPayment: 0, newPayingTenants: 0, churnedTenants: 0, churnBase: 0,
      churnRate: 0, convertedTenants: 0, trialsEnded: 0, conversionRate: 0, revenueCollected: 0,
    });
    trend.mockReset().mockResolvedValue([]);
    churn.mockReset().mockImplementation(async (month) => ({
      month, churnedCount: 0, payingAtMonthStart: 0, churnRate: 0, tenants: [],
    }));
    download.mockReset().mockResolvedValue(undefined);
  });

  it("defaults to the current month for the range and the churn table", async () => {
    renderPage();
    const month = currentMonth();
    await waitFor(() => expect(summary).toHaveBeenCalledWith(monthRange(month)));
    expect(churn).toHaveBeenCalledWith(month);
    expect(trend).toHaveBeenCalledTimes(1);
    expect(await screen.findByText("Aucune entreprise perdue ce mois-ci")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Revenus" })).toBeInTheDocument();
  });

  it("changing Du / Au refetches the summary with the new range", async () => {
    renderPage();
    await waitFor(() => expect(summary).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByLabelText("Du"), { target: { value: "2026-01-01" } });
    fireEvent.change(screen.getByLabelText("Au"), { target: { value: "2026-03-31" } });

    await waitFor(() => expect(summary).toHaveBeenLastCalledWith({ from: "2026-01-01", to: "2026-03-31" }));
  });

  it("an invalid range sends nothing and asks for a valid period", async () => {
    renderPage();
    await waitFor(() => expect(summary).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByLabelText("Du"), { target: { value: "2027-12-01" } });

    expect(await screen.findByText("Choisissez une période valide pour afficher les indicateurs")).toBeInTheDocument();
    expect(screen.getByText("La date de début doit précéder la date de fin.")).toBeInTheDocument();
    expect(summary).toHaveBeenCalledTimes(1);
  });

  it("« Ce mois-ci » resets the range to the current month", async () => {
    renderPage();
    const range = monthRange(currentMonth());
    await waitFor(() => expect(summary).toHaveBeenCalledTimes(1));

    const secondDay = `${range.from.slice(0, 8)}02`; // 2nd of the current month: always a valid, different range
    fireEvent.change(screen.getByLabelText("Du"), { target: { value: secondDay } });
    await waitFor(() => expect(summary).toHaveBeenLastCalledWith({ from: secondDay, to: range.to }));

    await userEvent.click(screen.getByRole("button", { name: "Ce mois-ci" }));

    expect(screen.getByLabelText("Du")).toHaveValue(range.from);
    expect(screen.getByLabelText("Au")).toHaveValue(range.to);
    await waitFor(() => expect(summary).toHaveBeenLastCalledWith(range));
  });

  it("changing the month refetches the churn of that month", async () => {
    renderPage();
    await waitFor(() => expect(churn).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByLabelText("Mois"), { target: { value: "2026-03" } });

    await waitFor(() => expect(churn).toHaveBeenLastCalledWith("2026-03"));
  });

  it("« Actualiser » calls the three APIs again", async () => {
    renderPage();
    await waitFor(() => {
      expect(summary).toHaveBeenCalledTimes(1);
      expect(trend).toHaveBeenCalledTimes(1);
      expect(churn).toHaveBeenCalledTimes(1);
    });
    await screen.findByRole("button", { name: "Actualiser" });

    await userEvent.click(screen.getByRole("button", { name: "Actualiser" }));

    await waitFor(() => {
      expect(summary).toHaveBeenCalledTimes(2);
      expect(trend).toHaveBeenCalledTimes(2);
      expect(churn).toHaveBeenCalledTimes(2);
    });
  });

  it("« Exporter CSV » exports the selected month", async () => {
    renderPage();
    fireEvent.change(screen.getByLabelText("Mois"), { target: { value: "2026-03" } });
    await waitFor(() => expect(churn).toHaveBeenLastCalledWith("2026-03"));

    await userEvent.click(screen.getByRole("button", { name: /Exporter CSV/ }));

    await waitFor(() => expect(download).toHaveBeenCalledWith("2026-03"));
  });

  it("an export failure is cleared when the month changes", async () => {
    download.mockRejectedValue(new Error("boom"));
    renderPage();
    await waitFor(() => expect(churn).toHaveBeenCalledTimes(1));

    await userEvent.click(screen.getByRole("button", { name: /Exporter CSV/ }));
    expect(await screen.findByText("L'export a échoué, réessayez.")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Mois"), { target: { value: "2026-03" } });

    await waitFor(() => expect(screen.queryByText("L'export a échoué, réessayez.")).not.toBeInTheDocument());
  });
});
