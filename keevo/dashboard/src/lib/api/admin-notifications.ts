import client from "./client";
import type {
  GlobalNotification,
  GlobalNotificationInput,
  GlobalNotificationScheduled,
  GlobalNotificationTarget,
  PickedTenant,
} from "@/types/global-notification";

const BASE = "/admin/notifications/global";

/** POST /admin/notifications/global — stored as SCHEDULED (sent by the server within a minute when due). */
export async function scheduleGlobalNotification(
  input: GlobalNotificationInput,
): Promise<GlobalNotificationScheduled> {
  const res = await client.post<{ data: GlobalNotificationScheduled }>(BASE, input);
  return res.data.data;
}

/** GET /admin/notifications/global — last 90 days, newest first. */
export async function getGlobalNotifications(): Promise<GlobalNotification[]> {
  const res = await client.get<{ data: GlobalNotification[] }>(BASE);
  return res.data.data ?? [];
}

/** POST /admin/notifications/global/{id}/cancel — only while scheduled (409 otherwise). */
export async function cancelGlobalNotification(id: string): Promise<void> {
  await client.post(`${BASE}/${encodeURIComponent(id)}/cancel`);
}

/** GET /admin/notifications/global/estimate — devices the target currently reaches. */
export async function getRecipientEstimate(
  target: GlobalNotificationTarget,
  targetTenantId: string | null,
): Promise<number> {
  const params: Record<string, string> = { target };
  if (targetTenantId) params.targetTenantId = targetTenantId;
  const res = await client.get<{ data: number }>(`${BASE}/estimate`, { params });
  return Number(res.data.data) || 0;
}

/** GET /admin/notifications/global/tenants — reachable companies whose name matches (at most 20). */
export async function searchPushTenants(search: string): Promise<PickedTenant[]> {
  const res = await client.get<{ data: PickedTenant[] }>(`${BASE}/tenants`, { params: { search } });
  return (res.data.data ?? []).map((t) => ({ id: t.id, name: t.name }));
}
