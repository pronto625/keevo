"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { getAlerts, getUnhandledHighAlertCount, markAlertHandled } from "@/lib/api/admin-alerts";
import type { AdminAlert } from "@/types/alerts";

export const ALERTS_KEY = ["admin-alerts"] as const;
export const ALERTS_HIGH_COUNT_KEY = ["admin-alerts-high-count"] as const;

/** « Alertes » page: feed, « Afficher les alertes traitées » toggle, « Marquer comme traité ». */
export function useAlerts() {
  const queryClient = useQueryClient();
  const [includeHandled, setIncludeHandled] = useState(false);
  /** Every alert whose action is in flight (several can be clicked in a row). */
  const [pendingKeys, setPendingKeys] = useState<ReadonlySet<string>>(new Set());

  const query = useQuery<AdminAlert[]>({
    queryKey: [...ALERTS_KEY, includeHandled],
    queryFn: () => getAlerts(includeHandled),
    placeholderData: (prev) => prev,
  });

  const track = (key: string, pending: boolean) =>
    setPendingKeys((prev) => {
      const next = new Set(prev);
      if (pending) next.add(key);
      else next.delete(key);
      return next;
    });

  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ALERTS_KEY }),
      queryClient.invalidateQueries({ queryKey: ALERTS_HIGH_COUNT_KEY }),
    ]);

  const handle = useMutation({
    mutationFn: (key: string) => markAlertHandled(key),
    onMutate: (key: string) => track(key, true),
    onSettled: (_d, _e, key) => track(key, false),
    onSuccess: () => {
      void refresh();
    },
  });

  const toggleHandled = (value: boolean) => {
    handle.reset();
    setIncludeHandled(value);
  };

  return {
    query,
    includeHandled,
    setIncludeHandled: toggleHandled,
    handle,
    pendingKeys,
    actionFailed: handle.isError,
    refresh,
  };
}

/** Sidebar badge: unhandled HIGH alerts (count-only endpoint, every minute). */
export function useUnhandledHighAlertCount() {
  return useQuery<number>({
    queryKey: ALERTS_HIGH_COUNT_KEY,
    queryFn: getUnhandledHighAlertCount,
    staleTime: 60_000,
    refetchInterval: 60_000,
  });
}
