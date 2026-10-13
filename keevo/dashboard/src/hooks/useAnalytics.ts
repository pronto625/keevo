"use client";

import { useCallback, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getAnalyticsGeography,
  getAnalyticsGrowth,
  getAnalyticsOverview,
  getAnalyticsRetention,
  getFeatureAdoption,
  getTopStores,
} from "@/lib/api/admin-analytics";
import { lastDaysRange, topStoresRangeError } from "@/lib/period";

export const ANALYTICS_KEY = "admin-analytics";
/** The overview may take a few seconds (one query per tenant schema): keep results 60 s. */
const STALE_TIME = 60_000;
export const TOP_STORES_DEFAULT_DAYS = 30;

/** « Vue d'ensemble » tab: KPI cards, growth curves, geography. */
export function useAnalyticsOverview() {
  const overviewQuery = useQuery({
    queryKey: [ANALYTICS_KEY, "overview"],
    queryFn: getAnalyticsOverview,
    staleTime: STALE_TIME,
  });
  const growthQuery = useQuery({
    queryKey: [ANALYTICS_KEY, "growth", 12],
    queryFn: () => getAnalyticsGrowth(12),
    staleTime: STALE_TIME,
  });
  const geographyQuery = useQuery({
    queryKey: [ANALYTICS_KEY, "geography"],
    queryFn: getAnalyticsGeography,
    staleTime: STALE_TIME,
  });
  return { overviewQuery, growthQuery, geographyQuery };
}

/** « Rétention » tab: weekly tenant cohorts (8 weeks). */
export function useRetention() {
  return useQuery({
    queryKey: [ANALYTICS_KEY, "retention", 8],
    queryFn: () => getAnalyticsRetention(8),
    staleTime: STALE_TIME,
  });
}

/** « Top boutiques » tab: range (default last 30 days, max 366 days, years 2000..9999) + query. */
export function useTopStores() {
  const [initial] = useState(() => lastDaysRange(TOP_STORES_DEFAULT_DAYS));
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const rangeError = topStoresRangeError(from, to);
  const rangeValid = rangeError === null;

  const query = useQuery({
    queryKey: [ANALYTICS_KEY, "top-stores", from, to],
    queryFn: () => getTopStores({ from, to }),
    enabled: rangeValid,
    staleTime: STALE_TIME,
    placeholderData: <T,>(prev: T | undefined) => (rangeValid ? prev : undefined),
  });

  const resetRange = useCallback(() => {
    const range = lastDaysRange(TOP_STORES_DEFAULT_DAYS);
    setFrom(range.from);
    setTo(range.to);
  }, []);

  return { query, from, setFrom, to, setTo, resetRange, rangeError };
}

/** « Adoption » tab: feature adoption among the tenants active over the last 30 days. */
export function useAdoption() {
  return useQuery({
    queryKey: [ANALYTICS_KEY, "adoption"],
    queryFn: getFeatureAdoption,
    staleTime: STALE_TIME,
  });
}

/** « Actualiser »: refetch every analytics query (mounted ones refetch now, others on next mount). */
export function useAnalyticsRefresh() {
  const queryClient = useQueryClient();
  return useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: [ANALYTICS_KEY] });
  }, [queryClient]);
}
