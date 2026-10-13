import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { TenantActivityTable } from "@/components/operations/TenantActivityTable";
import type { AdminTenantActivityPage, TenantActivityRow } from "@/types/operations";

const TENANT_A = "3f2b1c4d-0000-4000-8000-00000000000a";
const TENANT_B = "3f2b1c4d-0000-4000-8000-00000000000b";

function row(overrides: Partial<TenantActivityRow>): TenantActivityRow {
  return {
    tenantId: TENANT_A,
    tenantName: "Boutique Awa",
    plan: "PREMIUM_TRIAL",
    gmv: 150000,
    orderCount: 12,
    lastActivityAt: null,
    storeCount: 2,
    ...overrides,
  };
}

function pageOf(rows: TenantActivityRow[], totalPages = 1, number = 0): AdminTenantActivityPage {
  return { content: rows, totalElements: rows.length + (totalPages - 1) * 25, totalPages, number };
}

const DATA = pageOf([
  row({ tenantId: TENANT_A, tenantName: "Boutique Awa", plan: "PREMIUM_TRIAL" }),
  row({ tenantId: TENANT_B, tenantName: null, plan: "FREE", gmv: 0, orderCount: 0 }),
]);

describe("TenantActivityTable", () => {
  it("shows skeleton rows while loading", () => {
    const { container } = render(
      <TenantActivityTable data={undefined} isLoading page={0} setPage={vi.fn()} />,
    );
    expect(container.querySelectorAll("tr.animate-pulse")).toHaveLength(5);
  });

  it("renders names, « Sans nom », French plan labels, and never ids or raw codes", () => {
    const { container } = render(
      <TenantActivityTable data={DATA} isLoading={false} page={0} setPage={vi.fn()} />,
    );
    expect(screen.getByText("Boutique Awa")).toBeInTheDocument();
    expect(screen.getByText("Sans nom")).toBeInTheDocument();
    expect(screen.getByText("Essai Premium")).toBeInTheDocument();
    expect(screen.getByText("Gratuit")).toBeInTheDocument();
    expect(screen.getAllByText("Jamais")).toHaveLength(2);

    const text = container.textContent ?? "";
    expect(text).not.toContain(TENANT_A);
    expect(text).not.toContain(TENANT_B);
    expect(text).not.toMatch(/PREMIUM_TRIAL|\bFREE\b/);
  });

  it("shows the empty state", () => {
    render(<TenantActivityTable data={pageOf([])} isLoading={false} page={0} setPage={vi.fn()} />);
    expect(screen.getByText("Aucune entreprise")).toBeInTheDocument();
  });

  it("paginates with Précédent / Suivant", async () => {
    const setPage = vi.fn();
    render(
      <TenantActivityTable data={pageOf([row({})], 3, 1)} isLoading={false} page={1} setPage={setPage} />,
    );
    expect(screen.getByText("Page 2 / 3")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Suivant →" }));
    expect(setPage).toHaveBeenLastCalledWith(2);
    await userEvent.click(screen.getByRole("button", { name: "← Précédent" }));
    expect(setPage).toHaveBeenLastCalledWith(0);
  });

  it("disables Suivant on the last page and Précédent on the first", () => {
    const { rerender } = render(
      <TenantActivityTable data={pageOf([row({})], 2, 1)} isLoading={false} page={1} setPage={vi.fn()} />,
    );
    expect(screen.getByRole("button", { name: "Suivant →" })).toBeDisabled();
    rerender(
      <TenantActivityTable data={pageOf([row({})], 2, 0)} isLoading={false} page={0} setPage={vi.fn()} />,
    );
    expect(screen.getByRole("button", { name: "← Précédent" })).toBeDisabled();
  });

  it("hides pagination for a single page", () => {
    render(<TenantActivityTable data={DATA} isLoading={false} page={0} setPage={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Suivant →" })).not.toBeInTheDocument();
  });

  it("keeps cached rows and warns when a refresh fails", async () => {
    const onRetry = vi.fn();
    render(
      <TenantActivityTable data={DATA} isLoading={false} isError onRetry={onRetry} page={0} setPage={vi.fn()} />,
    );
    expect(screen.getByText("Boutique Awa")).toBeInTheDocument();
    expect(screen.getByText("Échec de l'actualisation — données possiblement obsolètes")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("asks for a valid period instead of an empty state while the custom range is incomplete", () => {
    render(
      <TenantActivityTable data={undefined} isLoading={false} rangeIncomplete page={0} setPage={vi.fn()} />,
    );
    expect(screen.getByText("Choisissez une période valide pour afficher les données")).toBeInTheDocument();
    expect(screen.queryByText("Aucune entreprise")).not.toBeInTheDocument();
  });

  it("shows a load error with retry on first-load failure", async () => {
    const onRetry = vi.fn();
    render(
      <TenantActivityTable data={undefined} isLoading={false} isError onRetry={onRetry} page={0} setPage={vi.fn()} />,
    );
    expect(screen.getByText("Erreur de chargement")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
