import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ChurnTable } from "@/components/revenue/ChurnTable";
import type { ChurnReport } from "@/types/revenue";

const UUID_RE = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const norm = (s: string | null | undefined) => (s ?? "").replace(/[  ]/g, " ");

const REPORT: ChurnReport = {
  month: "2026-09",
  churnedCount: 2,
  payingAtMonthStart: 4,
  churnRate: 0.5,
  tenants: [
    { tenantName: "Boutique Alpha", planEndDate: "2026-09-12", tenureMonths: 8.3, lastGmv30d: 42_000 },
    { tenantName: "Sans nom", planEndDate: "2026-09-20", tenureMonths: null, lastGmv30d: 0 },
  ],
};

function renderTable(props: Partial<Parameters<typeof ChurnTable>[0]> = {}) {
  return render(
    <ChurnTable
      report={REPORT}
      isLoading={false}
      month="2026-09"
      onMonthChange={vi.fn()}
      monthValid
      onExport={vi.fn()}
      {...props}
    />,
  );
}

describe("ChurnTable", () => {
  it("lists churned tenants with French dates, tenure, last CA and the month rate; no id", () => {
    const { container } = renderTable();
    const text = norm(container.textContent);
    expect(text).toContain("Entreprises perdues — septembre 2026");
    expect(text).toContain("50 % (2 sur 4)");
    expect(screen.getByText("Boutique Alpha")).toBeInTheDocument();
    expect(screen.getByText("12/09/2026")).toBeInTheDocument();
    expect(text).toContain("8,3 mois");
    expect(text).toMatch(/42 000/);
    expect(screen.getByText("Sans nom")).toBeInTheDocument();
    expect(text).not.toMatch(UUID_RE);
  });

  it("empty month → « Aucune entreprise perdue ce mois-ci », rate « — » when nobody was paying", () => {
    const { container } = renderTable({
      report: { month: "2026-10", churnedCount: 0, payingAtMonthStart: 0, churnRate: 0, tenants: [] },
      month: "2026-10",
    });
    expect(screen.getByText("Aucune entreprise perdue ce mois-ci")).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/tenant/i);
    expect(norm(container.textContent)).toContain("Taux de churn : —");
  });

  it("« Exporter CSV » triggers the export; disabled while exporting or without a month", async () => {
    const onExport = vi.fn();
    const { rerender } = renderTable({ onExport });
    await userEvent.click(screen.getByRole("button", { name: /Exporter CSV/ }));
    expect(onExport).toHaveBeenCalledTimes(1);

    rerender(
      <ChurnTable report={REPORT} isLoading={false} month="2026-09" onMonthChange={vi.fn()} monthValid onExport={onExport} isExporting />,
    );
    expect(screen.getByRole("button", { name: "Export…" })).toBeDisabled();

    rerender(
      <ChurnTable report={REPORT} isLoading={false} month="" onMonthChange={vi.fn()} monthValid={false} onExport={onExport} />,
    );
    expect(screen.getByRole("button", { name: /Exporter CSV/ })).toBeDisabled();
    expect(screen.getByText("Choisissez un mois")).toBeInTheDocument();
  });

  it("shows an export failure and the load error state", async () => {
    const onRetry = vi.fn();
    renderTable({ report: undefined, isError: true, onRetry, exportFailed: true });
    expect(screen.getByText("L'export a échoué, réessayez.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("shows a skeleton while loading", () => {
    renderTable({ report: undefined, isLoading: true });
    expect(screen.getByTestId("churn-skeleton")).toBeInTheDocument();
  });
});
