import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PeriodSelector } from "@/components/operations/PeriodSelector";
import type { Period } from "@/types/operations";

function renderSelector(period: Period, customFrom = "", customTo = "") {
  const props = {
    onPeriodChange: vi.fn(),
    onCustomFromChange: vi.fn(),
    onCustomToChange: vi.fn(),
  };
  render(
    <PeriodSelector period={period} customFrom={customFrom} customTo={customTo} {...props} />,
  );
  return props;
}

describe("PeriodSelector", () => {
  it("shows French period labels and marks the selected one", () => {
    renderSelector("7D");
    for (const label of ["Aujourd'hui", "7 jours", "30 jours", "90 jours", "Personnalisée"]) {
      expect(screen.getByRole("button", { name: label })).toBeInTheDocument();
    }
    expect(screen.getByRole("button", { name: "7 jours" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByText("7D")).not.toBeInTheDocument();
  });

  it("hides the custom dates unless the period is CUSTOM", () => {
    renderSelector("30D");
    expect(screen.queryByLabelText("Du")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Au")).not.toBeInTheDocument();
  });

  it("shows the custom dates for CUSTOM and forwards changes", () => {
    const props = renderSelector("CUSTOM", "2026-01-01", "2026-01-31");
    const from = screen.getByLabelText("Du");
    expect(from).toHaveValue("2026-01-01");
    expect(screen.getByLabelText("Au")).toHaveValue("2026-01-31");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    fireEvent.change(from, { target: { value: "2026-01-05" } });
    expect(props.onCustomFromChange).toHaveBeenLastCalledWith("2026-01-05");
  });

  it("calls onPeriodChange with the code", async () => {
    const props = renderSelector("7D");
    await userEvent.click(screen.getByRole("button", { name: "90 jours" }));
    expect(props.onPeriodChange).toHaveBeenCalledWith("90D");
  });

  it("shows an inline error for an inverted custom range", () => {
    renderSelector("CUSTOM", "2026-02-01", "2026-01-01");
    expect(screen.getByRole("alert")).toHaveTextContent("La date de début doit précéder la date de fin.");
  });

  it("shows an inline error for a range over 366 days", () => {
    renderSelector("CUSTOM", "2024-01-01", "2025-01-01");
    expect(screen.getByRole("alert")).toHaveTextContent("366 jours");
  });

  it("asks for both dates while incomplete", () => {
    renderSelector("CUSTOM", "2026-01-01", "");
    expect(screen.getByRole("alert")).toHaveTextContent("Choisissez une date de début et une date de fin.");
  });
});
