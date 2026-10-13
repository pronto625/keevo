"use client";

import { useCallback, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getGmvTrend,
  getInactiveTenants,
  getOperationsOverview,
  getTenantActivity,
} from "@/lib/api/admin-operations";
import { customRangeError } from "@/lib/period";
import type { Period, PeriodParams } from "@/types/operations";

export const OPS_OVERVIEW_KEY = "admin-operations-overview";
export const OPS_TREND_KEY = "admin-operations-trend";
export const OPS_TENANTS_KEY = "admin-operations-tenants";
export const OPS_INACTIVE_KEY = ["admin-operations-inactive"] as const;

export function useOperations() {
  const queryClient = useQueryClient();
  const [period, setPeriod] = useState<Period>("7D");
  const [customFrom, setCustomFromState] = useState("");
  const [customTo, setCustomToState] = useState("");
  const [page, setPage] = useState(0);

  const isCustom = period === "CUSTOM";
  // An incomplete or invalid custom range is never sent (the selector shows the error inline,
  // the period-dependent sections show « Choisissez une période valide… »).
  const rangeIncomplete = isCustom && customRangeError(customFrom, customTo) !== null;
  const enabled = !rangeIncomplete;
  const params: PeriodParams = isCustom
    ? { period, from: customFrom, to: customTo }
    : { period };

  // Keeps the previous period's data on screen while the next one loads (no flash on period or page
  // change) — but never while the custom range is invalid, where no query runs.
  const keepWhileValid = <T,>(prev: T | undefined) => (enabled ? prev : undefined);

  const overviewQuery = useQuery({
    queryKey: [OPS_OVERVIEW_KEY, params],
    queryFn: () => getOperationsOverview(params),
    enabled,
    placeholderData: keepWhileValid,
  });

  const trendQuery = useQuery({
    queryKey: [OPS_TREND_KEY, params],
    queryFn: () => getGmvTrend(params),
    enabled,
    placeholderData: keepWhileValid,
  });

  const activityQuery = useQuery({
    queryKey: [OPS_TENANTS_KEY, params, page],
    queryFn: async () => {
      const result = await getTenantActivity(params, page);
      // A refresh can return fewer pages than the current one (« Page 4 / 3 »): clamp to the
      // last page when the data arrives (refetches that page).
      const last = Math.max(0, result.totalPages - 1);
      if (page > last) setPage(last);
      return result;
    },
    enabled,
    placeholderData: keepWhileValid,
  });

  const inactiveQuery = useQuery({
    queryKey: OPS_INACTIVE_KEY,
    queryFn: getInactiveTenants,
    staleTime: 60_000,
  });

  const setCustomFrom = useCallback((value: string) => {
    setCustomFromState(value);
    setPage(0);
  }, []);

  const setCustomTo = useCallback((value: string) => {
    setCustomToState(value);
    setPage(0);
  }, []);

  const refresh = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: [OPS_OVERVIEW_KEY] });
    queryClient.invalidateQueries({ queryKey: [OPS_TREND_KEY] });
    queryClient.invalidateQueries({ queryKey: [OPS_TENANTS_KEY] });
    queryClient.invalidateQueries({ queryKey: OPS_INACTIVE_KEY });
  }, [queryClient]);

  return {
    overviewQuery,
    activityQuery,
    trendQuery,
    inactiveQuery,
    period,
    setPeriod,
    customFrom,
    setCustomFrom,
    customTo,
    setCustomTo,
    page,
    setPage,
    refresh,
    rangeIncomplete,
  };
}
