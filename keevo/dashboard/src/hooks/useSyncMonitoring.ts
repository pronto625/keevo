"use client";

import { useCallback, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getSyncOverview, getTenantSyncHealth } from "@/lib/api/admin-sync";

export const SYNC_OVERVIEW_KEY = ["admin-sync-overview"] as const;
export const SYNC_HEALTH_KEY = ["admin-sync-health"] as const;
export const SYNC_TENANT_DETAIL_KEY = "admin-sync-tenant-detail";

export function useSyncMonitoring() {
  const queryClient = useQueryClient();
  const [selectedTenantId, setSelectedTenantId] = useState<string | null>(null);

  const overviewQuery = useQuery({
    queryKey: SYNC_OVERVIEW_KEY,
    queryFn: getSyncOverview,
    staleTime: 30_000,
  });

  const healthQuery = useQuery({
    queryKey: SYNC_HEALTH_KEY,
    queryFn: getTenantSyncHealth,
    staleTime: 30_000,
  });

  const refresh = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: SYNC_OVERVIEW_KEY });
    queryClient.invalidateQueries({ queryKey: SYNC_HEALTH_KEY });
    queryClient.invalidateQueries({ queryKey: [SYNC_TENANT_DETAIL_KEY] });
  }, [queryClient]);

  return {
    overviewQuery,
    healthQuery,
    selectedTenantId,
    setSelectedTenantId,
    refresh,
  };
}
