import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { JobProgress, jobPercent } from "@/components/modules/JobProgress";
import { getJob } from "@/lib/api/admin-jobs";
import type { AdminJob } from "@/types/modules";

vi.mock("@/lib/api/admin-jobs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/admin-jobs")>();
  return { ...actual, getJob: vi.fn() };
});

const BASE: AdminJob = {
  id: "job-1", kind: "MODULE_BACKFILL", label: "Migration des modules", params: { dryRun: true },
  status: "RUNNING", processed: 141, total: 282, report: null, createdAt: "2026-11-01T10:00:00Z",
  startedAt: "2026-11-01T10:00:01Z", finishedAt: null, error: null,
};

function renderJob(onFinished = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <JobProgress jobId="job-1" onFinished={onFinished} />
    </QueryClientProvider>,
  );
  return onFinished;
}

describe("JobProgress (Story 16.4)", () => {
  // mocks are restored between tests (vitest restoreMocks); a mockReset here would surface the rejected reads
  // of the polling test as a test failure

  it("percent: processed / total, 100 once succeeded, 0 while the total is unknown", () => {
    expect(jobPercent({ processed: 141, total: 282, status: "RUNNING" })).toBe(50);
    expect(jobPercent({ processed: 0, total: null, status: "QUEUED" })).toBe(0);
    expect(jobPercent({ processed: 3, total: 3, status: "SUCCEEDED" })).toBe(100);
  });

  it("running: label, « En cours », progress bar and « x / y comptes »", async () => {
    vi.mocked(getJob).mockResolvedValue(BASE);
    renderJob();
    expect(await screen.findByText("141 / 282 comptes")).toBeInTheDocument();
    expect(screen.getByText("En cours")).toBeInTheDocument();
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "50");
  });

  it("succeeded: the backfill report in French, tenants by code; onFinished called once", async () => {
    vi.mocked(getJob).mockResolvedValue({
      ...BASE, status: "SUCCEEDED", processed: 282, finishedAt: "2026-11-01T10:02:00Z",
      report: { dryRun: true, tenantsProcessed: 280, tenantsSkipped: 2, rowsInserted: 8, rowsUpdated: 0,
        rowsUnchanged: 100, rowsPreserved: 1, modulesProvisioned: 1,
        tenants: [{ tenantCode: "KV-AAA111", inserted: 8, updated: 0, provisioned: ["Facturation"] }],
        failures: [{ tenantCode: "KV-BBB222", reason: "Abonnement illisible : modules laissés inchangés" }] },
    });
    const onFinished = renderJob();
    expect(await screen.findByText("Simulation : rien n'a été modifié.")).toBeInTheDocument();
    expect(screen.getByText("280 comptes traités")).toBeInTheDocument();
    expect(screen.getByText("8 modules ajoutés")).toBeInTheDocument();
    expect(screen.getByText(/Abonnement illisible/)).toBeInTheDocument();
    expect(screen.getByText("Terminée")).toBeInTheDocument();
    expect(onFinished).toHaveBeenCalledTimes(1);
  });

  it("failed: the French error, no progress bar", async () => {
    vi.mocked(getJob).mockResolvedValue({ ...BASE, status: "FAILED", error: "Interrompu par un redémarrage" });
    renderJob();
    expect(await screen.findByRole("alert")).toHaveTextContent("Interrompu par un redémarrage");
    expect(screen.queryByRole("progressbar")).toBeNull();
    expect(screen.getByText("Échouée")).toBeInTheDocument();
  });

  it("provisioning report: provisioned / skipped counts", async () => {
    vi.mocked(getJob).mockResolvedValue({
      ...BASE, kind: "MODULE_PROVISION_ALL", label: "Provisionnement d'un module", status: "SUCCEEDED",
      report: { moduleId: "invoicing", provisioned: ["KV-AAA111"], skipped: ["KV-BBB222", "KV-CCC333"], failures: [],
        upToDate: 5 },
    });
    renderJob();
    expect(await screen.findByText("1 compte provisionné")).toBeInTheDocument();
    expect(screen.getByText(/2 comptes ignorés/)).toBeInTheDocument();
    expect(screen.getByText("5 comptes déjà à jour")).toBeInTheDocument();
  });

  it("stops polling after 3 failed reads and shows the French error", async () => {
    vi.mocked(getJob).mockImplementation(() => Promise.reject(new Error("network")));
    renderJob();
    await waitFor(() => expect(vi.mocked(getJob)).toHaveBeenCalledTimes(3), { timeout: 4000 });
    expect(await screen.findByRole("alert")).toHaveTextContent("Impossible de suivre la tâche");
    await new Promise((r) => setTimeout(r, 1500));
    expect(vi.mocked(getJob)).toHaveBeenCalledTimes(3);
  }, 10_000);

  it("a finished job refreshes the profiles, catalogue and tenants' modules", async () => {
    vi.mocked(getJob).mockResolvedValue({ ...BASE, status: "SUCCEEDED", report: null });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(client, "invalidateQueries");
    render(
      <QueryClientProvider client={client}>
        <JobProgress jobId="job-1" />
      </QueryClientProvider>,
    );
    await screen.findByText("Terminée");
    const keys = spy.mock.calls.map((c) => (c[0] as { queryKey: string[] }).queryKey[0]);
    expect(keys).toEqual(expect.arrayContaining(["plan-profiles", "module-catalogue", "tenant-modules"]));
  });
});
