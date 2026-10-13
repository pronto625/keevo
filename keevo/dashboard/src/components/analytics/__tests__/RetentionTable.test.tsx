import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { RetentionTable, retentionCellClass } from "@/components/analytics/RetentionTable";
import type { RetentionReport } from "@/types/analytics";

const REPORT: RetentionReport = {
  weeks: 3,
  cohorts: [
    { weekStart: "2026-10-12", tenantCount: 2, retention: [null, null, null] },
    { weekStart: "2026-09-28", tenantCount: 4, retention: [0.75, 0.25, null] },
    { weekStart: "2026-09-21", tenantCount: 5, retention: [0.6, 0.3, 0.2] },
  ],
};

describe("retentionCellClass", () => {
  it("green > 60 %, yellow 30–60 %, red < 30 %, neutral for null", () => {
    expect(retentionCellClass(0.61)).toContain("green");
    expect(retentionCellClass(0.6)).toContain("yellow");
    expect(retentionCellClass(0.3)).toContain("yellow");
    expect(retentionCellClass(0.29)).toContain("red");
    expect(retentionCellClass(0)).toContain("red");
    expect(retentionCellClass(null)).toBe("text-gray-400");
  });
});

describe("RetentionTable", () => {
  it("renders cohorts with French week labels, coloured percentages and « — » for future weeks", () => {
    render(<RetentionTable report={REPORT} isLoading={false} />);

    expect(screen.getByText("Sem. du 28/09/2026")).toBeInTheDocument();
    expect(screen.getByText("Semaine 1")).toBeInTheDocument();
    expect(screen.getByText("Semaine 3")).toBeInTheDocument();
    const cells = screen.getAllByTestId("retention-cell");
    expect(cells).toHaveLength(9);
    expect(cells[3]).toHaveTextContent("75 %");
    expect(cells[3].className).toContain("green");
    expect(cells[4]).toHaveTextContent("25 %");
    expect(cells[4].className).toContain("red");
    expect(cells[5]).toHaveTextContent("—");
    expect(cells[6].className).toContain("yellow"); // exactly 60 %
    expect(screen.getByTestId("retention-legend")).toHaveTextContent("— : semaine pas encore terminée");
  });

  it("skeleton while loading", () => {
    render(<RetentionTable report={undefined} isLoading />);
    expect(screen.getByTestId("retention-skeleton")).toBeInTheDocument();
  });

  it("first-load error shows « Réessayer »", () => {
    const retry = vi.fn();
    render(<RetentionTable report={undefined} isLoading={false} isError onRetry={retry} />);
    expect(screen.getByText("Erreur de chargement")).toBeInTheDocument();
    screen.getByRole("button", { name: "Réessayer" }).click();
    expect(retry).toHaveBeenCalled();
  });
});
