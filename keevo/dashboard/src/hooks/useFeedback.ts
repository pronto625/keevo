"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  getFeedback,
  getUnhandledHighCount,
  markFeedbackHandled,
  markFeedbackUnhandled,
} from "@/lib/api/admin-feedback";
import type { FeedbackFilters, FeedbackPage } from "@/types/feedback";

export const DEFAULT_FEEDBACK_FILTERS: FeedbackFilters = { type: "", priority: "", handled: "false" };

export const FEEDBACK_HIGH_COUNT_KEY = ["admin-feedback-high-count"] as const;

/** « Retours utilisateurs » list: filters, page, handle / unhandle actions. */
export function useFeedback() {
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<FeedbackFilters>(DEFAULT_FEEDBACK_FILTERS);
  const [page, setPage] = useState(0);
  /** Every feedback whose action is in flight (several rows can be clicked in a row). */
  const [pendingIds, setPendingIds] = useState<ReadonlySet<string>>(new Set());

  const query = useQuery<FeedbackPage>({
    queryKey: ["admin-feedback", filters, page],
    queryFn: () => getFeedback(filters, page),
    placeholderData: (prev) => prev,
  });

  // Handling the last item of the last page empties it: go back to the new last page. Adjusted
  // during render (React's « storing information from previous renders » pattern), not in an effect.
  const totalPages = query.data?.totalPages;
  if (totalPages !== undefined && !query.isPlaceholderData && page > 0 && page > totalPages - 1) {
    setPage(Math.max(0, totalPages - 1));
  }

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["admin-feedback"] });
    queryClient.invalidateQueries({ queryKey: FEEDBACK_HIGH_COUNT_KEY });
  };

  const track = (id: string, pending: boolean) =>
    setPendingIds((prev) => {
      const next = new Set(prev);
      if (pending) next.add(id);
      else next.delete(id);
      return next;
    });

  const handle = useMutation({
    mutationFn: markFeedbackHandled,
    onMutate: (id: string) => track(id, true),
    onSettled: (_d, _e, id) => track(id, false),
    onSuccess: () => {
      unhandle.reset();
      invalidate();
    },
  });
  const unhandle = useMutation({
    mutationFn: markFeedbackUnhandled,
    onMutate: (id: string) => track(id, true),
    onSettled: (_d, _e, id) => track(id, false),
    onSuccess: () => {
      handle.reset();
      invalidate();
    },
  });

  const resetActionErrors = () => {
    handle.reset();
    unhandle.reset();
  };

  const updateFilter = <K extends keyof FeedbackFilters>(key: K, value: FeedbackFilters[K]) => {
    resetActionErrors();
    setPage(0);
    setFilters((f) => ({ ...f, [key]: value }));
  };

  const changePage = (next: number) => {
    resetActionErrors();
    setPage(next);
  };

  return {
    query,
    filters,
    updateFilter,
    page,
    setPage: changePage,
    handle,
    unhandle,
    pendingIds,
    actionFailed: handle.isError || unhandle.isError,
    refresh: invalidate,
  };
}

/** Unhandled HIGH-priority feedback count of the sidebar badge (count endpoint, every minute). */
export function useUnhandledHighFeedbackCount() {
  return useQuery<number>({
    queryKey: FEEDBACK_HIGH_COUNT_KEY,
    queryFn: getUnhandledHighCount,
    staleTime: 60_000,
    refetchInterval: 60_000,
  });
}
