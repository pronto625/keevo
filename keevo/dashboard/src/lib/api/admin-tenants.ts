import client from "./client";
import type {
  ActivatePremiumInput,
  PlanAlert,
  PlanState,
  TenantDetail,
  TenantFilters,
  TenantPage,
  TenantPayment,
} from "@/types/tenant";

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

// ── Story 9-2 — plan & payments ───────────────────────────────────────────────

export async function activatePremium(
  tenantId: string,
  input: ActivatePremiumInput
): Promise<PlanState> {
  const res = await client.post<{ data: PlanState }>(
    `/admin/tenants/${tenantId}/plan/premium`,
    input
  );
  return res.data.data;
}

export async function downgradeToFree(tenantId: string, reason: string): Promise<void> {
  await client.post(`/admin/tenants/${tenantId}/plan/free`, { reason: reason || null });
}

export async function suspendTenant(tenantId: string, reason: string): Promise<void> {
  await client.post(`/admin/tenants/${tenantId}/suspend`, { reason });
}

export async function reactivateTenant(tenantId: string): Promise<void> {
  await client.post(`/admin/tenants/${tenantId}/reactivate`);
}

export async function getTenantPayments(tenantId: string): Promise<TenantPayment[]> {
  const res = await client.get<{ data: TenantPayment[] }>(`/admin/tenants/${tenantId}/payments`);
  return res.data.data;
}

export async function voidPayment(tenantId: string, paymentId: string, reason: string): Promise<void> {
  await client.post(`/admin/tenants/${tenantId}/payments/${paymentId}/void`, { reason });
}

export async function getPlanAlerts(): Promise<PlanAlert[]> {
  const res = await client.get<{ data: PlanAlert[] }>("/admin/subscriptions/alerts");
  return res.data.data;
}

/** French message of a failed admin call (`error` of the backend error body), else a generic one. */
export function apiErrorMessage(err: unknown, fallback = "Une erreur est survenue"): string {
  const data = (err as { response?: { data?: { error?: unknown; domainCode?: unknown } } })?.response?.data;
  // Bean-validation answers carry an English `error` (« Validation failed »): never shown.
  if (data?.domainCode === "VALIDATION_ERROR") return "Données invalides, vérifiez le formulaire";
  return typeof data?.error === "string" && data.error.trim() ? data.error : fallback;
}

/** Field errors (`details`) of a 422 VALIDATION_ERROR answer, keyed by request field. */
export function apiFieldErrors(err: unknown): Record<string, string> {
  const data = (err as { response?: { data?: { domainCode?: unknown; details?: unknown } } })
    ?.response?.data;
  if (data?.domainCode !== "VALIDATION_ERROR" || !data.details || typeof data.details !== "object") {
    return {};
  }
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(data.details as Record<string, unknown>)) {
    if (typeof v === "string") out[k] = v;
  }
  return out;
}
