import client from "./client";
import type { HealthStatus, SystemHealth } from "@/types/health";

/** GET /admin/health — live metrics with their thresholds (« Santé système »). */
export async function getSystemHealth(): Promise<SystemHealth> {
  const res = await client.get<{ data: SystemHealth }>("/admin/health");
  return res.data.data;
}

/** GET /admin/health/status — incident flag of the sticky banner. */
export async function getHealthStatus(): Promise<HealthStatus> {
  const res = await client.get<{ data: HealthStatus }>("/admin/health/status");
  return res.data.data ?? { incident: false, breachedMetrics: [] };
}
