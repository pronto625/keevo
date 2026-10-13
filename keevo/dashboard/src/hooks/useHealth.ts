"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getHealthStatus, getSystemHealth } from "@/lib/api/admin-health";
import type { HealthStatus, SystemHealth } from "@/types/health";

export const HEALTH_KEY = ["admin-health"] as const;
export const HEALTH_STATUS_KEY = ["admin-health-status"] as const;

/** « Santé système » page: live metrics, « Actualiser » refetches both health queries. */
export function useSystemHealth() {
  const queryClient = useQueryClient();
  const query = useQuery<SystemHealth>({
    queryKey: HEALTH_KEY,
    queryFn: getSystemHealth,
    refetchInterval: 60_000,
  });
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: HEALTH_KEY });
    queryClient.invalidateQueries({ queryKey: HEALTH_STATUS_KEY });
  };
  return { query, refresh };
}

/**
 * Incident flag of the sticky banner, polled every 30 s on every admin page (server cache ≤ 30 s:
 * a breach shows within 60 s). Two retries before the status counts as unreachable.
 */
export function useHealthStatus() {
  return useQuery<HealthStatus>({
    queryKey: HEALTH_STATUS_KEY,
    queryFn: getHealthStatus,
    staleTime: 15_000,
    refetchInterval: 30_000,
    retry: 2,
  });
}
