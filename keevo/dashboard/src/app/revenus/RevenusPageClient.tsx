"use client";

import { AdminLayout } from "@/components/layout/AdminLayout";
import { DateRangeFilter } from "@/components/revenue/DateRangeFilter";
import { RevenueKpiCards } from "@/components/revenue/RevenueKpiCards";
import { MrrTrendChart } from "@/components/revenue/MrrTrendChart";
import { ChurnTable } from "@/components/revenue/ChurnTable";
import { useRevenue } from "@/hooks/useRevenue";

/** Story 9-3 — revenue dashboard (MRR/ARR, paying tenants, conversion, churn, CSV). */
export default function RevenusPageClient() {
  const {
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
    exportChurn,
    isExporting,
    exportFailed,
    refresh,
  } = useRevenue();

  const lastUpdated = summaryQuery.dataUpdatedAt ? new Date(summaryQuery.dataUpdatedAt) : null;
  const isFetching = summaryQuery.isFetching || trendQuery.isFetching || churnQuery.isFetching;

  return (
    <AdminLayout>
      <div className="p-6 space-y-6">
        <h1 className="text-xl font-bold text-gray-800">Revenus</h1>

        <DateRangeFilter
          from={from}
          to={to}
          onFromChange={setFrom}
          onToChange={setTo}
          onReset={resetRange}
          error={rangeError}
        />

        <RevenueKpiCards
          summary={summaryQuery.data}
          isLoading={summaryQuery.isLoading}
          lastUpdated={lastUpdated}
          onRefresh={refresh}
          isError={summaryQuery.isError}
          isFetching={isFetching}
          onRetry={() => void summaryQuery.refetch()}
          rangeInvalid={rangeError !== null}
        />

        <MrrTrendChart
          data={trendQuery.data}
          isLoading={trendQuery.isLoading}
          isError={trendQuery.isError}
          onRetry={() => void trendQuery.refetch()}
        />

        <ChurnTable
          report={churnQuery.data}
          isLoading={churnQuery.isLoading}
          isError={churnQuery.isError}
          onRetry={() => void churnQuery.refetch()}
          month={churnMonth}
          onMonthChange={setChurnMonth}
          monthValid={churnMonthValid}
          onExport={exportChurn}
          isExporting={isExporting}
          exportFailed={exportFailed}
        />
      </div>
    </AdminLayout>
  );
}
