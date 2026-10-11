import client from "./client";
import type {
  AdminSyncOverview,
  AdminSyncTenantDetail,
  AdminTenantSyncHealth,
} from "@/types/sync-monitoring";

export async function getSyncOverview(): Promise<AdminSyncOverview> {
  const res = await client.get<{ data: AdminSyncOverview }>(
    "/admin/sync/overview"
  );
  return res.data.data;
}

export async function getTenantSyncHealth(): Promise<AdminTenantSyncHealth[]> {
  const res = await client.get<{ data: AdminTenantSyncHealth[] }>(
    "/admin/sync/tenants"
  );
  return res.data.data;
}

export async function getTenantSyncDetail(
  tenantId: string
): Promise<AdminSyncTenantDetail> {
  const res = await client.get<{ data: AdminSyncTenantDetail }>(
    `/admin/sync/tenants/${encodeURIComponent(tenantId)}/detail`
  );
  return res.data.data;
}
