import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RevenueKpiCards } from "@/components/revenue/RevenueKpiCards";
import type { RevenueSummary } from "@/types/revenue";

const UUID_RE = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

function summary(overrides: Partial<RevenueSummary> = {}): RevenueSummary {
  return {
    from: "2026-10-01",
    to: "2026-10-31",
    mrr: 15_000,
    arr: 180_000,
    mrrPreviousMonth: 10_000,
    mrrTrendPercent: 50,
    payingTenants: 1,
    freeTenants: 3,
    trialTenants: 2,
    premiumTenants: 2,
    premiumWithoutPayment: 0,
    newPayingTenants: 1,
    churnedTenants: 1,
    churnBase: 4,
    churnRate: 0.25,
    convertedTenants: 1,
    trialsEnded: 2,
    conversionRate: 0.5,
    revenueCollected: 15_000,
    ...overrides,
  };
}

/** Intl inserts narrow no-break spaces: compare with plain spaces. */
const norm = (s: string | null | undefined) => (s ?? "").replace(/[  ]/g, " ");

describe("RevenueKpiCards", () => {
  it("shows skeletons while loading", () => {
    render(<RevenueKpiCards summary={undefined} isLoading lastUpdated={null} onRefresh={vi.fn()} />);
    expect(screen.getAllByTestId("kpi-skeleton").length).toBeGreaterThan(0);
  });

  it("renders MRR/ARR in full XAF, rates in percent, the range, and no id", () => {
    const { container } = render(
      <RevenueKpiCards summary={summary()} isLoading={false} lastUpdated={null} onRefresh={vi.fn()} />,
    );
    const text = norm(container.textContent);
    expect(text).toContain("MRR");
    expect(text).toMatch(/15 000\s?F\s?CFA|15 000\s?FCFA|15 000\s?XAF/);
    expect(text).toMatch(/180 000/);
    expect(text).toContain("25 %");
    expect(text).toContain("50 %");
    expect(text).toContain("du 01/10/2026 au 31/10/2026");
    expect(text).not.toMatch(UUID_RE);
  });

  it("MRR up vs previous month → ▲ +X % in green", () => {
    render(<RevenueKpiCards summary={summary({ mrrTrendPercent: 12.5 })} isLoading={false} lastUpdated={null} onRefresh={vi.fn()} />);
    const trend = screen.getByTestId("mrr-trend");
    expect(norm(trend.textContent)).toBe("▲ +12,5 %");
    expect(trend.className).toContain("text-green-600");
  });

  it("MRR down → ▼ −X % in red", () => {
    render(<RevenueKpiCards summary={summary({ mrrTrendPercent: -8 })} isLoading={false} lastUpdated={null} onRefresh={vi.fn()} />);
    const trend = screen.getByTestId("mrr-trend");
    expect(norm(trend.textContent)).toBe("▼ −8 %");
    expect(trend.className).toContain("text-red-600");
  });

  it("no previous MRR → « — »; zero denominators → « — » rates", () => {
    render(
      <RevenueKpiCards
        summary={summary({ mrrTrendPercent: null, churnBase: 0, churnRate: 0, trialsEnded: 0, conversionRate: 0 })}
        isLoading={false}
        lastUpdated={null}
        onRefresh={vi.fn()}
      />,
    );
    expect(screen.getByTestId("mrr-trend")).toHaveTextContent("—");
    expect(screen.getByText("Taux de churn sur la période").parentElement).toHaveTextContent("—");
    expect(screen.getByText("Conversion essai → Premium").parentElement).toHaveTextContent("—");
  });

  it("uses « Entreprises » wording, never « tenant »", () => {
    const { container } = render(
      <RevenueKpiCards summary={summary()} isLoading={false} lastUpdated={null} onRefresh={vi.fn()} />,
    );
    for (const label of ["Entreprises payantes", "Entreprises gratuites", "Entreprises en essai", "Entreprises Premium", "Taux de churn sur la période"]) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
    expect(container.textContent).not.toMatch(/tenant/i);
    expect(screen.getByText("1 perdue(s) sur 4 payante(s)")).toBeInTheDocument();
  });

  it("Premium without payment: note shown only when > 0", () => {
    const { rerender } = render(
      <RevenueKpiCards summary={summary()} isLoading={false} lastUpdated={null} onRefresh={vi.fn()} />,
    );
    expect(screen.queryByTestId("premium-without-payment")).not.toBeInTheDocument();

    rerender(
      <RevenueKpiCards summary={summary({ premiumTenants: 3, premiumWithoutPayment: 2 })} isLoading={false} lastUpdated={null} onRefresh={vi.fn()} />,
    );
    expect(screen.getByText("Entreprises Premium").parentElement).toHaveTextContent("3");
    expect(screen.getByTestId("premium-without-payment")).toHaveTextContent(
      "2 sans paiement enregistré (non compté dans le MRR)",
    );
  });

  it("first-load error shows « Réessayer »; « Actualiser » refreshes and is disabled in flight", async () => {
    const onRetry = vi.fn();
    const onRefresh = vi.fn();
    const { rerender } = render(
      <RevenueKpiCards summary={undefined} isLoading={false} isError lastUpdated={null} onRefresh={onRefresh} onRetry={onRetry} />,
    );
    expect(screen.getByText("Erreur de chargement")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(onRetry).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole("button", { name: "Actualiser" }));
    expect(onRefresh).toHaveBeenCalledTimes(1);

    rerender(<RevenueKpiCards summary={summary()} isLoading={false} isFetching lastUpdated={null} onRefresh={onRefresh} />);
    expect(screen.getByRole("button", { name: "Actualisation…" })).toBeDisabled();
  });

  it("invalid range → asks for a valid period", () => {
    render(<RevenueKpiCards summary={summary()} isLoading={false} lastUpdated={null} onRefresh={vi.fn()} rangeInvalid />);
    expect(screen.getByText("Choisissez une période valide pour afficher les indicateurs")).toBeInTheDocument();
  });
});
