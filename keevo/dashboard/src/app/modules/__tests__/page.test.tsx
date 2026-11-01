import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import ModulesPageClient from "@/app/modules/ModulesPageClient";
import { getModuleCatalogue, startBackfill, startProvisionAll } from "@/lib/api/admin-modules";
import { getJob, getJobs } from "@/lib/api/admin-jobs";
import type { AdminJob, CatalogueModule } from "@/types/modules";

vi.mock("@/lib/api/admin-modules", () => ({
  getModuleCatalogue: vi.fn(),
  startBackfill: vi.fn(),
  startProvisionAll: vi.fn(),
}));
vi.mock("@/lib/api/admin-jobs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/admin-jobs")>();
  return { ...actual, getJob: vi.fn(), getJobs: vi.fn() };
});
vi.mock("@/components/layout/AdminLayout", () => ({
  AdminLayout: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

const CATALOGUE: CatalogueModule[] = [
  { moduleId: "catalog", displayName: "Catalogue", category: "business", free: true, version: "1.0.0", requires: [], migrations: false },
  { moduleId: "invoicing", displayName: "Facturation", category: "premium", free: false, version: "1.4.0", requires: [], migrations: true },
];

const DONE: AdminJob = {
  id: "job-old", kind: "MODULE_BACKFILL", label: "Migration des modules", params: { dryRun: true }, status: "SUCCEEDED",
  processed: 10, total: 10, createdAt: "2026-11-01T09:00:00Z", startedAt: null, finishedAt: null, error: null,
  report: { dryRun: true, tenantsProcessed: 9, tenantsSkipped: 1, rowsInserted: 0, rowsUpdated: 0, rowsUnchanged: 40,
    rowsPreserved: 0, modulesProvisioned: 0, tenants: [], failures: [] },
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ModulesPageClient />
    </QueryClientProvider>,
  );
}

function setUp(latestBackfill: AdminJob[] = []) {
  vi.mocked(getModuleCatalogue).mockResolvedValue(CATALOGUE);
  vi.mocked(getJobs).mockImplementation(async (kind) => (kind === "MODULE_BACKFILL" ? latestBackfill : []));
  vi.mocked(getJob).mockImplementation(async (id) => ({ ...DONE, id }));
}

describe("ModulesPageClient (Story 16.4)", () => {
  it("catalogue: gratuit / payant, version; « Provisionner » only for modules with tables → startProvisionAll(id)", async () => {
    setUp();
    vi.mocked(startProvisionAll).mockResolvedValue("job-p");
    const user = userEvent.setup();
    renderPage();

    const invoicing = (await screen.findByRole("cell", { name: "Facturation" })).closest("tr") as HTMLElement;
    const catalog = screen.getByRole("cell", { name: "Catalogue" }).closest("tr") as HTMLElement;
    expect(within(invoicing).getByText("Payant")).toBeInTheDocument();
    expect(within(catalog).getByText("Gratuit")).toBeInTheDocument();
    expect(within(catalog).queryByRole("button", { name: "Provisionner" })).toBeNull();
    await user.click(within(invoicing).getByRole("button", { name: "Provisionner" }));
    await waitFor(() => expect(startProvisionAll).toHaveBeenCalledWith("invoicing"));
  });

  it("« Simuler » → dry run; the real run only after « Confirmer »", async () => {
    setUp();
    vi.mocked(startBackfill).mockResolvedValue("job-b");
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("cell", { name: "Facturation" });

    await user.click(screen.getByRole("button", { name: "Simuler" }));
    await waitFor(() => expect(startBackfill).toHaveBeenLastCalledWith(true));
    await user.click(screen.getByRole("button", { name: "Lancer la migration" }));
    expect(startBackfill).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "Confirmer" }));
    await waitFor(() => expect(startBackfill).toHaveBeenLastCalledWith(false));
  });

  it("the latest backfill job is shown when the page opens (reload keeps its report)", async () => {
    setUp([DONE]);
    renderPage();
    expect(await screen.findByText("9 comptes traités")).toBeInTheDocument();
    expect(getJob).toHaveBeenCalledWith("job-old");
  });

  it("409: the French message, and the job already running is shown", async () => {
    setUp();
    vi.mocked(startBackfill).mockRejectedValue({ response: { data: {
      error: "« Migration des modules » est déjà en cours. Attendez la fin de la tâche avant d'en relancer une.",
      domainCode: "ADMIN_JOB_RUNNING" } } });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("cell", { name: "Facturation" });
    vi.mocked(getJobs).mockImplementation(async (kind) => (kind === "MODULE_BACKFILL" ? [{ ...DONE, id: "job-run" }] : []));
    vi.mocked(getJob).mockImplementation(async (id) => ({ ...DONE, id, status: "RUNNING", processed: 3, report: null }));

    await user.click(screen.getByRole("button", { name: "Simuler" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("est déjà en cours");
    expect(await screen.findByText("3 / 10 comptes")).toBeInTheDocument();
    expect(getJob).toHaveBeenCalledWith("job-run");
  });
});
