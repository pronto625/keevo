import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { TenantFiltersPanel } from "@/components/tenants/TenantFilters";
import { TenantFilterChips } from "@/components/tenants/TenantFilterChips";
import type { TenantFilters } from "@/types/tenant";

const BASE: TenantFilters = {
  search: "",
  plan: "ALL",
  status: "ALL",
  registeredFrom: null,
  registeredTo: null,
  lastActivityFrom: null,
  lastActivityTo: null,
};

describe("Tenant filters (spec 9-1 completion)", () => {
  it("plan select tells Essai Premium from Premium, with French labels only", async () => {
    const onUpdate = vi.fn();
    render(<TenantFiltersPanel filters={BASE} onUpdate={onUpdate} />);

    const plan = screen.getByLabelText("Plan");
    expect(within(plan).getAllByRole("option").map((o) => o.textContent))
      .toEqual(["Tous", "Gratuit", "Essai Premium", "Premium"]);
    await userEvent.selectOptions(plan, "PREMIUM_TRIAL");
    expect(onUpdate).toHaveBeenCalledWith("plan", "PREMIUM_TRIAL");

    const status = screen.getByLabelText("Statut");
    expect(within(status).getAllByRole("option").map((o) => o.textContent))
      .toEqual(["Tous", "Actif", "Suppression planifiée", "Suspendu"]);
  });

  it("chips use labels and French dates", () => {
    render(
      <TenantFilterChips
        filters={{ ...BASE, plan: "PREMIUM_TRIAL", status: "DELETION_PENDING", registeredFrom: "2026-10-01" }}
        onClear={vi.fn()}
      />,
    );
    expect(screen.getByText("Plan : Essai Premium")).toBeInTheDocument();
    expect(screen.getByText("Statut : Suppression planifiée")).toBeInTheDocument();
    expect(screen.getByText("Inscrite depuis le 01/10/2026")).toBeInTheDocument();
  });

  it("legacy PAID chip reads « Premium et essai »", () => {
    render(<TenantFilterChips filters={{ ...BASE, plan: "PAID" }} onClear={vi.fn()} />);
    expect(screen.getByText("Plan : Premium et essai")).toBeInTheDocument();
  });
});
