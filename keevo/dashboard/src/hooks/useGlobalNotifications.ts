"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import {
  cancelGlobalNotification,
  getGlobalNotifications,
  getRecipientEstimate,
  scheduleGlobalNotification,
  searchPushTenants,
} from "@/lib/api/admin-notifications";
import type {
  GlobalNotification,
  GlobalNotificationInput,
  GlobalNotificationTarget,
  PickedTenant,
} from "@/types/global-notification";

export const GLOBAL_NOTIFICATIONS_KEY = ["admin-global-notifications"] as const;
/** Status polling period of the « Envoi en cours » card. */
export const STATUS_POLL_MS = 10_000;

/** Polling stops this long after the scheduled time if the row is still not terminal. */
export const STATUS_POLL_CAP_MS = 15 * 60_000;

/** SCHEDULED or SENDING: the server has not finished with it. */
export function isSendPending(n: GlobalNotification | undefined): boolean {
  return !!n && (n.status === "SCHEDULED" || n.status === "SENDING");
}

/**
 * Pending for longer than the poll cap (scheduled time + 15 min): the dashboard stops polling and
 * shows « Statut inconnu, actualisez ».
 */
export function isStatusStale(n: GlobalNotification | undefined, now: number = Date.now()): boolean {
  return isSendPending(n) && now > new Date(n!.scheduledAt).getTime() + STATUS_POLL_CAP_MS;
}

/**
 * « Notifications » page: history, send, cancel and the status of the last send. The history is
 * polled every 10 s while the tracked send is SCHEDULED or SENDING (or not listed yet); polling stops
 * once it is sent / failed / cancelled, or 15 min after its scheduled time.
 */
export function useGlobalNotifications() {
  const queryClient = useQueryClient();
  /** Last send of this session: id + devices targeted at submit time. */
  const [tracked, setTracked] = useState<{ id: string; estimate: number } | null>(null);
  const [pendingCancelIds, setPendingCancelIds] = useState<ReadonlySet<string>>(new Set());
  /** Clock of the status card (« Programmé » vs « Envoi en cours », 15-min cap), ticked with the polling. */
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), STATUS_POLL_MS);
    return () => clearInterval(t);
  }, []);

  const query = useQuery<GlobalNotification[]>({
    queryKey: GLOBAL_NOTIFICATIONS_KEY,
    queryFn: getGlobalNotifications,
    placeholderData: (prev) => prev,
    refetchInterval: (q) => {
      if (!tracked) return false;
      const current = q.state.data?.find((n) => n.id === tracked.id);
      // Not listed yet (first refetch in flight) counts as pending.
      if (!current) return STATUS_POLL_MS;
      return isSendPending(current) && !isStatusStale(current) ? STATUS_POLL_MS : false;
    },
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: GLOBAL_NOTIFICATIONS_KEY });

  const send = useMutation({
    mutationFn: (input: GlobalNotificationInput) => scheduleGlobalNotification(input),
    onSuccess: (res) => {
      setTracked({ id: res.id, estimate: res.recipientEstimate });
      void refresh();
    },
  });

  const trackCancel = (id: string, pending: boolean) =>
    setPendingCancelIds((prev) => {
      const next = new Set(prev);
      if (pending) next.add(id);
      else next.delete(id);
      return next;
    });

  const cancel = useMutation({
    mutationFn: (id: string) => cancelGlobalNotification(id),
    onMutate: (id: string) => trackCancel(id, true),
    onSettled: (_d, _e, id) => {
      trackCancel(id, false);
      void refresh();
    },
  });

  const trackedNotification = tracked ? query.data?.find((n) => n.id === tracked.id) : undefined;

  return {
    query,
    send,
    cancel,
    pendingCancelIds,
    tracked,
    trackedNotification,
    now,
    dismissTracked: () => setTracked(null),
    refresh,
  };
}

/** Devices the target reaches (TENANT: only once a company is picked). */
export function useRecipientEstimate(target: GlobalNotificationTarget, tenantId: string | null) {
  return useQuery<number>({
    queryKey: ["admin-global-notifications-estimate", target, tenantId],
    queryFn: () => getRecipientEstimate(target, tenantId),
    enabled: target !== "TENANT" || !!tenantId,
    staleTime: 30_000,
  });
}

/** Company search of « Un commerce », debounced 300 ms, at least 2 characters. */
export function useTenantSearch(search: string) {
  const [debounced, setDebounced] = useState(search);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(search), 300);
    return () => clearTimeout(t);
  }, [search]);
  const term = debounced.trim();
  return useQuery<PickedTenant[]>({
    queryKey: ["admin-global-notifications-tenants", term],
    queryFn: () => searchPushTenants(term),
    enabled: term.length >= 2,
    staleTime: 30_000,
  });
}
