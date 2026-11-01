// Story 16.4 — super-admin module endpoints (catalogue, per-tenant activation, history, bulk jobs).
import client from "./client";
import type {
  ActivationInput,
  CatalogueModule,
  DeactivationOptions,
  ModuleActivation,
  ModuleDeactivation,
  ModuleHistoryEntry,
  TenantModule,
} from "@/types/modules";

export async function getModuleCatalogue(): Promise<CatalogueModule[]> {
  const res = await client.get<{ data: CatalogueModule[] }>("/admin/modules");
  return res.data.data;
}

export async function getTenantModules(tenantId: string): Promise<TenantModule[]> {
  const res = await client.get<{ data: TenantModule[] }>(`/admin/tenants/${tenantId}/modules`);
  return res.data.data;
}

/** Activates a module; a free module is sent without status nor date. */
export async function activateModule(
  tenantId: string,
  moduleId: string,
  input: ActivationInput | null,
): Promise<ModuleActivation> {
  const res = await client.post<{ data: ModuleActivation }>(
    `/admin/tenants/${tenantId}/modules/${moduleId}/activate`,
    input ? { status: input.status, expiresAt: input.expiresAt || null } : undefined,
  );
  return res.data.data;
}

export async function deactivateModule(
  tenantId: string,
  moduleId: string,
  options: DeactivationOptions = {},
): Promise<ModuleDeactivation> {
  const params: Record<string, boolean> = {};
  if (options.cascade) params.cascade = true;
  if (options.force) params.force = true;
  const res = await client.post<{ data: ModuleDeactivation }>(
    `/admin/tenants/${tenantId}/modules/${moduleId}/deactivate`,
    undefined,
    { params },
  );
  return res.data.data;
}

export async function getModuleHistory(tenantId: string): Promise<ModuleHistoryEntry[]> {
  const res = await client.get<{ data: ModuleHistoryEntry[] }>(`/admin/tenants/${tenantId}/modules/history`);
  return res.data.data;
}

/** Starts the backfill job (dry run by default); returns the job id. */
export async function startBackfill(dryRun: boolean): Promise<string> {
  const res = await client.post<{ data: { jobId: string } }>("/admin/modules/backfill", undefined, {
    params: { dryRun },
  });
  return res.data.data.jobId;
}

/**
 * Starts the provisioning of one module for every tenant; returns the job id. `force`: every account goes through
 * the migrations again (full check), even those already up to date.
 */
export async function startProvisionAll(moduleId: string, force = false): Promise<string> {
  const res = await client.post<{ data: { jobId: string } }>(
    `/admin/modules/${moduleId}/provision-all`,
    undefined,
    force ? { params: { force: true } } : undefined,
  );
  return res.data.data.jobId;
}
