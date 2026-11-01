import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import PlansPageClient from "@/app/plans/PlansPageClient";
import { getPlanProfiles, updatePlanProfile } from "@/lib/api/admin-plans";
import { getModuleCatalogue } from "@/lib/api/admin-modules";
import { getJob, getJobs } from "@/lib/api/admin-jobs";
import type { AdminJob, PlanProfile } from "@/types/modules";

vi.mock("@/lib/api/admin-plans", () => ({ getPlanProfiles: vi.fn(), updatePlanProfile: vi.fn() }));
vi.mock("@/lib/api/admin-modules", () => ({ getModuleCatalogue: vi.fn() }));
vi.mock("@/lib/api/admin-jobs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/admin-jobs")>();
  return { ...actual, getJob: vi.fn(), getJobs: vi.fn() };
});
vi.mock("@/components/layout/AdminLayout", () => ({
  AdminLayout: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

const FREE: PlanProfile = { planType: "FREE", label: "Gratuit", maxStores: 1, maxProducts: 500, maxEmployees: 2,
  modules: [], fallback: false };
const RUNNING: AdminJob = { id: "job-r", kind: "PROFILE_RESYNC", label: "Mise à jour des comptes d'une offre",
  params: { planType: "FREE", plan: "Gratuit" }, status: "RUNNING", processed: 140, total: 282, report: null,
  createdAt: "2026-11-01T10:00:00Z", startedAt: null, finishedAt: null, error: null };

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PlansPageClient />
    </QueryClientProvider>,
  );
}

function setUp() {
  vi.mocked(getPlanProfiles).mockResolvedValue([FREE]);
  vi.mocked(getModuleCatalogue).mockResolvedValue([
    { moduleId: "exports", displayName: "Exports PDF et Excel", category: "premium", free: false, version: "1.1.0",
      requires: [], migrations: false },
  ]);
  vi.mocked(getJobs).mockResolvedValue([]);
  vi.mocked(getJob).mockImplementation(async (id) => ({ ...RUNNING, id }));
}

describe("PlansPageClient (Story 16.4)", () => {
  it("a composition change asks for confirmation, then the re-sync job's progress is shown", async () => {
    setUp();
    vi.mocked(updatePlanProfile).mockResolvedValue({ profile: { ...FREE, modules: [{ id: "exports",
      displayName: "Exports PDF et Excel" }] }, compositionChanged: true, jobId: "job-r" });
    const user = userEvent.setup();
    renderPage();
    const form = await screen.findByRole("form", { name: "Offre Gratuit" });
    await user.click(within(form).getByRole("checkbox", { name: "Exports PDF et Excel" }));
    await user.click(within(form).getByRole("button", { name: "Enregistrer" }));
    expect(updatePlanProfile).not.toHaveBeenCalled();
    await user.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Continuer" }));

    await waitFor(() => expect(updatePlanProfile).toHaveBeenCalledWith("FREE",
      { maxStores: 1, maxProducts: 500, maxEmployees: 2, modules: ["exports"] }));
    expect(await screen.findByText("140 / 282 comptes")).toBeInTheDocument();
    expect(getJob).toHaveBeenCalledWith("job-r");
  });

  it("409 while a re-sync runs: the French message on the card and the running job shown", async () => {
    setUp();
    vi.mocked(updatePlanProfile).mockRejectedValue({ response: { data: {
      error: "« Mise à jour des comptes d'une offre » est déjà en cours. Attendez la fin de la tâche avant d'en relancer une.",
      domainCode: "ADMIN_JOB_RUNNING" } } });
    const user = userEvent.setup();
    renderPage();
    const form = await screen.findByRole("form", { name: "Offre Gratuit" });
    vi.mocked(getJobs).mockResolvedValue([RUNNING]);
    await user.click(within(form).getByRole("button", { name: "Enregistrer" }));

    expect(await within(form).findByRole("alert")).toHaveTextContent("est déjà en cours");
    expect(await screen.findByText("140 / 282 comptes")).toBeInTheDocument();
  });

  it("the latest re-sync job is shown when the page opens", async () => {
    setUp();
    vi.mocked(getJobs).mockResolvedValue([RUNNING]);
    renderPage();
    expect(await screen.findByText("140 / 282 comptes")).toBeInTheDocument();
  });
});
