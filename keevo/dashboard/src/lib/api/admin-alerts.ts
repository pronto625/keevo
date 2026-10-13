import client from "./client";
import type { AdminAlert } from "@/types/alerts";

/** GET /admin/alerts — newest first; handled alerts only with `includeHandled`. */
export async function getAlerts(includeHandled: boolean): Promise<AdminAlert[]> {
  const res = await client.get<{ data: AdminAlert[] }>("/admin/alerts", {
    params: { includeHandled: String(includeHandled) },
  });
  return res.data.data ?? [];
}

/** GET /admin/alerts/count?severity=HIGH — unhandled HIGH alerts of the sidebar badge (cached server-side). */
export async function getUnhandledHighAlertCount(): Promise<number> {
  const res = await client.get<{ data: number }>("/admin/alerts/count", { params: { severity: "HIGH" } });
  return Number(res.data.data) || 0;
}

/** POST /admin/alerts/{key}/handle — idempotent, 204. */
export async function markAlertHandled(key: string): Promise<void> {
  await client.post(`/admin/alerts/${encodeURIComponent(key)}/handle`);
}
