import client from "./client";
import type { TenantDetail, TenantFilters, TenantPage } from "@/types/tenant";

export async function getTenants(
  filters: TenantFilters,
  page: number
): Promise<TenantPage> {
  const params: Record<string, string | number> = { page, pageSize: 25 };
  if (filters.search) params.search = filters.search;
  if (filters.plan !== "ALL") params.plan = filters.plan;
  if (filters.status !== "ALL") params.status = filters.status;
  if (filters.registeredFrom) params.registeredFrom = filters.registeredFrom;
  if (filters.registeredTo) params.registeredTo = filters.registeredTo;
  if (filters.lastActivityFrom) params.lastActivityFrom = filters.lastActivityFrom;
  if (filters.lastActivityTo) params.lastActivityTo = filters.lastActivityTo;

  const res = await client.get<{ data: TenantPage }>("/admin/tenants", { params });
  return res.data.data;
}

export async function getTenantDetail(
  tenantId: string,
  auditPage = 0
): Promise<TenantDetail> {
  const res = await client.get<{ data: TenantDetail }>(
    `/admin/tenants/${tenantId}/detail`,
    { params: { auditPage } }
  );
  return res.data.data;
}

export async function cancelDeletion(tenantId: string): Promise<void> {
  await client.post(`/admin/tenants/${tenantId}/cancel-deletion`);
}

export async function forceDelete(tenantId: string): Promise<void> {
  await client.post(`/admin/tenants/${tenantId}/force-delete`);
}
