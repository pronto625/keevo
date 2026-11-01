import { afterEach, describe, expect, it, vi } from "vitest";
import client from "@/lib/api/client";
import {
  activateModule,
  deactivateModule,
  getModuleHistory,
  getTenantModules,
  startBackfill,
  startProvisionAll,
} from "@/lib/api/admin-modules";
import { applyBundle, deleteBundle, saveBundle } from "@/lib/api/admin-bundles";
import { updatePlanProfile } from "@/lib/api/admin-plans";
import { getJob, getJobs, isJobRunning } from "@/lib/api/admin-jobs";
import { consoleErrorMessage, dependentsOf, isDeactivationFailure } from "@/lib/api/console-errors";

vi.mock("@/lib/api/client", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const get = vi.mocked(client.get);
const post = vi.mocked(client.post);
const put = vi.mocked(client.put);
const del = vi.mocked(client.delete);
const T = "3f2b1c4d-0000-4000-8000-0000000000aa";

describe("admin modules / packs / plans / jobs API (Story 16.4)", () => {
  afterEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it("reads the tenant's modules and history, unwrapping data", async () => {
    get.mockResolvedValue({ data: { data: [{ moduleId: "invoicing" }] } });
    await expect(getTenantModules(T)).resolves.toEqual([{ moduleId: "invoicing" }]);
    expect(get).toHaveBeenLastCalledWith(`/admin/tenants/${T}/modules`);
    await getModuleHistory(T);
    expect(get).toHaveBeenLastCalledWith(`/admin/tenants/${T}/modules/history`);
  });

  it("activation: a paid module sends status + date (null when empty); a free one no body", async () => {
    post.mockResolvedValue({ data: { data: { activated: [] } } });
    await activateModule(T, "invoicing", { status: "TRIAL", expiresAt: "" });
    expect(post).toHaveBeenLastCalledWith(`/admin/tenants/${T}/modules/invoicing/activate`, {
      status: "TRIAL",
      expiresAt: null,
    });
    await activateModule(T, "crm", null);
    expect(post).toHaveBeenLastCalledWith(`/admin/tenants/${T}/modules/crm/activate`, undefined);
  });

  it("deactivation sends cascade / force only when asked", async () => {
    post.mockResolvedValue({ data: { data: { deactivated: [], forced: [] } } });
    await deactivateModule(T, "inventory");
    expect(post).toHaveBeenLastCalledWith(`/admin/tenants/${T}/modules/inventory/deactivate`, undefined, {
      params: {},
    });
    await deactivateModule(T, "inventory", { cascade: true, force: true });
    expect(post).toHaveBeenLastCalledWith(`/admin/tenants/${T}/modules/inventory/deactivate`, undefined, {
      params: { cascade: true, force: true },
    });
  });

  it("jobs: backfill (dry run flag) and provisioning return the job id; a job is read by id or by kind", async () => {
    post.mockResolvedValue({ data: { data: { jobId: "job-1" } } });
    await expect(startBackfill(true)).resolves.toBe("job-1");
    expect(post).toHaveBeenLastCalledWith("/admin/modules/backfill", undefined, { params: { dryRun: true } });
    await expect(startProvisionAll("invoicing")).resolves.toBe("job-1");
    expect(post).toHaveBeenLastCalledWith("/admin/modules/invoicing/provision-all", undefined, undefined);
    await startProvisionAll("invoicing", true);
    expect(post).toHaveBeenLastCalledWith("/admin/modules/invoicing/provision-all", undefined, { params: { force: true } });

    get.mockResolvedValue({ data: { data: { id: "job-1", status: "RUNNING" } } });
    await getJob("job-1");
    expect(get).toHaveBeenLastCalledWith("/admin/jobs/job-1");
    get.mockResolvedValue({ data: { data: [] } });
    await getJobs("MODULE_BACKFILL");
    expect(get).toHaveBeenLastCalledWith("/admin/jobs", { params: { kind: "MODULE_BACKFILL" } });
    expect(isJobRunning({ status: "QUEUED" })).toBe(true);
    expect(isJobRunning({ status: "RUNNING" })).toBe(true);
    expect(isJobRunning({ status: "SUCCEEDED" })).toBe(false);
    expect(isJobRunning(null)).toBe(false);
  });

  it("packs: save trims and sends an empty description as null; delete; apply with the terms", async () => {
    put.mockResolvedValue({ data: { data: { id: "pack-pro" } } });
    await saveBundle("pack-pro", { name: "  Pack Pro ", description: "  ", modules: ["pos", "invoicing"] });
    expect(put).toHaveBeenLastCalledWith("/admin/bundles/pack-pro", {
      name: "Pack Pro",
      description: null,
      modules: ["pos", "invoicing"],
    });
    del.mockResolvedValue({});
    await deleteBundle("pack-pro");
    expect(del).toHaveBeenLastCalledWith("/admin/bundles/pack-pro");
    post.mockResolvedValue({ data: { data: { name: "Pack Pro", activated: [], alreadyActive: [] } } });
    await applyBundle(T, "pro", { status: "ACTIVE", expiresAt: "2027-06-30" });
    expect(post).toHaveBeenLastCalledWith(`/admin/tenants/${T}/bundles/pro`, {
      status: "ACTIVE",
      expiresAt: "2027-06-30",
    });
  });

  it("plan profile: the three limit keys are always sent (null = illimité)", async () => {
    put.mockResolvedValue({ data: { data: { profile: {}, compositionChanged: true, jobId: "job-2" } } });
    const r = await updatePlanProfile("FREE", { maxStores: 1, maxProducts: null, maxEmployees: 2, modules: [] });
    expect(r.jobId).toBe("job-2");
    const body = put.mock.calls[0][1] as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(["maxEmployees", "maxProducts", "maxStores", "modules"]);
    expect(body.maxProducts).toBeNull();
  });
});

describe("console errors (Story 16.4)", () => {
  const error = (data: unknown) => ({ response: { data } });

  it("shows the backend's French message; a validation refusal shows its field message", () => {
    expect(consoleErrorMessage(error({ error: "Pack introuvable", domainCode: "BUNDLE_NOT_FOUND" }))).toBe(
      "Pack introuvable",
    );
    expect(
      consoleErrorMessage(
        error({
          error: "Données invalides",
          domainCode: "VALIDATION_ERROR",
          details: { expiresAt: "Une date de fin est requise pour un module payant (« Facturation »)" },
        }),
      ),
    ).toBe("Une date de fin est requise pour un module payant (« Facturation »)");
    expect(consoleErrorMessage(new Error("boom"))).toBe("Une erreur est survenue. Réessayez.");
  });

  it("dependents of a refused deactivation; the forced-deactivation case", () => {
    const refused = error({
      error: "Désactivation impossible : ce module est utilisé par « Inventaire assisté »",
      domainCode: "MODULE_HAS_DEPENDENTS",
      details: { modules: ["Inventaire assisté", "Multi-boutiques et transferts"] },
    });
    expect(dependentsOf(refused)).toEqual(["Inventaire assisté", "Multi-boutiques et transferts"]);
    expect(dependentsOf(error({ domainCode: "MODULE_NOT_FOUND" }))).toBeNull();
    expect(dependentsOf(error({ domainCode: "MODULE_HAS_DEPENDENTS", details: {} }))).toBeNull();
    expect(dependentsOf(error({ domainCode: "MODULE_HAS_DEPENDENTS", details: { modules: [] } }))).toBeNull();
    expect(isDeactivationFailure(error({ domainCode: "MODULE_DEACTIVATION_FAILED" }))).toBe(true);
    expect(isDeactivationFailure(refused)).toBe(false);
  });
});
