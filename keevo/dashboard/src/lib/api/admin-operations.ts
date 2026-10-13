import client from "./client";
import type {
  AdminOperationsOverview,
  AdminTenantActivityPage,
  GmvTrendPoint,
  InactiveTenant,
  PeriodParams,
} from "@/types/operations";

export const TENANT_ACTIVITY_PAGE_SIZE = 25;

function periodQuery(p: PeriodParams): Record<string, string> {
  const params: Record<string, string> = { period: p.period };
  if (p.period === "CUSTOM") {
    if (p.from) params.from = p.from;
    if (p.to) params.to = p.to;
  }
  return params;
}

export async function getOperationsOverview(
  p: PeriodParams
): Promise<AdminOperationsOverview> {
  const res = await client.get<{ data: AdminOperationsOverview }>(
    "/admin/operations/overview",
    { params: periodQuery(p) }
  );
  return res.data.data;
}

export async function getGmvTrend(p: PeriodParams): Promise<GmvTrendPoint[]> {
  const res = await client.get<{ data: GmvTrendPoint[] }>(
    "/admin/operations/trend",
    { params: periodQuery(p) }
  );
  return res.data.data;
}

export async function getTenantActivity(
  p: PeriodParams,
  page: number
): Promise<AdminTenantActivityPage> {
  const res = await client.get<{ data: AdminTenantActivityPage }>(
    "/admin/operations/tenants",
    { params: { ...periodQuery(p), page, pageSize: TENANT_ACTIVITY_PAGE_SIZE } }
  );
  return res.data.data;
}

export async function getInactiveTenants(): Promise<InactiveTenant[]> {
  const res = await client.get<{ data: InactiveTenant[] }>(
    "/admin/operations/inactive-tenants"
  );
  return res.data.data;
}
