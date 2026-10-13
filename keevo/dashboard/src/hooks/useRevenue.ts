"use client";

import { useCallback, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  downloadChurnCsv,
  getChurnReport,
  getRevenueSummary,
  getRevenueTrend,
} from "@/lib/api/admin-revenue";
import { currentMonth, isValidMonth, monthRange, revenueRangeError } from "@/lib/period";

export const REVENUE_SUMMARY_KEY = "admin-revenue-summary";
export const REVENUE_TREND_KEY = "admin-revenue-trend";
export const REVENUE_CHURN_KEY = "admin-revenue-churn";

/** Story 9-3 — « Revenus » page state: range filter (default current month), churn month, manual refresh. */
export function useRevenue() {
  const queryClient = useQueryClient();
  const [initialMonth] = useState(() => currentMonth());
  const [from, setFrom] = useState(() => monthRange(initialMonth).from);
  const [to, setTo] = useState(() => monthRange(initialMonth).to);
  const [churnMonth, setChurnMonthState] = useState(initialMonth);

  const rangeError = revenueRangeError(from, to);
  const rangeValid = rangeError === null;
  const churnMonthValid = isValidMonth(churnMonth);

  const summaryQuery = useQuery({
    queryKey: [REVENUE_SUMMARY_KEY, from, to],
    queryFn: () => getRevenueSummary({ from, to }),
    enabled: rangeValid,
    placeholderData: <T,>(prev: T | undefined) => (rangeValid ? prev : undefined),
  });

  const trendQuery = useQuery({
    queryKey: [REVENUE_TREND_KEY],
    queryFn: getRevenueTrend,
  });

  const churnQuery = useQuery({
    queryKey: [REVENUE_CHURN_KEY, churnMonth],
    queryFn: () => getChurnReport(churnMonth),
    enabled: churnMonthValid,
    placeholderData: <T,>(prev: T | undefined) => (churnMonthValid ? prev : undefined),
  });

  const exportMutation = useMutation({
    mutationFn: (month: string) => downloadChurnCsv(month),
  });

  const { reset: resetExport } = exportMutation;
  // A failed export belongs to the month it was asked for: clear it when the month changes.
  const setChurnMonth = useCallback(
    (month: string) => {
      setChurnMonthState(month);
      resetExport();
    },
    [resetExport],
  );

  const resetRange = useCallback(() => {
    const range = monthRange(currentMonth());
    setFrom(range.from);
    setTo(range.to);
  }, []);

  const refresh = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: [REVENUE_SUMMARY_KEY] });
    queryClient.invalidateQueries({ queryKey: [REVENUE_TREND_KEY] });
    queryClient.invalidateQueries({ queryKey: [REVENUE_CHURN_KEY] });
  }, [queryClient]);

  return {
    summaryQuery,
    trendQuery,
    churnQuery,
    from,
    setFrom,
    to,
    setTo,
    resetRange,
    rangeError,
    churnMonth,
    setChurnMonth,
    churnMonthValid,
    exportChurn: () => exportMutation.mutate(churnMonth),
    isExporting: exportMutation.isPending,
    exportFailed: exportMutation.isError,
    refresh,
  };
}
