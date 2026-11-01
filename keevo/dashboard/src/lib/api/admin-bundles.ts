// Story 16.4 — super-admin packs (Story 16.2 endpoints).
import client from "./client";
import type { ActivationInput, Bundle, BundleActivation, BundleInput } from "@/types/modules";

export async function getBundles(): Promise<Bundle[]> {
  const res = await client.get<{ data: Bundle[] }>("/admin/bundles");
  return res.data.data;
}

/** Creates or replaces a pack (every field sent, description may be empty). */
export async function saveBundle(bundleId: string, input: BundleInput): Promise<Bundle> {
  const res = await client.put<{ data: Bundle }>(`/admin/bundles/${bundleId}`, {
    name: input.name.trim(),
    description: input.description.trim() || null,
    modules: input.modules,
  });
  return res.data.data;
}

export async function deleteBundle(bundleId: string): Promise<void> {
  await client.delete(`/admin/bundles/${bundleId}`);
}

export async function applyBundle(
  tenantId: string,
  bundleId: string,
  input: ActivationInput,
): Promise<BundleActivation> {
  const res = await client.post<{ data: BundleActivation }>(`/admin/tenants/${tenantId}/bundles/${bundleId}`, {
    status: input.status,
    expiresAt: input.expiresAt || null,
  });
  return res.data.data;
}
